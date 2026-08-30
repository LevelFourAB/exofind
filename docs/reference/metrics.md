# Metrics

The engine registers Micrometer meters to monitor search performance, indexing, synchronization, cluster state, and node resources.

You can scrape these meters through the Prometheus endpoint or push them to an OpenTelemetry (OTLP) collector.

## Meter reference

### Search metrics

| Name | Type | Unit | Tags | Description |
| --- | --- | --- | --- | --- |
| `exofind.search` | Timer | Seconds | `outcome` (`success`, `error`), `index` (optional) | End-to-end duration of a search request. The `index` tag is present only when `EXOFIND_METRICS_INDEX_SEARCH_HISTOGRAM` is `true`. |
| `exofind.search.relaxation` | Counter | Words | `reason` | Count of words dropped by a search before the query matched. Each dropped word increments the counter by one. |

### Write and commit metrics

| Name | Type | Unit | Tags | Description |
| --- | --- | --- | --- | --- |
| `exofind.write` | Timer | Seconds | `operation` (`add`, `update`, `delete`, `delete_by_query`), `outcome` (`success`, `error`) | Duration of a write request on the node that served it. The duration covers reading the request and changing the index, and excludes finding the index. |
| `exofind.write.documents` | Counter | Documents | `operation` (`add`, `update`, `delete`, `delete_by_query`) | Count of documents processed in write requests. A request that failed counts none, including the documents it wrote before the failure. |
| `exofind.write.forwarded` | Counter | Requests | `outcome` (`success`, `unreachable`, `unavailable`, `stale`) | Write requests forwarded to the node holding the index. |
| `exofind.commit` | Timer | Seconds | `trigger` (`changes`, `interval`, `merges`, `explicit`), `outcome` (`success`, `error`) | Duration of a Lucene commit operation. |

The `operation` tag names the request rather than the endpoint, so the JSON and the newline-delimited form of one request share a value:

- `add`: indexing documents, and counting every document the request carried.
- `update`: changing fields of documents, by batch or by key, and counting the documents that changed. A key skipped with `?missing=skip` counts as none.
- `delete`: removing documents by key, and counting the keys the request carried. A key nothing was indexed under counts all the same.
- `delete_by_query`: removing documents by query, and counting the committed documents the query matched.

### Synchronization and storage metrics

| Name | Type | Unit | Tags | Description |
| --- | --- | --- | --- | --- |
| `exofind.sync.push` | Timer | Seconds | `outcome` (`success`, `error`) | Duration of pushing an index to remote storage. |
| `exofind.sync.pull` | Timer | Seconds | `outcome` (`success`, `error`) | Duration of pulling an index from remote storage. |
| `exofind.sync.conflict` | Counter | Conflicts | `operation` (`push`, `pull`) | Synchronization attempts refused because another node wrote to the index. A value above zero indicates concurrent writers. |
| `exofind.storage.operation` | Timer | Seconds | `operation`, `outcome` (`success`, `error`), `status` | Duration of an object storage request. The `operation` tag contains the S3 API call (such as `GetObject` or `PutObject`). The `status` tag contains the HTTP status code (such as `412`). |

### Index catalog and cluster state metrics

| Name | Type | Unit | Tags | Description |
| --- | --- | --- | --- | --- |
| `exofind.indexes.open` | Gauge | Generations | None | Number of index generations currently open on this node. |
| `exofind.indexes.owned` | Gauge | Indexes | None | Number of index names currently written by this node. |
| `exofind.indexes.total` | Gauge | Indexes | None | Total index names across the deployment, as read during the last refresh pass. |
| `exofind.index.state` | Gauge | Generations | `state` (`NEEDS_PULL`, `PULLING`, `USABLE`, `MODIFIED`, `PUSHING`, `UNSUPPORTED`, `INCOMPATIBLE`, `CLOSED`) | Number of open generations in each synchronization state. Emits one series per state across all indexes. |
| `exofind.ownership.change` | Counter | Events | `direction` (`gained`, `lost`, `revoked`) | Changes in index write ownership on this node. |
| `exofind.registry.refresh.age` | Gauge | Seconds | None | Seconds elapsed since the registry refresh loop completed a pass. |

### Per-index metrics

| Name | Type | Unit | Tags | Description |
| --- | --- | --- | --- | --- |
| `exofind.index.unhealthy` | Gauge | Value (`1`) | `index`, `generation`, `state` (`NEEDS_PULL`, `PULLING`, `MODIFIED`, `PUSHING`, `UNSUPPORTED`, `INCOMPATIBLE`, `CLOSED`) | Reports `1` for each open generation that is not in the `USABLE` state. Emits no series when all generations are healthy. |
| `exofind.index.documents` | Gauge | Documents | `index`, `generation` | Number of documents contained in the index generation. Registered only on the writer node. |
| `exofind.index.pending.changes` | Gauge | Changes | `index`, `generation` | Number of uncommitted changes waiting for a Lucene commit. Registered only on the writer node. |
| `exofind.index.pending.age` | Gauge | Seconds | `index`, `generation` | Age in seconds of the oldest uncommitted change. Registered only on the writer node. |
| `exofind.index.disk.bytes` | Gauge | Bytes | `index`, `generation` | Disk space occupied by the index generation in the local index directory. Registered on all nodes holding a copy. |

### Node storage and cache metrics

