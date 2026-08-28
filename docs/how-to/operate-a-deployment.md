# Operating a deployment

This guide shows you how to operate, monitor, and maintain an active search
deployment. Use this guide to verify node configuration, check health and
index freshness, identify active writers, manage disk capacity, replace or
upgrade nodes, and back up deployment data.

## Prerequisites

Before you begin, ensure that you have:

- A running deployment with one or more nodes. To configure a deployment, see
  [Run more than one node](run-multiple-nodes.md).
- Network access to the HTTP endpoints of each node.

## Check what a node came up as

When a node starts, inspect its startup logs to verify its configuration
before it begins handling requests:

1. Review the initial startup lines in the node logs:
   ```text
   INFO  storage=object auth=keys indexer=true bucket=exofind directory=/var/lib/exofind Starting node, which competes to write indexes
   INFO  node=node-a-7f21 address=http://node-a:8080 Competing for the indexer role
   INFO  exofind 0.1.0 on JVM … started in 1.4s. Listening on: http://0.0.0.0:8080
   ```
2. Verify the configuration settings:
   - Check `storage`, `auth`, `indexer`, and the storage location (`bucket` or
     `directory`).
   - Check the candidate name in `node` (used in write-assignment logs) and the
     reachable `address`.
   - Check write eligibility: A node that cannot write outputs `only answers
     searches`. A node using local storage specifies no bucket and manages all
     indexes uncontested.
3. Check for any `WARN` lines during startup, such as running in `local` mode,
   running with authentication disabled, or providing settings that the active
   mode ignores.
4. When stopping a node, observe that the node closes open indexes and pushes
   modified indexes to remote storage. The shutdown log reports `Closing the
   open indexes`.

## Ask whether a node is up

Check node health by querying the health endpoints. These endpoints do not
require authentication keys:

| Endpoint | Answers |
|---|---|
| `GET /q/health/ready` | Whether to send this node requests |
| `GET /q/health/live` | Whether restarting this node would help |
| `GET /q/health` | Both readiness and liveness together |

Status codes indicate the overall health:
- `200`: The check is UP.
- `503`: The check is DOWN.

Example response:

```json
{
  "status": "UP",
  "checks": [
    { "name": "index-registry", "status": "UP" },
    {
      "name": "index-refresh",
      "status": "UP",
      "data": { "secondsSinceRefresh": 12 }
    }
  ]
}
```

Evaluate the checks in the response body:
- `index-registry` (readiness): A node is ready once it reads the registry
  object (defining existing indexes and generations) from storage. If a node
  remains unready, check connectivity to remote storage. If subsequent
  registry reads fail after startup, the node remains ready and continues
  serving its local copy.
- `index-refresh` (liveness): Reports whether the background refresh loop is
  running. If the loop stops, the node stops receiving updates and requires a
  restart. If a refresh pass fails because the remote bucket is unreachable,
  the check logs a `WARN` and remains UP. The check turns DOWN only when
  `secondsSinceRefresh` exceeds four times `EXOFIND_INDEXES_REFRESH_INTERVAL`
  (with a minimum of 60 seconds).

