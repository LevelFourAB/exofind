# Configuration

Exofind is configured through environment variables. Every variable starts with
`EXOFIND_`, apart from the `QUARKUS_` and `JAVA_OPTS` variables read by the
framework and the JVM. Configuration is read through MicroProfile Config, so
each variable is also available as a dotted property
(`EXOFIND_STORAGE_REMOTE_URL` corresponds to `exofind.storage.remote.url`),
which is how tests and `application.properties` set them.

## Storage

The storage mode specifies where the node keeps indexes, the index registry,
and authentication keys. The mode is explicitly set rather than inferred from
other variables. If storage settings are invalid, the node refuses to start.

The following table lists storage configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_STORAGE_MODE` | Storage mode: `object` to store data in a shared S3-compatible bucket, or `local` to store data on local disk. | `local` |
| `EXOFIND_STORAGE_LOCAL_DIRECTORY` | Directory where the node writes data. In `object` mode, it holds local copies of indexes. In `local` mode, it holds the only copy of indexes, the registry, and keys. | Required |

`local` mode is for a single node, such as a local test environment or a
single container. The node does not copy data to remote storage, and additional
nodes cannot join or take over. If the directory is lost, all indexes and keys
are lost. A node started in `local` mode logs this status.
`EXOFIND_INDEXES_DISK_MAX_SIZE` does not free disk space in `local` mode. For
more information, see [Disk use](#disk-use).

`EXOFIND_INDEXER_ENABLED` defaults to `true` in `local` mode because the single
node must perform writes.

Only one node can run against a directory at a time. The node claims the
directory with file locks for as long as the node runs. A second node pointed
at the same directory refuses to start. Because file locking over NFS and SMB
is unreliable, use locally attached disk storage.

### Object storage

These variables are read in `object` mode and ignored in `local` mode.

The following table lists object storage configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_STORAGE_REMOTE_URL` | URL of the S3-compatible storage. | Required |
| `EXOFIND_STORAGE_REMOTE_ACCESS_KEY` | Access key for authentication. | Required |
| `EXOFIND_STORAGE_REMOTE_SECRET_KEY` | Secret key for authentication. | Required |
| `EXOFIND_STORAGE_REMOTE_REGION` | Region of the object storage. | None |
| `EXOFIND_STORAGE_REMOTE_BUCKET` | Bucket where indexes are stored. | Required |
| `EXOFIND_STORAGE_REMOTE_PREFIX` | Key prefix within the bucket when sharing a bucket with other services. | None |

## Locale data

