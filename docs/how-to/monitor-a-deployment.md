# Monitoring a deployment

This guide shows you how to export metrics from a running deployment to a
monitoring backend such as Grafana Cloud or Amazon Managed Service for
Prometheus. Use this guide to inspect exposed meters, select collection methods,
control label cardinality, configure histogram modes, and define alerts.

On a backend that bills per active series, the number of indexes decides the
bill. Dropping or collapsing index labels at your collector controls this cost.
For a complete catalog of available meters and tags, see the
[Metrics reference](../reference/metrics.md).

## Prerequisites

Before you begin, ensure that you have:

- A running deployment with one or more nodes.
- Network access to the HTTP port of each node.
- A metrics backend or collector endpoint ready to receive data.

## Inspect exposed node metrics

Exofind exposes Prometheus metrics by default on the standard HTTP port at
`/q/metrics`.

1. Query the metrics endpoint on a running node:
   ```bash
   curl http://localhost:8080/q/metrics
   ```
2. Verify that the node returns Prometheus exposition text containing JVM,
   HTTP, and Exofind engine meters.

Prometheus exposition runs continuously and consumes no extra resources until
scraped.

## Choose a histogram mode

Exofind publishes histogram buckets rather than precomputed quantiles. Buckets
from multiple series or nodes can be summed directly, whereas quantiles cannot
be aggregated.

Set `EXOFIND_METRICS_HISTOGRAM_MODE` in your node environment depending on your
backend capabilities:

| Mode | Boundaries and series volume | Recommended use |
| --- | --- | --- |
| `slo` (default) | 11 request boundaries (1ms to 10s), 8 sync boundaries (10ms to 5m). Around 12 series per timer. | Standard Prometheus backends. |
| `detailed` | Full Micrometer bucket set, dozens of buckets per timer. | Backends with native histogram support. |
| `none` | Count, total time, and maximum value only. No percentiles can be read. | Environments requiring minimal series count. |

To track search latency per index, set
`EXOFIND_METRICS_INDEX_SEARCH_HISTOGRAM=true`. This turns one search histogram
into one per index, so a deployment holding 500 indexes multiplies that meter by
500. Two things make it affordable: a backend that stores native histograms, or
a collector that collapses the index label, as described in "Control index
label cardinality". Without
either, leave it `false`.

## Configure metrics collection

Choose either pull collection with Grafana Alloy or push collection with
OpenTelemetry Protocol (OTLP).

### Option A: Scrape with Grafana Alloy and forward to Grafana Cloud

Configure Grafana Alloy to discover your Exofind pods, scrape `/q/metrics`, and
forward data using `prometheus.remote_write`.

1. Define pod discovery and scrape targets in your Alloy configuration file:
   ```alloy
   discovery.kubernetes "exofind" {
     role = "pod"
   }

   discovery.relabel "exofind" {
     targets = discovery.kubernetes.exofind.targets

     rule {
       source_labels = ["__meta_kubernetes_pod_label_app"]
       regex         = "exofind"
       action        = "keep"
     }

     rule {
       target_label = "__metrics_path__"
       replacement  = "/q/metrics"
     }
   }

   prometheus.scrape "exofind" {
     targets         = discovery.relabel.exofind.output
     forward_to      = [prometheus.relabel.exofind.receiver]
     scrape_interval = "30s"
   }
   ```
2. Connect `prometheus.relabel.exofind.receiver` to your
   `prometheus.remote_write` component.

### Option B: Push with OTLP directly from nodes

To push metrics directly to an OTLP-compatible collector or Grafana Cloud OTLP
gateway without a scraping agent, configure the OTLP exporter environment
variables on each node:

1. Enable OTLP metric publishing:
   ```bash
   export QUARKUS_MICROMETER_EXPORT_OTLP_PUBLISH=true
   ```
2. Set the destination URL:
   ```bash
   export QUARKUS_MICROMETER_EXPORT_OTLP_URL=https://<collector-endpoint>/v1/metrics
   ```
   If unset, the exporter defaults to `http://localhost:4318/v1/metrics`.
