# Separating search and indexing nodes

This document explains why production deployments run search nodes and indexer candidate nodes in separate pools, and what resource limits a deployment encounters as the number of indexes grows.

For the underlying storage model, see [Architecture](architecture.md). For the consistency mechanisms that coordinate writes across storage, see [Synchronization](synchronization.md). For variable settings, see the [Configuration reference](../reference/configuration.md).

## Candidacy rather than role assignment

Setting `EXOFIND_INDEXER_ENABLED=true` does not assign a fixed set of indexes to a node. Instead, it marks the node as a candidate to write indexes.

Candidate nodes coordinate through a shared leadership table in object storage. They divide existing index names among themselves so that each index has at most one writer at any time. When a candidate joins or leaves, the remaining candidates balance the index claims. If a candidate crashes or stops renewing its lease, its claims lapse and other candidates acquire them.

Search nodes run with `EXOFIND_INDEXER_ENABLED=false`. They never claim indexes or run Lucene writers. Instead, they discover indexes through the shared registry, pull committed files from storage, and serve search requests locally.

## Why one pool cannot configure both roles

A single pool of nodes can run both search and indexing if you enable `EXOFIND_INDEXER_ENABLED=true` on some or all instances. However, searching and indexing place opposing demands on memory, scaling, disk management, and lifecycle handling. A single pool configuration compromises both workloads.

### Memory split: heap against page cache

Search nodes and indexer candidates use memory differently:

- **Search nodes** read index files through memory-mapped files. The operating system kernel manages these mappings in the page cache. Search nodes perform best with a small JVM heap (such as 25% of pod memory) and a large page cache. A large heap deprives the kernel of memory for cached index files, forcing searches to read from disk.
- **Indexer candidates** hold uncommitted document buffers in memory and run background segment merges. These operations allocate memory directly inside the JVM heap. Indexer nodes require a large heap (often 50% or more of pod memory) to prevent out-of-memory errors during heavy write and merge activity.

Because container platforms configure JVM heap flags uniformly across a pool, a shared pool either starves indexer buffers or reduces the page cache available for search.

### Scaling signals and autoscaling

Search capacity scales horizontally with query volume. Adding search nodes increases aggregate query throughput and distributes read traffic. Search pools work well with horizontal autoscalers driven by CPU or request metrics.

Indexing capacity does not scale with search traffic. Indexer candidates run as a small, stable set of nodes (typically two or three). An autoscaler that reduces pool size when CPU usage drops terminates pods holding active write leases. When an indexer pod terminates unexpectedly:

1. Pending unpushed writes remain uncommitted on that node.
2. The index remains without an active writer until `EXOFIND_INDEXER_LEASE_DURATION` expires.
3. Another candidate must take over the claim, pull the latest manifest, and reopen the Lucene writer before accepting new writes.

Separating pools allows you to autoscale search nodes without triggering indexer failovers. A search node never registers as a candidate in the leadership table, so adding or removing one leaves every claim where it is.

### Disk rules and cleanup

The disk on a search node is an ephemeral read cache. If local disk space runs low, the background disk sweeper configured by `EXOFIND_INDEXES_DISK_MAX_SIZE` safely deletes local copies of inactive indexes that exist in object storage.

The disk on an indexer candidate holds the local copy of every index the node writes, the commits that have not reached object storage yet, and the working files of running segment merges. The disk sweep skips every index that is open, and an indexer keeps the indexes it writes open so the sweep never reaches their directories. The sweep also refuses to remove a directory that holds changes object storage does not have, and never removes a copy used more recently than `EXOFIND_INDEXES_DISK_MIN_IDLE` (default: `24h`), so it frees only the copies of indexes the node no longer writes.

Leave `EXOFIND_INDEXES_DISK_MAX_SIZE` unset on an indexer candidate. Give the node its own persistent volume and size it for the indexes it writes, with headroom for merges and for commits waiting to be pushed. A node holds a file lock on its storage directory for the lifetime of its process, so two nodes cannot share one volume.

### Rollout order and shutdown grace periods

When rolling out updates, the two workloads require different shutdown behavior:

- **Search nodes** hold no uncommitted state. They can be replaced in parallel or with aggressive surge rollouts. Their shutdown grace period only needs to cover in-flight search queries.
- **Indexer candidates** must flush open writers, commit pending document batches, and push manifests to object storage before terminating. If a candidate finishes pushing before shutdown, it releases its claims so successors take over immediately. If it is killed early, its claims must lapse in storage. Indexers require sequential rollouts and long termination grace periods.

## Request routing and the cost of write forwarding

A client may send any request to any node. Reads are answered by the node that receives them, and only write endpoints that declare the index writer as their server are forwarded. A node that receives such a write for an index it does not hold reads the leadership table, finds the candidate holding the index, and proxies the request to that candidate's `EXOFIND_NODE_ADDRESS`. It keeps the method, path, query, body, and the caller's own credentials, and takes only the scheme, host, and port from the address. Any node forwards this way, including an indexer candidate that holds other indexes, and reuses its last read of the leadership table for a few seconds so the answer can lag a failover by that much.

When no candidate holds the index, the forwarding node picks a live candidate by hashing the index name. Every node picks the same candidate for the same index, so the first writes to a new index do not scatter across the pool. That candidate claims the index by serving the write. A node that is a candidate itself claims the index instead of forwarding. When no candidate is running, or no candidate set `EXOFIND_NODE_ADDRESS`, the write is answered with `409 Conflict` and the code `indexer:unavailable`. A holder that cannot be reached gives `502 Bad Gateway` and the code `indexer:unreachable`.

The forwarding proxy streams the request body directly using HTTP/1.1 and adds an `X-Exofind-Forwarded` header. If the target candidate no longer holds the index, it rejects the forwarded request with `409 Conflict` rather than forwarding it again, preventing proxy loops while the leadership table updates.

Forwarding works transparently, but introduces an extra network hop across the internal network. For bulk loads and high-throughput ingestion pipelines, sending writes through search nodes consumes search pod CPU and network bandwidth. Routing write traffic directly to an indexer service bypasses the forwarding hop.

## Scaling limits with many indexes

As a deployment grows to hundreds of indexes, resource contention appears on indexer candidates before search nodes. A growing deployment reaches three primary limits:

### Open Lucene writers

Every open index on an indexer candidate maintains an active Lucene writer with dedicated document buffers and merge threads.

The `EXOFIND_INDEXES_MAX_OPEN` variable caps how many indexes a node keeps open simultaneously. Setting this value too high exhausts JVM heap space and causes out-of-memory crashes. Setting it too low causes thrashing, where the node repeatedly closes and reopens writers as writes arrive for different indexes.

### Commit request volume against refresh polls

Each active index commits and pushes updates to object storage based on `EXOFIND_INDEXES_COMMIT_MAX_INTERVAL` (defaulting to 5 seconds). With hundreds of active indexes, frequent commits produce a high volume of upload requests and conditional manifest writes against the storage backend.

Search nodes pull an index at most once per `EXOFIND_INDEXES_REFRESH_INTERVAL` (defaulting to 30 seconds). Committing to storage more frequently than that generates storage API operations without making changes visible to searchers any sooner. Aligning `EXOFIND_INDEXES_COMMIT_MAX_INTERVAL` with `EXOFIND_INDEXES_REFRESH_INTERVAL` reduces commit overhead.

### Write throughput per index

Adding indexer candidates distributes multiple indexes across more nodes. However, all writes for an individual index route to the single candidate holding its claim.

Write throughput for a single index cannot be split across multiple candidate nodes. If write ingestion for one index exceeds what a single node can process, adding candidate nodes to the deployment does not increase throughput for that index.

## Related

- [Node memory and JVM configuration](node-resources.md) — Why the heap is sized against the page cache, and how the two roles differ.
- [Architecture](architecture.md) — The storage model, and how a request reaches the node that answers it.
- [Synchronization](synchronization.md) — Manifests, epochs, and the leadership table.
- [Deploy on Kubernetes](../how-to/deploy-on-kubernetes.md) — The manifests that put this into practice.
- [Configuration](../reference/configuration.md) — The variables named here.