The following table lists locale data configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_LOCALE_DATA_DIRECTORY` | Directory holding language data that is not built into the engine. | `locale-data` under the working directory |

:::note
The default container image bundles the locale data, so in most cases you will not need to set this. For other deployments see the [`locale-data` folder in the repository](https://github.com/LevelFourAB/exofind/tree/main/locale-data).
:::

`EXOFIND_LOCALE_DATA_DIRECTORY` contains one subdirectory per locale, named after the locale tag. For what the data is used for, see [compound words](analysis.md#compound-words) and the [locale reference](locales.md).

The engine reads only the following files from a locale directory:

| File | Format | May be gzipped |
|------|--------|----------------|
| `patterns.txt` | Text, hyphenation patterns | Yes |
| `stopwords.txt` | Text, one word per line | Yes |
| `words.fst` | Binary transducer of compound parts | No |
| `stemming.fst` | Binary transducer, word form to dictionary form | No |

The engine reads no other files. `words.txt.gz` and `stemming.txt.gz` exist in the project repository as source files to build the `.fst` files. They are not read at runtime and are not part of a deployment.

### File requirements and missing file behavior

- **Compound splitting:** Requires both `patterns.txt` and `words.fst`. If either file is missing, the engine indexes compound words whole and reports no error.
- **Lookup stemming:** `stemming.fst` is required for locales that use lookup stemming instead of a built-in stemmer. Icelandic (`is`) is the only locale that requires this file. Without this file, the node reports the locale as unsupported and rejects index definitions naming it.

The node determines supported locales at startup. Data installed while a node runs takes effect at the next restart.

### Resource use

The engine reads `.fst` files through a memory map. They cost almost no heap and load in no measurable time. Icelandic is the largest dataset and costs about 1.5 MB of heap.

The engine loads a locale's data the first time an index uses that locale, and holds it until the node stops.

### Version compatibility

A `.fst` file can only be read by the version of Lucene that wrote it. Each Exofind release ships compatible files in the `locale-data` directory of the distribution. If you point a node at a data directory from a different release, the node fails when the locale is first used. The file is never read incorrectly.

## Indexer role

The following table lists indexer configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_INDEXER_ENABLED` | Specifies whether this node can write indexes. Multiple indexer candidates divide indexes through a leadership table in object storage. Each index is written by one node at a time. Other nodes take over an index if its holder stops or stalls. | `false` (`true` in `local` mode) |
| `EXOFIND_INDEXER_LEASE_DURATION` | Duration an index lease is held before expiring without renewal. Failover takes approximately this duration. Renewal occurs at one third of this duration. | `30s` |
| `EXOFIND_INDEXER_REINDEX_MAX_CONCURRENT` | Number of reindex jobs one node runs at once. Accepted jobs past the limit wait in the `pending` phase. | `2` |
| `EXOFIND_INDEXER_REINDEX_SWEEP_INTERVAL` | Interval at which an indexer candidate looks for unfinished reindex jobs whose index no node holds, and claims them to resume. | `30s` |
| `EXOFIND_INDEXER_REINDEX_CATCHUP_INTERVAL` | Interval at which a `ready` reindex job replays what changed in the source, so a manual promote stays quick. | `30s` |
| `EXOFIND_INDEXER_REINDEX_PROMOTE_GRACE` | Delay between a reindex job's promote and its final catch-up sweep, for writes that resolved the index name just before the promote. | `1s` |
| `EXOFIND_NODE_ID` | Identifier this node uses in the leadership table. | Hostname with a random suffix |
| `EXOFIND_NODE_ADDRESS` | Network address where this node serves write requests. Recorded in the leadership table so other nodes can forward write requests. Must be reachable by other nodes. If not set, write requests to other nodes are rejected instead of forwarded. | None |

The indexer requires storage that enforces conditional writes (`If-Match` on
`PUT`) to prevent write collisions. Amazon S3 and SeaweedFS enforce conditional
writes. The node verifies conditional write support at startup and refuses to
run as an indexer against storage that does not support them.

## Authentication

Keys are stored in the configured storage backend. Only node-specific settings
are configured through environment variables. For information about key
permissions and the keys API, see [Authentication](auth.md).

