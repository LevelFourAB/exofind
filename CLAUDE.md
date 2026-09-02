# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

Exofind is an experimental search engine built on S3-compatible object storage. It uses Apache Lucene for indexing and search, and Quarkus as the application framework.

Three other locations contain details not summarized in this file:

- `README.md`: Quick start, the toolchain and its mise tasks, and the container images.
- `docs/`: The manual, organized by Diátaxis. `docs/reference/` states the configuration settings, the API and the error codes. `docs/explanation/` gives the design rationale; read it before changing node coordination.
- Doc comments on types: Detailed explanations of enforced rules and their rationale.

`website/` publishes `docs/`, a page per API endpoint, and the demo pages to GitHub Pages by reading Markdown source files directly. To add a document, create a file in `docs/` and add an entry to `docs/README.md`, which generates the sidebar. For more details, see `website/README.md`.

## Commands

Common workflows use mise tasks: `mise run dev`, `build`, `test`, `verify`, `storage`, and `storage:stop`. Toolchain versions are defined in `mise.toml`.

`mise run site` serves the website, including documentation and demo pages. `mise run site:build` builds the website. The website build is separate from the engine build; the engine does not require Node.js.

`mise run bench` runs JMH benchmarks under `src/benchmark/java`. These benchmarks compile only under the `benchmark` profile. For details, see `docs/how-to/benchmark-the-engine.md`.

Run targeted tests with Maven directly:

```bash
./mvnw test -Dtest=IndexTest              # one test class
./mvnw test -Dtest=IndexTest#testMethod   # one test method
./mvnw verify -Pnative                    # integration tests, needs a native build
```

Tests that interact with remote storage start a container through Testcontainers. These tests require Docker, but do not require manually started services. Development mode (`mise run dev`) stores everything on local disk, because `EXOFIND_STORAGE_MODE` defaults to `local`. To run it against object storage, start SeaweedFS with `mise run storage` and set the mode and the remote settings from `docs/reference/configuration.md`.

## Orientation

Source code is located under `se.l4.exofind.engine`.

- **Storage configuration:** `StorageMode` is configured by `EXOFIND_STORAGE_MODE`. `StorageProviders` creates the sync provider, indexer ownership, registry storage, and key storage together. This ensures all components agree on whether a bucket is configured. `LOCAL` is the default mode, where a single node's directory serves as the deployment rather than a copy.
- **Index generations:** An index represents a name over multiple generations. `IndexRegistry` is stored as a single object in the bucket and replaced conditionally. It tracks existing indexes, their generations, and which generation serves requests for the bare index name. `IndexName` parses generation names such as `books@2`. `Indexes` opens and caches `Index` instances, with one instance per generation. `RegistryPoller` reads the registry once for the whole node and hands each read to the `RegistryPoller.Listener` beans that work from it, so a new part that polls storage per index belongs there rather than on a schedule of its own.
- **Index state and directory ownership:** An `Index` owns one Lucene directory. It moves through a state machine during synchronization (`NEEDS_PULL` → `PULLING` → `USABLE` → `MODIFIED`) and pushes and pulls data through a `StateSyncProvider`. Below the open cache, `Indexes` removes the coldest closed directories when `exofind.indexes.disk.max-size` is set. `LocalCopy` prevents removal of any data that remote storage does not fully contain.
- **Node state and index writing:** `NodeState` tracks which indexes the local node writes. The `exofind.indexer.enabled` property controls candidacy. `IndexerOwnership` distributes index names among candidates through a leadership table. Gaining or losing an index name reopens active generations through listeners on `NodeState`. Writes for unassigned indexes are forwarded by `IndexerForwardFilter`, based on the `@ServedBy` annotation on the endpoint. A write request for an unassigned index claims a writer using `tryClaim`.
- **Reindexing generations:** The `se.l4.exofind.engine.reindex` package populates a new generation from an existing one. `ReindexJobs` manages the copy-replay-hold-promote workflow using a durable record per index (`ReindexJobStorage`). Successor nodes use this record to resume interrupted jobs. Reindex jobs run on the node that owns the index writer, and any node can query job status from the record.
- **Metrics:** The `se.l4.exofind.engine.metrics` package registers what a node reports. `Meters` names every meter and tag, `NodeMetrics` registers the gauges and rebuilds the per-index rows, and `RequestMetrics` is what call sites record through. `docs/reference/metrics.md` is the copy an operator reads.
- **Authentication:** The `se.l4.exofind.engine.auth` package holds the keys and what they grant. `Keys` converts a bearer token into a `Principal`. The `se.l4.exofind.engine.api.auth` package applies that principal to a request: `AuthFilter` validates it against the `@RequiresPermission` annotation declared on the endpoint.

Only one node writes to an index at a time. This requires two coordination mechanisms:

- The leadership table determines which node attempts writes (liveness).
- Conditional manifest writes and epoch-scoped object keys prevent split-brain nodes from corrupting data (safety).

Neither mechanism is sufficient on its own. For more information, see `docs/explanation/synchronization.md`.

Local storage does not use remote synchronization mechanisms. Instead, `StorageDirectoryLock` holds a file lock on the storage directory for the lifetime of the node process. This guarantees that only one process accesses the directory. The file lock is held in both local and remote modes, because multiple nodes sharing a directory can overwrite Lucene commits regardless of object storage.

## Rules that fail silently

The compiler, tests, or validation catch most errors. The following issues fail silently if overlooked:

