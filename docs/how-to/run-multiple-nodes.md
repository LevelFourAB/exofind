# Running multiple nodes

This guide shows you how to run multiple nodes against a shared object storage bucket to scale search capacity and provide high availability for writes.

## Prerequisites

Before you begin, ensure that you have:

- An object storage bucket that enforces conditional writes.
- Multiple node processes configured with access to the same bucket.

## 1. Start reader nodes

Reader nodes discover indexes in the bucket, pull them locally, and answer search queries from local copies.

To add search capacity:

1. Start one or more nodes with the same remote storage configuration and leave `INDEXER` unset or set to `false`.
2. (Optional) Set `INDEXES_REFRESH_INTERVAL` to adjust how often the node checks the bucket for updates. The default is `30s`. Lower values provide fresher reads at the cost of more storage traffic. You can also trigger the `pull` action on a node to fetch the latest state of an index immediately.
3. (Optional) Set `INDEXES_MAX_OPEN` to limit how many open indexes a node keeps in memory at once. When the limit is reached, the node closes the least recently used indexes and reopens them when requested.

## 2. Configure and start writer candidates

Writer candidate nodes divide indexes among themselves using a leadership table in the bucket. Each index is held and written by one candidate at a time. If a candidate stops renewing its lease, other candidates take over its indexes.

To configure writer candidates:

1. Select two or three nodes to serve as candidates.
   
   **Note:** Writes to a single index do not spread across multiple nodes. Running more than two or three candidates provides little benefit unless the deployment handles many busy indexes.

2. Set the following environment variables on each candidate node:
   - Set `INDEXER=true`.
   - Set `NODE_ADDRESS` to the address that the node uses to serve writes. Other nodes use this address to forward writes to the candidate that holds an index. The address must be reachable by other nodes, but it does not need to be accessible to clients.
   - (Optional) Set `NODE_ID` to a unique identifier for the node in the leadership table. By default, the node uses its hostname with a random suffix.
   - (Optional) Set `INDEXER_LEASE_DURATION` to specify how long an index lease is held without renewal. The default is `30s`. Nodes renew their leases at one-third of this duration. Failover takes approximately the duration of the lease.

3. Start the candidate nodes.
   
   Each candidate node checks at startup whether the storage backend enforces conditional writes. If conditional writes are unsupported, the node refuses to run as a candidate. For more information, see [Synchronization](../explanation/synchronization.md).

   **Note:** When upgrading to a release that changes candidate coordination, upgrade all candidate nodes at the same time. Candidates on different versions do not corrupt data, but they can contest index leases and cause churn.

## 3. Route client traffic

Clients do not need to know which node holds a specific index.

1. Put reader nodes behind a load balancer.
2. Configure the load balancer health check to point to `/q/health/ready`. A node responds to `/q/health/ready` without an API key after it reads the registry. For more information, see [Ask whether a node is up](operate-a-deployment.md#ask-whether-a-node-is-up).
3. Send write requests to any node. If a node receives a write for an index held by another candidate, it forwards the request to the holder's `NODE_ADDRESS` using the original request credentials and returns the holder's response. If an index does not have a holder, the candidate node that receives the write claims the index immediately.

### Write error codes

If a write request cannot be processed, the node returns one of the following error responses:

- `409 Conflict` with code `indexer:unavailable`: No candidate node is running, or no candidate has set `NODE_ADDRESS`.
- `502 Bad Gateway` with code `indexer:unreachable`: The node that holds the index cannot be reached at its `NODE_ADDRESS`.

## Verifying the deployment

To confirm that candidate nodes are active and writing indexes:

1. Send a `GET` request to any node:
   ```http
   GET /v1alpha1/admin/indexers
   ```
2. Verify that the response lists which node is currently writing each index. For more information, see [Operate a deployment](operate-a-deployment.md#know-which-node-is-writing).

## Related

- [Deploy on Kubernetes](deploy-on-kubernetes.md): Deploy multiple nodes using Kubernetes manifests.
- [Operate a deployment](operate-a-deployment.md): Monitor and manage a running deployment.
- [Synchronization](../explanation/synchronization.md): Learn how storage conditional writes protect data integrity.