The following table lists authentication configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_AUTH_MODE` | Authentication mode: `keys` to validate credentials on every request, or `none` to disable authentication and allow all requests. | `keys` (`none` in development mode) |
| `EXOFIND_AUTH_ROOT_KEY` | Administrative credential with full access, accepted only by this node and not stored in storage. Provide either the raw key value or `sha256:` followed by its hash. Used to create the initial key or recover access if all administrative keys are deleted. | None |
| `EXOFIND_AUTH_ANONYMOUS_KEY` | ID of the key applied to unauthenticated requests. The referenced key must have only the `search` permission, or the node refuses to start. If not set, unauthenticated requests are rejected. | None |
| `EXOFIND_AUTH_REFRESH_INTERVAL` | Interval at which the node refreshes keys from storage. Revoking a key can take up to this interval to propagate. Unseen keys are looked up immediately, at most once per interval. | `10s` |

A node running in `keys` mode refuses to start if it cannot read stored keys and
has no `EXOFIND_AUTH_ROOT_KEY` configured. If a node in `object` mode cannot
connect to storage, you can access it only with the root key. A node in `local`
mode stores keys on local disk.

## Index management

The following table lists index management configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_INDEXES_MAX_OPEN` | Maximum number of indexes kept open simultaneously. | Unbounded |
| `EXOFIND_INDEXES_REFRESH_INTERVAL` | Longest a node waits before it considers pulling an open index, and the shortest it leaves between two storage requests for the same generation. Also the longest that promoting a generation takes to reach other nodes. Unseen indexes are looked up immediately, at most once per interval. | `30s` |
| `EXOFIND_INDEXES_REFRESH_CONCURRENCY` | Number of indexes refreshed concurrently. | `4` |
| `EXOFIND_INDEXES_VERIFY_INTERVAL` | Maximum time an index's manifest or search settings go unchecked against storage when the registry reports no change for them. The registry's change hints let a refresh skip per-index requests; this interval bounds the staleness if a hint is lost. Keep it above both refresh intervals, which are otherwise held down to it. | `10m` |
| `EXOFIND_SETTINGS_REFRESH_INTERVAL` | Longest a node waits before it considers re-reading the search settings of an index it serves, and the shortest it leaves between two reads of the same index's settings. Changing or removing settings can take up to this interval to reach other nodes; the node that served the change applies it immediately. | `10s` |
| `EXOFIND_INDEXES_CLOSE_GRACE_PERIOD` | Grace period that an evicted index waits for in-flight requests before closing. | `10s` |
| `EXOFIND_INDEXES_PRELOAD_IDLE_LIMIT` | Number of indexes a node may hold before it stops preparing indexes that nobody has written recently. When a candidate node is given an index to write, it pulls the index copy and opens the Lucene writer straight away so the first write does not have to wait for that work. Below the limit, a node prepares every index it is given. Above the limit, a node prepares only indexes that were being written when they moved to it. Set to `0` to prepare only indexes that were being written. A node already at `EXOFIND_INDEXES_MAX_OPEN` prepares nothing, regardless of this setting. | `16` |

A node reads the index registry once for everything that works from it, at the
shorter of `EXOFIND_INDEXES_REFRESH_INTERVAL` and
`EXOFIND_SETTINGS_REFRESH_INTERVAL`. The registry names what changed, so a
change often reaches a node sooner than its own interval promises. Each
interval remains the guarantee: the node makes no two storage requests for the
same index inside it, and lets nothing go longer than it without a look.
Lowering either interval therefore also makes the other react faster.

## Committing

The indexer automatically commits changes to make them searchable. Commits
also push data to remote storage, making changes visible to other nodes after at
most one `EXOFIND_INDEXES_REFRESH_INTERVAL`.

To disable automatic commits, set a trigger to `0`. If both triggers are
disabled, an index commits only when requested through the API. For more
information, see [Admin API](admin-api.md). When loading datasets, disable both
triggers or increase `EXOFIND_INDEXES_COMMIT_MAX_CHANGES` to commit once at the
end.

The following table lists commit configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_INDEXES_COMMIT_MAX_CHANGES` | Maximum number of uncommitted document changes before triggering a commit. | `10000` |
| `EXOFIND_INDEXES_COMMIT_MAX_INTERVAL` | Maximum duration uncommitted changes can wait before triggering a commit. | `5s` |

If a commit fails, the node retries with exponential backoff up to one minute
while retaining pending changes. Retries are abandoned in two cases: another
node wrote to storage first (triggering an index pull), or the node lost write
ownership of the index.

## Segment merging

Each commit creates a Lucene segment, and Lucene merges small segments into
larger ones in the background on the node that writes the index. A push uploads
the merged segments and deletes the objects they replaced, so remote storage
holds the merged form and the object count stays bounded however often the
index commits.

A merge can finish after the last commit, leaving merged segments that no
commit has taken. The indexer then commits and pushes them on its own, one
`EXOFIND_INDEXES_COMMIT_MAX_INTERVAL` after the last commit. With the interval
trigger disabled, finished merges wait for the next commit.

Frequent commits create many small segments, and each merge uploads its result
again. Setting `EXOFIND_INDEXES_MERGE_FLOOR_SEGMENT` makes Lucene merge
segments below that size toward it ahead of its usual tiers, which keeps the
number of small objects down at the cost of rewriting small segments more
often. Leave it unset to use Lucene's default.

The following table lists segment merging configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_INDEXES_MERGE_FLOOR_SEGMENT` | Segment size below which Lucene merges segments toward that size, specified in bytes with an optional `K`, `M`, `G`, or `T` binary suffix. | Lucene's default |

