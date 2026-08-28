# Configuration

Exofind is configured through environment variables. Configuration is read
through MicroProfile Config, so each variable is also available as a dotted
property (`REMOTE_STORAGE_URL` corresponds to `remote.storage.url`), which is
how tests and `application.properties` set them.

## Storage

The storage mode specifies where the node keeps indexes, the index registry,
and authentication keys. The mode is explicitly set rather than inferred from
other variables. If storage settings are invalid, the node refuses to start.

The following table lists storage configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_STORAGE_MODE` | Storage mode: `object` to store data in a shared S3-compatible bucket, or `local` to store data on local disk. | `local` |
| `LOCAL_STORAGE_DIRECTORY` | Directory where the node writes data. In `object` mode, it holds local copies of indexes. In `local` mode, it holds the only copy of indexes, the registry, and keys. | Required |

`local` mode is for a single node, such as a local test environment or a
single container. The node does not copy data to remote storage, and additional
nodes cannot join or take over. If the directory is lost, all indexes and keys
are lost. A node started in `local` mode logs this status.
`INDEXES_DISK_MAX_SIZE` does not free disk space in `local` mode. For more
information, see [Disk use](#disk-use).

`INDEXER` defaults to `true` in `local` mode because the single node must
perform writes.

Only one node can run against a directory at a time. The node claims the
directory with file locks for as long as the node runs. A second node pointed
at the same directory refuses to start. Because file locking over NFS and SMB
is unreliable, use locally attached disk storage.

### Object storage

These variables are read in `object` mode and ignored in `local` mode.

The following table lists object storage configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `REMOTE_STORAGE_URL` | URL of the S3-compatible storage. | Required |
| `REMOTE_STORAGE_ACCESS_KEY` | Access key for authentication. | Required |
| `REMOTE_STORAGE_SECRET_KEY` | Secret key for authentication. | Required |
| `REMOTE_STORAGE_REGION` | Region of the object storage. | None |
| `REMOTE_STORAGE_BUCKET` | Bucket where indexes are stored. | Required |
| `REMOTE_STORAGE_PREFIX` | Key prefix within the bucket when sharing a bucket with other services. | None |

## Decompounding data

The following table lists decompounding configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_DECOMPOUND_DIRECTORY` | Directory holding per-locale data to split compound words. For more information, see [Analysis](analysis.md#compound-words). Provide one directory per locale containing `patterns.txt` and `words.txt`, optionally gzipped. If a locale directory is missing, compound words are indexed whole. | `decompound-data` under the working directory |

## Indexer role

The following table lists indexer configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `INDEXER` | Specifies whether this node can write indexes. Multiple indexer candidates divide indexes through a leadership table in object storage. Each index is written by one node at a time. Other nodes take over an index if its holder stops or stalls. | `false` (`true` in `local` mode) |
| `INDEXER_LEASE_DURATION` | Duration an index lease is held before expiring without renewal. Failover takes approximately this duration. Renewal occurs at one third of this duration. | `30s` |
| `INDEXER_REINDEX_MAX_CONCURRENT` | Number of reindex jobs one node runs at once. Accepted jobs past the limit wait in the `pending` phase. | `2` |
| `INDEXER_REINDEX_SWEEP_INTERVAL` | Interval at which an indexer candidate looks for unfinished reindex jobs whose index no node holds, and claims them to resume. | `30s` |
| `INDEXER_REINDEX_CATCHUP_INTERVAL` | Interval at which a `ready` reindex job replays what changed in the source, so a manual promote stays quick. | `30s` |
| `INDEXER_REINDEX_PROMOTE_GRACE` | Delay between a reindex job's promote and its final catch-up sweep, for writes that resolved the index name just before the promote. | `1s` |
| `NODE_ID` | Identifier this node uses in the leadership table. | Hostname with a random suffix |
| `NODE_ADDRESS` | Network address where this node serves write requests. Recorded in the leadership table so other nodes can forward write requests. Must be reachable by other nodes. If not set, write requests to other nodes are rejected instead of forwarded. | None |

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
| `INDEXES_MAX_OPEN` | Maximum number of indexes kept open simultaneously. | Unbounded |
| `INDEXES_REFRESH_INTERVAL` | Interval at which the node checks storage for index and generation changes and pulls updates for open indexes. Also specifies how long promoting a generation takes to reach other nodes. Unseen indexes are looked up immediately, at most once per interval. | `30s` |
| `INDEXES_REFRESH_CONCURRENCY` | Number of indexes refreshed concurrently. | `4` |
| `INDEXES_CLOSE_GRACE_PERIOD` | Grace period that an evicted index waits for in-flight requests before closing. | `10s` |

## Committing

The indexer automatically commits changes to make them searchable. Commits
also push data to remote storage, making changes visible to other nodes after at
most one `INDEXES_REFRESH_INTERVAL`.

To disable automatic commits, set a trigger to `0`. If both triggers are
disabled, an index commits only when requested through the API. For more
information, see [Admin API](admin-api.md). When loading datasets, disable both
triggers or increase `INDEXES_COMMIT_MAX_CHANGES` to commit once at the end.

The following table lists commit configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `INDEXES_COMMIT_MAX_CHANGES` | Maximum number of uncommitted document changes before triggering a commit. | `10000` |
| `INDEXES_COMMIT_MAX_INTERVAL` | Maximum duration uncommitted changes can wait before triggering a commit. | `5s` |

If a commit fails, the node retries with exponential backoff up to one minute
while retaining pending changes. Retries are abandoned in two cases: another
node wrote to storage first (triggering an index pull), or the node lost write
ownership of the index.

## Disk use

Closing an index retains its files on disk. Setting `INDEXES_DISK_MAX_SIZE`
enables a background sweep that deletes local copies of inactive indexes until
disk usage is 10% below the configured limit. Indexes are ranked by access
frequency, with accesses halving in value after each half-life period. Evicted
indexes are re-downloaded from storage when requested.

Local index copies are deleted only if all changes exist in remote storage.
Unpushed commits or definitions are retained and logged as warnings. Because
all copies in `local` mode are unique, `INDEXES_DISK_MAX_SIZE` does not delete
files in `local` mode.

The following table lists disk usage configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `INDEXES_DISK_MAX_SIZE` | Maximum disk space allocated for local index copies, specified in bytes with an optional `K`, `M`, `G`, or `T` binary suffix. | Unbounded |
| `INDEXES_DISK_MIN_IDLE` | Minimum idle time required to retain an index copy regardless of the disk limit. | `24h` |
| `INDEXES_DISK_HALF_LIFE` | Half-life duration after which unopened index access counts are halved. | `168h` |
| `INDEXES_DISK_SWEEP_INTERVAL` | Interval between disk space cleanup checks. | `1h` |

## Document cache

Stored fields are compressed. Reading search results decompresses document hits
on each request. Setting `INDEXES_DOCUMENT_CACHE_MAX_SIZE` enables a shared heap
cache across all indexes on the node.

Cache entries are associated with Lucene index segments. Unmodified segments
retain cache entries across commits, while merged or closed segments discard
their entries.

The document cache is stored on the Java heap. For heap sizing recommendations,
see [Page cache and heap](../explanation/node-resources.md#page-cache-and-heap).

The following table lists document cache configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `INDEXES_DOCUMENT_CACHE_MAX_SIZE` | Maximum memory allocated to cached documents, specified in bytes with an optional `K`, `M`, `G`, or `T` binary suffix. | Off |

## Search

The following table lists search configuration variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `SEARCH_MAX_PAGE_DEPTH` | Maximum result depth allowed for offset-based pagination. Requests exceeding this depth are rejected with `search:page:too_deep`, and page numbers past the limit are not returned. Cursor-based pagination using `next` and `previous` is not capped. | `10000` |

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
| `-XX:+ExitOnOutOfMemoryError` | Ends the process when the heap is exhausted, releasing the file lock on `LOCAL_STORAGE_DIRECTORY` and stopping indexer lease renewals. |

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