3. Set the export interval:
   ```bash
   export QUARKUS_MICROMETER_EXPORT_OTLP_STEP=60s
   ```
4. If your endpoint requires authentication, provide the credentials as
   key-value headers:
   ```bash
   export QUARKUS_MICROMETER_EXPORT_OTLP_HEADERS="Authorization=Bearer <token>"
   ```

## Control index label cardinality

A deployment with 500 indexes produces roughly 2,500 per-index gauge series per
node, compared to approximately 250 node-level timer series and 400 JVM/HTTP
series.

To control billing on backends that charge per active series, apply relabelling
rules in your collector before forwarding.

### Keep specific index labels and collapse the rest

Prometheus relabelling uses RE2 regular expressions, which do not support
negative lookaheads. To retain the `index` label on important indexes while
collapsing all other indexes to a single value:

1. Add a `prometheus.relabel` block to mark allowed indexes, rewrite unmarked
   indexes, and remove the temporary label:
   ```alloy
   prometheus.relabel "exofind" {
     forward_to = [prometheus.remote_write.grafana.receiver]

     rule {
       source_labels = ["index"]
       regex         = "site-alpha|site-beta|site-gamma"
       target_label  = "__keep_index"
       replacement   = "yes"
     }

     rule {
       source_labels = ["__keep_index"]
       regex         = "^$"
       target_label  = "index"
       replacement   = "other"
     }

     rule {
       action = "labeldrop"
       regex  = "__keep_index"
     }
   }
   ```

### Drop unused meters

To drop high-cardinality meters entirely:

1. Add a drop rule matching the metric name:
   ```alloy
     rule {
       source_labels = ["__name__"]
       regex         = "exofind_index_disk_bytes"
       action        = "drop"
     }
   ```

### Adjust node-level index emission

If you do not need per-index metrics on your backend, you can disable them at
the engine level:

1. Set `EXOFIND_METRICS_INDEX_ENABLED=false` in the node environment.
   This disables per-index gauges while keeping node-level meters and
   `exofind.index.unhealthy` active.
2. Adjust `EXOFIND_METRICS_INDEX_INTERVAL` (default `30s`) to control how
   frequently per-index metrics and disk checks update.

## Set up alerts

Configure alerts in your monitoring backend for these core operational signals:

| Alert condition | Signal | What it indicates |
| --- | --- | --- |
| `exofind_sync_conflict_total > 0` | Sync conflict | Two nodes wrote the same index at the same time. See [Synchronization](../explanation/synchronization.md). |
| `exofind_registry_refresh_age_seconds` growing without bound | Registry refresh stalled | The background refresh loop has stopped. |
| `exofind_index_unhealthy` > 0 | Unhealthy index generation | An index generation has remained in a non-`USABLE` state longer than an expected pull duration. |
| `exofind_index_pending_age_seconds` approaching `EXOFIND_INDEXES_COMMIT_MAX_INTERVAL` | Pending changes age | Writes are not committing within the configured interval; search readers are becoming stale. |
| `exofind_disk_used_bytes` approaching `exofind_disk_max_bytes` | Disk budget | Disk usage is nearing the local storage budget limit. |
| `exofind_ownership_change_total` rising steadily | Churning index writers | Writer assignments are rapidly moving between nodes instead of stabilizing. |

## Confirm the result

Verify that metrics are arriving in your backend:

1. Open your monitoring backend query interface (such as Grafana Explore).
2. Execute a query for the node refresh age:
   ```promql
   exofind_registry_refresh_age_seconds
   ```
3. Verify that the query returns values for all active nodes.
4. Execute a query to check that relabelling rules collapsed index names
   correctly:
   ```promql
   count by (index) (exofind_index_documents)
   ```
   Verify that only allowlisted index names and the `other` label appear.

## Related

- [Metrics reference](../reference/metrics.md) - Complete meter catalog, types,
  and tag values.
- [Operating a deployment](operate-a-deployment.md) - Health checks and node
  inspection.
- [Configuration](../reference/configuration.md) - Full list of engine and
  metrics environment variables.
- [Synchronization](../explanation/synchronization.md) - Explanation of sync
  conflicts and index leases.