## Disk use

Closing an index retains its files on disk. Setting
`EXOFIND_INDEXES_DISK_MAX_SIZE` enables a background sweep that deletes local
copies of inactive indexes until disk usage is 10% below the configured limit.
Indexes are ranked by access frequency, with accesses halving in value after
each half-life period. Evicted indexes are re-downloaded from storage when
requested.

Local index copies are deleted only if all changes exist in remote storage.
Unpushed commits or definitions are retained and logged as warnings. Because
all copies in `local` mode are unique, `EXOFIND_INDEXES_DISK_MAX_SIZE` does not
delete files in `local` mode.

The following table lists disk usage configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_INDEXES_DISK_MAX_SIZE` | Maximum disk space allocated for local index copies, specified in bytes with an optional `K`, `M`, `G`, or `T` binary suffix. | Unbounded |
| `EXOFIND_INDEXES_DISK_MIN_IDLE` | Minimum idle time required to retain an index copy regardless of the disk limit. | `24h` |
| `EXOFIND_INDEXES_DISK_HALF_LIFE` | Half-life duration after which unopened index access counts are halved. | `168h` |
| `EXOFIND_INDEXES_DISK_SWEEP_INTERVAL` | Interval between disk space cleanup checks. | `1h` |

## Index removal

Deleting an index or generation in object storage mode marks its storage in the bucket. A background sweep on nodes that can index removes the marked storage once the mark is older than the grace period, during which a [registry repair](../how-to/repair-the-index-registry.md#restore-a-deleted-index-or-generation) can restore it. Both settings do nothing in `local` storage mode.

The following table lists index removal configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_INDEXES_REMOVAL_GRACE` | Duration marked storage of a deleted index or generation stays in the bucket before the sweep removes it. | `1h` |
| `EXOFIND_INDEXES_REMOVAL_SWEEP_INTERVAL` | Interval at which nodes that can index check for marked storage whose grace period has expired. | `10m` |

## Document cache

Stored fields are compressed. Reading search results decompresses document hits
on each request. Setting `EXOFIND_INDEXES_DOCUMENT_CACHE_MAX_SIZE` enables a
shared heap cache across all indexes on the node.

Cache entries are associated with Lucene index segments. Unmodified segments
retain cache entries across commits, while merged or closed segments discard
their entries.