- **Feature registration in `IndexFeatures` and `AuthFeatures`:** Register any new usage, type, or capability in `IndexFeatures`. Register any key constraint in `AuthFeatures`. Protocol Buffers preserves unknown fields during decoding. Without an explicit feature name, an older node reads a newer definition, ignores unrecognized fields, and indexes data incorrectly.
- **Comparisons in `DefinitionCompatibility`:** Any setting that affects written index data requires an explicit check in `DefinitionCompatibility`. The class enumerates each setting individually rather than deriving compatibility automatically. If you add a setting without a compatibility check, the engine accepts documents that lack that setting, leading to incomplete query results. Settings that only affect search queries do not require compatibility checks.
- **Persistent identifier immutability:** Never rename or reuse identifiers that are written to disk or sent by clients. This includes feature names, `Permission` constants, matcher and clause identifiers, Protocol Buffers field numbers, and error codes reported by `DefinitionCompatibility`. Meter and tag names in `Meters` are the same kind of identifier: dashboards and alerts are written against them and live outside this repository, so a rename leaves a query that still parses and returns no data.
- **Tagging a meter with an index name:** A deployment can hold hundreds of indexes, and a backend that bills per active series charges for each one. Add a per-index meter only where the question cannot be answered without the name, register it only on the node that can answer it, and keep histograms off it unless a setting asks for them. `NodeMetrics` states which meters are registered where.
- **The OpenAPI document the site publishes:** `website/public/openapi.yaml` is a copy of what the engine build writes to `target/openapi/openapi.yaml`, checked in because the Pages workflow builds no Java. Nothing compares them. A change to an endpoint, a schema or an annotation description is published only after `mise run site:openapi`; until then the site builds and deploys the previous API. The `servers` block is the one part not derived from annotations, and lives in `src/main/resources/META-INF/openapi.yaml`.
- **Where an API example is visible:** The site renders the examples on a parameter and the `@ExampleObject` values of a body. It ignores `examples` on a `@Schema`, so an example put there alone reaches a generated client and no reader of the pages. `SchemaExampleTest` reads every example back into its model, but it cannot tell you where one is shown.
- **Protocol Buffers backward compatibility:** `definitions.proto` and `storage.proto` define storage formats that must remain readable for the lifetime of an index. Follow the rules in the header comment of `definitions.proto` before adding fields.
- **Independent definition versioning:** Stored definitions and the REST API contract version independently and are mapped by `IndexDefinitionMapper`. The `checkRepresentable` validation assumes that each API definition maps to exactly one stored definition. If a mapping allows multiple stored representations for the same API definition, `checkRepresentable` fails.
- **Logger instantiation with `Log.of`:** Always instantiate loggers using `Log.of` instead of `LoggerFactory`. While both compile and log output, only `Log.of` preserves `addKeyValue` pairs as structured fields. SLF4J flattens structured key-value pairs into the log message on Quarkus logging backends. See `Log` for details.
- **Clean builds for JMH benchmarks:** Always run a clean build before running benchmarks after modifying source code. Incremental compilation leaves stale JMH-generated classes, causing benchmark forks to fail while the test runner still exits with status zero. This failure only appears as missing benchmark results. For instructions on performing a clean build without losing existing indexes, see `docs/how-to/benchmark-the-engine.md`.
- **Transducer compatibility in `locale-data/`:** Files with the `.fst` extension under `locale-data/` can only be read by the exact version of Lucene that generated them. The engine does not fall back to the adjacent `.txt.gz` source files at runtime. Upgrading Lucene without rebuilding `.fst` files silently disables decompounding, causing compounds to be indexed without splitting. `DecompounderTest` and `LemmatizerTest` validate every shipped transducer during the build. To rebuild transducer files, see `tools/locale-data/README.md`.
- **What a kept facet answer is keyed by:** `FacetStates.Scope` is the whole key of a cached facet result: the clauses, locale, search settings version and definition version. Any new input that changes what clauses match without reopening the reader must be added to it, or a search answers stale counts for as long as the reader stays open. Nothing detects the omission.
- **Matching chain text analysis in tests:** The default matching chain analyzes text by stemming, decompounding, and applying locale-specific rules. Tests that rely on exact character distances or specific edit distances must account for this analysis. To test exact character matching, configure a normalize-only analyzer in the test index definition.

## Where a new thing goes

When adding components, place them in the following locations:

- **Matchers, ranking shapes, or query clauses:** Create a record, add a branch in `QueryCompiler`, and implement `createQuery` on every supported type. Unsupported types must throw an exception.
- **Field usage types:** In `definitions.proto`, add field features to the `FieldDef` message if they apply regardless of value type (`filter`, `sort`, `facet`). Add them to the message of the specific type if they depend on value analysis. Map the new configuration to the API in `FieldDefinition` and `IndexDefinitionMapper`. Validate shared field rules in `Field.validate`, and type-specific rules in the corresponding type class. Add a branch in `DefinitionCompatibility` because enabling a field feature does not re-index existing documents.
- **Locales:** Add an entry in `Locales`. `IndexFeatures` derives a `locale.<tag>` feature from every registered locale, and a `decompound.<tag>` feature from every locale that splits compounds. For locales whose analysis data is stored in `locale-data/` rather than the JAR file (such as Icelandic), register the locale only when the data files are installed. This ensures nodes without language data reject incompatible definitions rather than indexing unanalyzed text. Use `tools/locale-data/` to regenerate locale data files. Word lists and lemma lookups include both a runtime memory-mapped `.fst` file and a source text file.
- **Indexable types:** Add a case to `documents.proto` and `DocumentSource` to allow storing documents of that type.

## Keeping this file useful

Keep this file concise: it serves as a map of the repository and a list of silent failure modes. Place design rationale in the doc comment of the enforcing type, or in `docs/explanation/` if the rationale spans multiple types. Follow the guidelines in the `documentation` skill. If content in this file belongs in a doc comment, move it to the source code and keep only a single-line reference here.
