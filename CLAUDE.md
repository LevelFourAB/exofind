# CLAUDE.md

Exofind is a search engine built on S3-compatible object storage. It uses Apache Lucene for indexing and search, and Quarkus as the application framework.

This file holds the rules that nothing else enforces and a map to the places that hold everything else. Add a line only if it changes what you do and you could not find it by reading the code. What a type does belongs in its doc comment. Why the system is shaped as it is belongs in `docs/explanation/`. The `documentation` skill states the rules for both.

## Where to read

- `README.md`: Quick start, the toolchain and its mise tasks, and the container images.
- `docs/`: The manual, organized by Diátaxis. `docs/reference/` states the configuration settings, the API and the error codes. `docs/explanation/` gives the design rationale.
- `docs/explanation/synchronization.md`: Read it before changing node coordination. Only one node writes to an index at a time. The leadership table decides who tries (liveness), and conditional manifest writes with epoch-scoped keys stop a split-brain writer from corrupting data (safety). Neither is enough alone. Local mode uses neither: `StorageDirectoryLock` holds a file lock on the directory for the life of the process.
- `website/README.md`: How the site publishes `docs/`, the API pages, the demos and its own pages, and how it searches itself.

## Commands

Common workflows use mise tasks: `mise run dev`, `build`, `test`, `verify`, `storage`, and `storage:stop`. Toolchain versions are defined in `mise.toml`. `mise run site` serves the website and `mise run site:build` builds it; the engine build does not require Node.js.

Run targeted tests with Maven directly:

```bash
./mvnw test -Dtest=IndexTest              # one test class
./mvnw test -Dtest=IndexTest#testMethod   # one test method
```

Tests that use remote storage start a container through Testcontainers, so they need Docker and nothing started by hand. `mise run dev` stores everything on local disk because `EXOFIND_STORAGE_MODE` defaults to `local`. To run it against object storage, start SeaweedFS with `mise run storage` and set the mode and the remote settings from `docs/reference/configuration.md`.

Run every task that installs Node dependencies outside an agent sandbox. Inside one, `pnpm install` removes `website/node_modules` and then fails with `ERR_PNPM_EPERM` because the sandbox refuses the reflink out of the store. Repair a part-installed directory by running the task again outside the sandbox.

`mise run bench` runs the JMH benchmarks under `src/benchmark/java`, which compile only under the `benchmark` profile. See `docs/how-to/benchmark-the-engine.md`.

## Rules that fail silently

The compiler, tests and validation catch most mistakes. The ones below compile, pass, and do the wrong thing. Each names the type that guards it; read that type before changing what it guards.

Stored formats and identifiers:

- **Persistent identifiers are immutable.** Feature names, `Permission` constants, matcher and clause identifiers, Protocol Buffers field numbers, the error codes `DefinitionCompatibility` reports, and the meter and tag names in `Meters` are written to disk, sent by clients, or queried by dashboards outside this repository. A rename compiles and stops matching.
- **A REST change must be additive within an API version.** `docs/reference/api-conventions.md` states what a client may rely on: paths, methods, field names and types, error-code meanings, the permission an endpoint needs, and the status class of a condition. A new request field must be optional and default to the behavior before it. Renaming a field or narrowing a status code compiles, passes the tests, and breaks every client. A change that cannot be additive needs a new version prefix, so raise it instead of making it.
- **An error code a client acts on needs a `@ReturnsError` on the endpoint.** `ErrorCodeFilter` writes the annotations into the OpenAPI document, and the site draws them under the status that carries them. A code named only in the prose of a description is a code no client generator and no page can read, and nothing reports it. `ErrorCodeFilterTest` holds every annotated code to an `ErrorType` the engine declares.
- **Protocol Buffers formats last the life of an index.** Follow the header comment of `definitions.proto` before adding a field to it or to `storage.proto`.
- **Every new capability needs a feature name.** Register usages, types, and capabilities in `IndexFeatures`, key constraints in `AuthFeatures`, and parts of the search settings in `SearchSettingsFeatures`. Without a name, an older node ignores the unknown field and indexes or searches with part of the definition.
- **Every setting that changes written data needs a branch in `DefinitionCompatibility`.** The class enumerates settings one by one. A setting without a branch lets the engine accept documents that lack it, and queries return incomplete results. Settings that only affect queries need no branch.
- **One API definition maps to one stored definition.** `IndexDefinitionMapper` maps them, and `checkRepresentable` fails on a mapping that allows two stored forms of one API definition.
- **A file the engine rewrites beside the segments ends in `.ef.bin`.** `ObjectStorageSync` keys such a file by its checksum, so a rewrite never replaces the object an earlier manifest names. Any other name is taken for a Lucene file and keyed by its epoch alone, so a rewrite of it in the same session silently overwrites the referenced object.
- **A transducer in `locale-data/` reads under one Lucene version.** An `.fst` file another version wrote turns decompounding off with no error. `DecompounderTest` and `LemmatizerTest` check every shipped file, and `tools/locale-data/README.md` states how to rebuild them.