The document cache is stored on the Java heap. For heap sizing recommendations,
see [Page cache and heap](../explanation/node-resources.md#page-cache-and-heap).

The following table lists document cache configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_INDEXES_DOCUMENT_CACHE_MAX_SIZE` | Maximum memory allocated to cached documents, specified in bytes with an optional `K`, `M`, `G`, or `T` binary suffix. | Off |

## Search

Each variable in this section caps what a single request may ask a node to do. For the reasoning behind the caps, see [Cost of a single search](../explanation/node-resources.md#cost-of-a-single-search).

The following table lists search configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_SEARCH_MAX_LIMIT` | Maximum number of results one page may hold. Requests asking for a larger `limit` are rejected with `search:limit:too_large`. The node ranks, reads, and highlights every result on the page, so this caps what one response costs. | `1000` |
| `EXOFIND_SEARCH_MAX_PAGE_DEPTH` | Maximum result depth allowed for offset-based pagination. Requests exceeding this depth are rejected with `search:page:too_deep`, and page numbers past the limit are not returned. Cursor-based pagination using `next` and `previous` is not capped. | `10000` |
| `EXOFIND_SEARCH_MAX_RESCORE_WINDOW` | Maximum number of results a `rescore` block may score a second time. Requests asking for a larger window are rejected with `search:rescore:window_invalid`. Every result in the window is scored again on every request, so this caps what one search costs. | `1000` |
| `EXOFIND_SEARCH_MAX_KNN_K` | Maximum number of neighbours one `knn` clause may ask for. Requests with a larger `k` are rejected with `search:clause:k_too_large`. The vector index collects `k` candidates per segment, so this caps what one clause costs. | `1000` |
| `EXOFIND_SEARCH_MAX_FUSE_DEPTH` | Maximum number of results read from each ranking of a `fuse` clause. Requests with a larger `depth` are rejected with `search:clause:depth_too_large`. Every ranking runs as its own search and is read to this depth. | `1000` |
| `EXOFIND_SEARCH_MAX_CLAUSES` | Maximum number of clauses one request may hold, counted across `query`, `filters`, `hits.when`, and `rescore.boost`, including the clauses nested inside other clauses. Requests holding more are rejected with `search:query:too_many_clauses`. | `1024` |
| `EXOFIND_SEARCH_MAX_CLAUSE_DEPTH` | Maximum nesting depth of clauses. A clause the request carries directly counts as depth one, and the clauses inside it as depth two. Requests nesting deeper are rejected with `search:query:too_deep`. | `20` |
| `EXOFIND_SEARCH_TIMEOUT` | How long one search may collect results for. A search that runs longer is abandoned and answered with `search:timeout`, and the results it collected are dropped. The budget covers collecting matches, not the time spent reading stored fields or building fragments. Set to `0` to let every search run to completion. | `30s` |
| `EXOFIND_SEARCH_THREADS` | Number of threads the node lends to searches, shared across all searches on the node. Accepts `auto` to use the number of processor cores available to the process, or an explicit thread count. Set to `0` to disable the pool and run each search on its request thread alone. When the pool is busy, the request thread runs unstarted search pieces directly. For more information, see [Threads of a single search](../explanation/node-resources.md#threads-of-a-single-search). | `auto` |

## Metrics

A node exposes Prometheus metrics on `/q/metrics` and can push them over OTLP.
For every meter and its tags, see [Metrics](metrics.md). For wiring a
deployment into a backend, see
[Monitor a deployment](../how-to/monitor-a-deployment.md).

The following table lists metrics configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_METRICS_INDEX_ENABLED` | Whether the per-index gauges are registered. Turning them off leaves the node-level meters and `exofind.index.unhealthy` in place, which is what a deployment holding hundreds of indexes does when it does not want a series per index. | `true` |
| `EXOFIND_METRICS_INDEX_INTERVAL` | How often the per-index rows are rebuilt. Measuring what the local copies take walks every index directory, so this is also how often that happens. | `30s` |
| `EXOFIND_METRICS_INDEX_SEARCH_HISTOGRAM` | Whether `exofind.search` carries the index name. Turns one latency histogram into one per index. | `false` |
| `EXOFIND_METRICS_HISTOGRAM_MODE` | What shape the timers publish: `slo` for around a dozen explicit buckets, `detailed` for Micrometer's full bucket set on a backend storing native histograms, or `none` for a count, total and maximum with no buckets. Every mode publishes buckets rather than quantiles, so a collector may sum them across labels. | `slo` |
| `EXOFIND_METRICS_HTTP_MAX_URI_TAGS` | How many distinct `uri` values `http.server.requests` may take before the rest are reported as `UNKNOWN`. | `200` |
| `QUARKUS_MICROMETER_EXPORT_OTLP_PUBLISH` | Whether the node pushes metrics over OTLP. Without an endpoint in `QUARKUS_MICROMETER_EXPORT_OTLP_URL` the registry would push to a collector on localhost, so pushing is something to ask for by name. | `false` |
| `QUARKUS_MICROMETER_EXPORT_OTLP_URL` | Where metrics are pushed when publishing is on. | `http://localhost:4318/v1/metrics` |
| `QUARKUS_MICROMETER_EXPORT_OTLP_STEP` | How often metrics are pushed. | `60s` |
| `QUARKUS_MICROMETER_EXPORT_OTLP_HEADERS` | Headers sent with each push, as `key=value` pairs. This is where a hosted endpoint's credential goes. | None |

## Logging

The node writes logs to standard output. For information on log line formats,
see [Operate a deployment](../how-to/operate-a-deployment.md#read-the-log).

The following table lists logging configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `QUARKUS_LOG_LEVEL` | Minimum log level written to standard output. `INFO` logs index, key, and indexer role events; `DEBUG` adds commit, pull, and push events. | `INFO` |
| `QUARKUS_LOG_CATEGORY__SE_L4_EXOFIND__LEVEL` | Log level for the Exofind engine. Use this variable to adjust engine logging independently of underlying frameworks. | `QUARKUS_LOG_LEVEL` |
| `QUARKUS_HTTP_ACCESS_LOG_ENABLED` | Specifies whether to write an HTTP access log line for each request. | `false` |
| `QUARKUS_LOG_CONSOLE_JSON_ENABLED` | Specifies whether to format console logs as one JSON object per line instead of plain text. | `false` |

To set engine logging to `DEBUG` without enabling verbose output for
third-party libraries (such as the S3 client), set
`QUARKUS_LOG_CATEGORY__SE_L4_EXOFIND__LEVEL`:

```shell
docker run -e QUARKUS_LOG_CATEGORY__SE_L4_EXOFIND__LEVEL=DEBUG exofind/engine
```

`TRACE` logging requires rebuilding the application because log levels below
`DEBUG` are removed at build time.

### JSON output

To output logs in JSON format for log collection systems, set
`QUARKUS_LOG_CONSOLE_JSON_ENABLED` to `true`:

```shell
docker run -e QUARKUS_LOG_CONSOLE_JSON_ENABLED=true exofind/engine
```

Each log line is formatted as a JSON object containing `timestamp`, `level`,
`logger`, `thread`, and `host` fields. Exceptions are formatted as nested
objects to preserve stack traces.

Context key-value pairs are included as JSON fields on the log object. Whole
numbers are encoded as JSON numbers, and all other values are encoded as
strings.

## The JVM

The container image reads these variables at startup. When running
`quarkus-run.jar` directly, pass these options to the `java` command.

The following table lists JVM configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `JAVA_OPTS` | JVM startup arguments. Setting this variable replaces default JVM flags. | See default flags below |
| `JAVA_OPTS_APPEND` | JVM arguments appended to `JAVA_OPTS`. Overrides specific default flags without replacing the entire list. | None |

The container image starts the JVM with the following default flags:

| Flag | What it does |
|------|--------------|
| `-XX:MaxRAMPercentage=50` | Sizes the maximum heap against the container's memory limit, leaving the other half for the page cache the indexes are read through. |
| `--add-modules jdk.incubator.vector` | Gives Lucene the Vector API for vector distance calculations and postings decoding. Without it, Lucene falls back to scalar code and logs `Java vector incubator module is not readable` at startup. With it, the JVM warns that an incubator module is in use. |
| `--enable-native-access=ALL-UNNAMED` | Lets Lucene advise the kernel how it reads index files. Without it, calls succeed with a one-time JVM warning; in future JVM releases they fail. |
| `-XX:+ExitOnOutOfMemoryError` | Ends the process when the heap is exhausted, releasing the file lock on `EXOFIND_STORAGE_LOCAL_DIRECTORY` and stopping indexer lease renewals. |

No garbage collector is specified, so the JVM selects one: G1 on systems with at
least two processors and approximately 2 GB of memory, and the serial collector
on smaller systems. ZGC and Shenandoah are included in the JRE.

`JAVA_OPTS_APPEND` is passed after `JAVA_OPTS`, and the JVM takes the last value
of a flag it is given twice. A module cannot be removed this way: running
without `jdk.incubator.vector` requires replacing `JAVA_OPTS`.

To adjust the maximum heap size on a search-only node, append
`-XX:MaxRAMPercentage`:

```shell
docker run -e JAVA_OPTS_APPEND=-XX:MaxRAMPercentage=25 exofind/engine
```

To select a different collector, append its flags:

```shell
docker run -e JAVA_OPTS_APPEND="-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational" exofind/engine
```

For how to choose these values - what the heap is traded against, and which
collector suits which node - see
[Node memory and JVM configuration](../explanation/node-resources.md).

### Memory maps

Each open index file uses at least one memory mapping. Linux limits the maximum
number of mappings per process with `vm.max_map_count` (65530 on many
distributions). A node serving many indexes across multiple segments can
reach that cap, causing file open operations to fail.

The setting is not namespaced, so it is raised on the host rather than in the
container:

```shell
sysctl -w vm.max_map_count=262144
```