To check writer assignments, see [Know which node is writing](#know-which-node-is-writing).

## See what a node is serving

To inspect the indexes available on a node and their local status:

1. Retrieve the list of all indexes in the deployment registry:
   ```http
   GET /v1alpha1/admin/indexes
   ```
   Every node returns the same registry list.
2. Query an individual node directly (bypassing any load balancer) for the
   status of a specific index:
   ```http
   GET /v1alpha1/admin/indexes/products
   ```
   Example response body:
   ```json
   "status": {
     "state": "USABLE",
     "readOnly": true,
     "indexer": { "node": "node-a-7f21", "address": "http://node-a:8080" },
     "luceneCompatibility": "CURRENT"
   }
   ```
3. Check the index `state`:
   - Standard operational states: `NEEDS_PULL`, `PULLING`, `USABLE`, and on
     writer nodes `MODIFIED` and `PUSHING`. See
     [Index states](../reference/admin-api.md#index-states).
   - `CLOSED`: The node closed the index to free disk space or slots.
     Requesting the index automatically reopens it.
   - `UNSUPPORTED`: The index definition requires features missing in this node
     version. Upgrade the node.
   - `INCOMPATIBLE`: The Lucene index files are too old for this build. See
     [Survive Lucene upgrades](survive-lucene-upgrades.md).

## Tell whether a node is current

Changes become searchable on a reader node within the commit delay plus one
refresh interval: `EXOFIND_INDEXES_COMMIT_MAX_INTERVAL` (5 seconds by default)
plus `EXOFIND_INDEXES_REFRESH_INTERVAL` (30 seconds by default). A node reporting
`NEEDS_PULL` or `PULLING` is within this refresh window.

To immediately update an index without waiting for the next refresh interval:

1. Trigger an immediate pull action:
   ```http
   POST /v1alpha1/admin/indexes/products/actions/pull
   ```
   This endpoint fetches the latest remote state immediately and returns the
   resulting status. Use this action after bulk loads or promotions.
2. If all nodes require lower latency, reduce
   `EXOFIND_INDEXES_REFRESH_INTERVAL` in
   your configuration. Lower intervals increase remote storage request volume.
3. If a node stays behind, check the logs for storage errors. Errors are keyed
   by `index` and `bucket`.

## Know which node is writing

Each index is written by one node at a time. Different indexes can be written
by different nodes.

To identify index writers:

1. Query the indexers endpoint on any node (including search-only nodes):
   ```http
   GET /v1alpha1/admin/indexers
   ```
   The [claims](../reference/admin-api.md#indexers) response names the writer
   node for each index and lists active candidate nodes. An index without a
   claim has no writer assigned until a write request arrives.
2. Check the index status on an individual node:
   - `status.indexer` names the active writer node.
   - `status.readOnly` is `false` on the writer node and `true` on all other
     nodes.
   These values can lag handovers by a few seconds.
3. Track index writer handovers in candidate node logs:
   ```text
   INFO  node=node-a-7f21 index=products Took over writing the index
   INFO  node=node-a-7f21 index=products Handed over writing the index
   ERROR node=node-a-7f21 index=products Giving up writing the index, <reason>
   ```
   - `Took over writing the index` and `Handed over writing the index` indicate
     routine workload distribution between candidate nodes.
   - `Giving up writing the index` indicates that the node lost storage
     connectivity or claim renewals failed. Set up alerts for this error.

Write requests sent to a non-writer node are automatically forwarded to the
assigned writer.

## Keep the disk in hand

If `EXOFIND_INDEXES_DISK_MAX_SIZE` is unset, a node retains files for every index
it has served. When `EXOFIND_INDEXES_DISK_MAX_SIZE` is configured, the node
sweeps least-used
local copies that are fully uploaded to remote storage.

1. Monitor sweep warnings in the node log:
   ```text
   WARN  Index holds changes the remote never got, keeping its local copy
   WARN  Local copies exceed the disk budget and nothing more can be removed
   ```
   - `Index holds changes the remote never got, keeping its local copy`: A
     commit or definition was not pushed to remote storage. The local copy is
     preserved to prevent data loss. Inspect previous push errors in the log.
   - `Local copies exceed the disk budget and nothing more can be removed`:
     All local index copies are currently in use or unpushed. To resolve this,
     increase disk space, reduce the number of served indexes, or lower
     `EXOFIND_INDEXES_DISK_MIN_IDLE`.
2. Handle request retries: If a search or write conflicts with an index closing
   for space, the node returns HTTP `503`. Retrying the request reopens the
   index. If `503` responses persist, increase `EXOFIND_INDEXES_MAX_OPEN`.

## Replace a node

To replace a node using `object` storage mode:

1. Stop the node cleanly. A clean shutdown releases indexer claims
   immediately, allowing candidate nodes to take over writer roles without
   waiting for `EXOFIND_INDEXER_LEASE_DURATION`.
2. Start a replacement node with the same configuration. The replacement node
   pulls required index files from remote storage on demand. No volume
   migration or backup restoration is required.

If the node uses `local` storage mode, all deployment data resides in the local
directory. See [Run on one node](run-on-one-node.md).

## Upgrade the engine

Nodes of different versions can run against the same remote bucket. To perform
a rolling upgrade:

1. Check for indexes reporting `"luceneCompatibility": "ENDING"`. Reindex these
   indexes before upgrading across major Lucene versions. See
   [Survive Lucene upgrades](survive-lucene-upgrades.md).
2. Upgrade nodes one at a time.
3. Upgrade all nodes before applying index definitions that use new features.
   If an un-upgraded node encounters a new feature in a definition, it marks the
   index `UNSUPPORTED` and stops serving it.

To roll back to an older version, reverse the process. An older build reads data
it created, but reports `UNSUPPORTED` for features created by newer versions.

## Back up

To back up a deployment:

1. If you use `object` storage mode, configure backups on your object storage
   bucket (such as bucket versioning, replication, or prefix snapshots). Scope
   backups to the prefix set in `EXOFIND_STORAGE_REMOTE_PREFIX`. Local node
   disks do not require backups.
2. If you use `local` storage mode, stop the node and back up the directory
   specified in `EXOFIND_STORAGE_LOCAL_DIRECTORY`.

When deleting indexes in object storage mode, note that deleting an index
removes it from the registry and local node disks, but objects remain in the
bucket until deleted directly from remote storage.

## Read the log

Log messages include key-value attributes after the message text (such as
`index`, `generation`, `node`, `bucket`, and `object`). When using
[JSON output](../reference/configuration.md#json-output), these attributes
appear as separate fields.

Monitor and configure alerts for the following log messages:

| Log message | Description |
|---|---|
| `Giving up writing the index` | The node stopped writing an index without handing it over. Remote storage stopped responding or claims expired. |
| `Index holds changes the remote never got` | A push failed and the local copy is now the only copy. |
| `Local copies exceed the disk budget` | The disk budget cannot be met by sweeping local copies. |
| `Index was created with Lucene …` | Compatibility is `ENDING` or `UNREADABLE`. |
| `Authentication is turned off` | Authentication is disabled; all requests are permitted. See [Secure a deployment](secure-a-deployment.md). |
| `Storing everything on this node's disk` | The node started in `local` storage mode instead of `object` storage mode. |

## Confirm the deployment status

After performing maintenance or operational tasks, verify that the deployment is
healthy:

1. Check the health endpoint on each node:
   ```http
   GET /q/health
   ```
   Verify that the response returns HTTP status `200` with `"status": "UP"`.
2. Check the index status on each node:
   ```http
   GET /v1alpha1/admin/indexes
   ```
   Verify that all expected indexes are listed and report a `USABLE` state.

## Related

- [Admin API](../reference/admin-api.md) - Status shapes and index state descriptions.
- [Configuration](../reference/configuration.md) - Configuration settings, intervals, and bounds.
- [Run more than one node](run-multiple-nodes.md) - Candidacy, failover, and write forwarding.
- [Deploy on Kubernetes](deploy-on-kubernetes.md) - Deployment manifests for search and write pools.
- [Architecture](../explanation/architecture.md) - Object storage architecture and node persistence.