Coordination between nodes:

- **Only a removal mark says stored data was deleted.** A prefix the registry does not name is either a deleted index or a lost registry, and the repair rebuilds the registry from such prefixes. A sweep or a creation that removes objects from registry absence alone destroys data. See `IndexRemovals`.
- **Only `IndexerOwnership.Listener.onOwnershipRevoked` tells a lost claim from a handover.** A node that hands over pushes what it holds. A node whose claim was taken must push nothing, or it replaces documents its successor already acknowledged. The signal reaches the generations through `NodeState.revokeOwnership` and `Index.revokeWriting`.
- **Only the answer of `Index.reopen(true)` says a handover may go ahead.** `ObjectStorageIndexerOwnership` reads it through `Indexes.flushForHandover`. A caller that drops the answer releases the claim after a failed push, and the successor pulls a manifest without those documents.

Caches and search internals:

- **`FacetStates.Scope` is the whole key of a cached facet result.** Any new input that changes what matches without reopening the reader must join it, and `FacetStates.shapeOf` must copy any new `Facet` component, or searches answer stale or foreign counts.
- **Every Lucene document carries every analyzed field.** `AnalyzedFields` enumerates them through `FieldType.collectAnalyzedFields`, and `Index.addDocument` pads the document. A field a type writes but does not report sends scoring through sparse norms with no change in results.
- **A signal field lives only in its sort doc values.** A refresh replaces those and nothing else, so the field is left out of the stored source and never written as a stored field. Every path that turns a Lucene document into a `Document` fills it in through `SignalValues` after the `DocumentCache` has answered, never inside the cache. A new read path that skips the fill returns documents without the field and nothing reports it.
- **A search borrows threads only from `SearchThreads`.** Those carry the `SearchDeadline` budget. Work on any other executor runs past the timeout and nothing reports it.
- **The default matching chain stems and decompounds.** A test that depends on exact characters or edit distances needs a normalize-only analyzer in its index definition.

Observability:

- **Create loggers with `Log.of`, not `LoggerFactory`.** Only `Log.of` keeps `addKeyValue` pairs as structured fields on the Quarkus backends.
- **A meter tagged with an index name costs one series per index.** Add one only where the question needs the name, register it only on the node that can answer, and keep histograms off it. `NodeMetrics` states which meters live where.

Build outputs that nothing compares:

- **`website/public/openapi.yaml` is a checked-in copy.** Run `mise run site:openapi` after changing an endpoint, a schema, or an annotation description, or the site publishes the previous API. The site renders parameter examples, `@ExampleObject` bodies, and the `examples` of a `@Schema` beside the rows of the type that states it.
- **The index the site searches itself with is loaded separately.** Run `mise run site:index` against the node the site searches after a page's text or headings change. `website/search/README.md` states what the load does.
- **JMH benchmarks need a clean build after a source change.** Stale generated classes make the forks fail while the runner exits zero, so the failure shows only as missing results. `docs/how-to/benchmark-the-engine.md` states how to clean without losing indexes.