| Name | Type | Unit | Tags | Description |
| --- | --- | --- | --- | --- |
| `exofind.disk.used.bytes` | Gauge | Bytes | None | Total disk space used by the local index directory. |
| `exofind.disk.max.bytes` | Gauge | Bytes | None | Configured maximum disk capacity for the index directory (`EXOFIND_INDEXES_DISK_MAX_SIZE`). Absent when no limit is configured. |
| `exofind.document.cache.hits` | Counter | Reads | None | Number of document reads served directly from the in-memory cache. |
| `exofind.document.cache.misses` | Counter | Reads | None | Number of document reads that missed the cache and required a disk lookup. |
| `exofind.document.cache.evictions` | Counter | Entries | None | Number of entries evicted from the document cache. |

### API and security metrics

| Name | Type | Unit | Tags | Description |
| --- | --- | --- | --- | --- |
| `exofind.api.error` | Counter | Requests | `code` | API requests that resulted in an error response, tagged with the API error code. |
| `exofind.auth.failure` | Counter | Requests | `reason` (`unauthenticated`, `forbidden`, `not_covered`) | API requests refused during authentication or authorization. |

### Reindexing metrics

| Name | Type | Unit | Tags | Description |
| --- | --- | --- | --- | --- |
| `exofind.reindex.active` | Gauge | Jobs | `phase` (`PENDING`, `COPYING`, `REPLAYING`, `READY`, `PROMOTING`, `DONE`, `FAILED`, `CANCELLED`) | Number of reindexing jobs known to this node in each phase. Emits one series per phase across all indexes. |

## Index tagging and registration scope

To limit cardinality growth in large deployments, meters differ in whether they include index names and on which nodes they register:

| Meter | Carries `index` tag | Registration scope |
| --- | --- | --- |
| `exofind.search` | Optional (off by default) | All nodes serving search requests |
| `exofind.index.unhealthy` | Yes | Nodes with generations not in the `USABLE` state |
| `exofind.index.documents` | Yes | Node currently writing the index |
| `exofind.index.pending.changes` | Yes | Node currently writing the index |
| `exofind.index.pending.age` | Yes | Node currently writing the index |
| `exofind.index.disk.bytes` | Yes | Every node holding a local copy |
| All other meters | No | Node-level |

## Registries and OTLP export

The engine includes two Micrometer registries on the classpath:

- **Prometheus**: Exposed at `/q/metrics` on the node HTTP port. Enabled by default. Scraping this endpoint consumes resources only when requested.
- **OTLP push**: Compiled in but disabled at runtime by default.

### Enabling OTLP push

OTLP push is built into the node binary (`quarkus.micrometer.export.otlp.enabled=true`). You configure export at runtime using environment variables:

| Variable | Default | Description |
| --- | --- | --- |
| `QUARKUS_MICROMETER_EXPORT_OTLP_PUBLISH` | `false` | Set to `true` to enable pushing metrics to an OTLP collector. |
| `QUARKUS_MICROMETER_EXPORT_OTLP_URL` | `http://localhost:4318/v1/metrics` | Target OTLP endpoint URL. For example, `https://<collector>/v1/metrics`. |
| `QUARKUS_MICROMETER_EXPORT_OTLP_STEP` | `60s` | Push interval frequency. |
| `QUARKUS_MICROMETER_EXPORT_OTLP_HEADERS` | None | Comma-separated list of `key=value` headers, such as authentication tokens for hosted gateways. |

## Histogram modes

The engine publishes cumulative histogram buckets rather than precomputed quantiles. Buckets from multiple nodes or label dimensions can be summed with `sum by (le)` in Prometheus, whereas quantiles cannot be aggregated across series.

The `EXOFIND_METRICS_HISTOGRAM_MODE` setting supports three modes:

- `slo` (default): Publishes fixed bucket boundaries tailored for service level objectives.
- `detailed`: Publishes Micrometer default percentiles histogram bucket set (tens of buckets per timer). Suitable for backends supporting native histograms.
- `none`: Publishes only count, total time, and maximum value. Emits no histogram buckets.

### Bucket boundaries in `slo` mode

Request timers (`exofind.search`, `exofind.write`):

- 1 ms
- 5 ms
- 10 ms
- 25 ms
- 50 ms
- 100 ms
- 250 ms
- 500 ms
- 1 s
- 5 s
- 10 s

Synchronization and storage timers (`exofind.commit`, `exofind.sync.push`, `exofind.sync.pull`, `exofind.storage.operation`):

- 10 ms
- 50 ms
- 250 ms
- 1 s
- 5 s
- 15 s
- 60 s
- 5 min

## Engine metrics configuration

Configure metrics behavior using the following environment variables:

| Setting | Default | Description |
| --- | --- | --- |
| `EXOFIND_METRICS_INDEX_ENABLED` | `true` | Enables per-index gauges (`exofind.index.*`). When `false`, only node-level meters and `exofind.index.unhealthy` remain active. |
| `EXOFIND_METRICS_INDEX_INTERVAL` | `30s` | Interval for rebuilding per-index metrics and scanning disk usage. |
| `EXOFIND_METRICS_INDEX_SEARCH_HISTOGRAM` | `false` | Adds the `index` tag to `exofind.search`, splitting the latency histogram per index. |
| `EXOFIND_METRICS_HISTOGRAM_MODE` | `slo` | Histogram bucket generation mode: `slo`, `detailed`, or `none`. |
| `EXOFIND_METRICS_HTTP_MAX_URI_TAGS` | `200` | Maximum number of distinct URI values allowed for `http.server.requests` before collapsing additional paths to `UNKNOWN`. |
