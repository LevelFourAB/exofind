# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

Exofind is an experimental search engine built on S3-compatible object
storage, using Apache Lucene for indexing/search and Quarkus as the
application framework.

Three places hold what this file leaves out, and they are kept current rather
than summarized here:

- `README.md` - configuration, the toolchain, the shape of the API.
- `docs/` - why the design is the way it is, written for someone running
  Exofind. Read `docs/explanation/` before changing how nodes coordinate.
- The doc comment on the type itself - every rule below is explained where it
  is enforced, and that is where the whole argument lives.

## Commands

Common workflows are mise tasks: `mise run dev`, `build`, `test`, `verify`,
`storage`, `storage:stop`. The toolchain versions live in `mise.toml`.

`mise run bench` runs the JMH benchmarks under `src/benchmark/java`, which
compile only under the `benchmark` profile - see
`docs/how-to/benchmark-the-engine.md`.

Narrower runs go through Maven directly:

```bash
./mvnw test -Dtest=IndexTest              # one test class
./mvnw test -Dtest=IndexTest#testMethod   # one test method
./mvnw verify -Pnative                    # integration tests, needs a native build
```

Tests that talk to remote storage start their own container through
Testcontainers, so they need Docker but nothing started by hand. Dev mode
(`mise run dev`) uses the SeaweedFS from docker compose (`mise run storage`).

## Orientation

Code lives under `se.l4.exofind.engine`.

- **Where things are kept is one decision.** `StorageMode` is named by
  `EXOFIND_STORAGE_MODE` and `StorageProviders` produces the sync provider, the
  indexer ownership, the registry storage and the key storage from it together,
  so they can never disagree about whether there is a bucket. `LOCAL` is the
  default and means one node whose directory *is* the deployment rather than a
  copy of it.
- **An index is a name over generations.** `IndexRegistry` - one object in the
  bucket, replaced conditionally - says which indexes exist, which generations
  each has and which one the bare name answers for; `IndexName` is how
  `books@2` is taken apart. `Indexes` opens and caches `Index` instances, one
  per *generation*.
- **An `Index` owns one Lucene directory**, moves through a state machine as it
  syncs (`NEEDS_PULL` → `PULLING` → `USABLE` → `MODIFIED`) and pushes and pulls
  through a `StateSyncProvider`. Below the open cache, `Indexes` sweeps the
  coldest closed directories when `indexes.disk.maxSize` is set, and
  `LocalCopy` is what refuses to remove anything the remote does not fully
  hold.
- **`NodeState` says which indexes this node writes.** The `indexer` property
  is candidacy, `IndexerOwnership` divides the index names among the
  candidates through a leadership table; gaining or losing a name reopens its
  open generations through `NodeState`'s listeners. Writes for an index a node
  does not hold are forwarded by `IndexerForwardFilter`, per what the endpoint
  declares with `@ServedBy` - and a write for an index nothing holds is what
  appoints its writer, via `tryClaim`.
- **`se.l4.exofind.engine.auth` decides who a request is from.** `Keys` turns a
  bearer token into a `Principal`, and `AuthFilter` checks that principal
  against the `@RequiresPermission` the endpoint declares.

Only one node writes an index at a time, and that takes both halves: the
leadership table decides who *tries* (liveness), while the conditional
manifest writes and the epoch-scoped object keys are what stop a node that
wrongly believes it holds an index from corrupting anything (safety). Neither
alone is enough - see `docs/explanation/synchronization.md`.

Storing locally has neither half, so `StorageDirectoryLock` supplies what they
were protecting: a file lock held on the storage directory for as long as the
node runs, which makes "there is one process" true rather than assumed. It is
held in both modes, because two nodes over one directory write over the same
Lucene commits whether or not there is a bucket behind them.

## Rules that fail silently

Everything else is caught by the compiler, a test or validation. These are not:

- **A new usage, type or capability needs a name in `IndexFeatures`**, and
  anything that *narrows* a key needs one in `AuthFeatures`. Protobuf keeps
  fields it has no code for, so without a name an older node reads a newer
  definition, misses the part it does not understand and indexes anyway.
- **Names written to disk, or written by callers, are never renamed or
  reused**: feature names, `Permission` constants, matcher and clause
  identifiers, proto field numbers.
- **`definitions.proto` and `storage.proto` are storage formats** that have to
  stay readable for as long as an index exists. The rules are in the header
  comment of `definitions.proto` - read it before adding a field.
- **The stored definition and the REST contract version independently**, mapped
  by `IndexDefinitionMapper`. Its `checkRepresentable` holds only while one API
  definition always maps to one stored definition, so a mapping with two ways
  to store the same thing breaks the check rather than the round trip.
- **A logger comes from `Log.of`, never from `LoggerFactory`**. Both compile
  and both log; only the first keeps `addKeyValue` pairs as fields, because
  SLF4J flattens them into the message for every backend Quarkus can bind. The
  argument is on `Log`.
- **A benchmark run after a source change needs a clean build.** The
  incremental recompile leaves the classes JMH generated from the old sources
  stale, every fork dies, and the runner still exits zero - the failure shows
  only as benchmarks missing from the results. How to build clean without
  losing the built indexes is in `docs/how-to/benchmark-the-engine.md`.
- **The default matching chain rewrites words** - stemmed, decompounded, by
  locale - so a test that hand-picks words to sit a certain number of letters
  or typos apart is measuring the distance between what analysis leaves of
  them. Declare a normalize-only analyzer in the test's definition when the
  letters have to mean what they say.

## Where a new thing goes

- A matcher, a ranking shape or a query clause: a record, plus the branch in
  `QueryCompiler`, plus `createQuery` on every type the matcher means something
  for - a type it means nothing for throws.
- A way of using a field: on `FieldDef` when it works whatever the value is
  (`filter`, `sort`, `facet`), on the type when it depends on how the value is
  analyzed. In both the proto and `FieldDefinition`. Rules every type shares
  are checked in `Field.validate`, rules only one type can judge in its own.
- A locale: an entry in `Locales`, which registers `locale.<tag>` as a feature
  on its own.
- An indexable type: a case in `documents.proto` and in `DocumentSource`, or
  documents of it cannot be kept.

## Keeping this file useful

This file is a map and a list of traps, nothing more. Design rationale belongs
in the doc comment of the type that enforces it, or in `docs/explanation/` when
it outgrows one type; the `documentation` skill has the house rules for both.
If something here could be a doc comment, move it there and leave at most the
one line that says where to look.
