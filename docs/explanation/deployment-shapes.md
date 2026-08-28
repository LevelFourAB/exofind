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

Separating pools allows you to autoscale search nodes without triggering indexer failovers.

### Disk rules and cleanup

The disk on a search node is an ephemeral read cache. If local disk space runs low, the background disk sweeper configured by `EXOFIND_INDEXES_DISK_MAX_SIZE` safely deletes local copies of inactive indexes that exist in object storage.

The disk on an indexer candidate holds unpushed commits and active Lucene segment merges. The disk sweeper cannot free uncommitted files. Running disk cleanup on an active writer risks deleting files required by ongoing merge operations. Indexer nodes require dedicated persistent storage sized for write buffers rather than cache budgets.

### Rollout order and shutdown grace periods

When rolling out updates, the two workloads require different shutdown behavior:

- **Search nodes** hold no uncommitted state. They can be replaced in parallel or with aggressive surge rollouts. Their shutdown grace period only needs to cover in-flight search queries.
- **Indexer candidates** must flush open writers, commit pending document batches, and push manifests to object storage before terminating. If a candidate finishes pushing before shutdown, it releases its claims so successors take over immediately. If it is killed early, its claims must lapse in storage. Indexers require sequential rollouts and long termination grace periods.

## Request routing and the cost of write forwarding

Exofind allows clients to send any request to any node. A search node that receives a write request checks the leadership table for the candidate holding that index and proxies the request to the candidate's `EXOFIND_NODE_ADDRESS`. If no candidate holds the index, the first candidate to receive the forwarded write claims the index.

The forwarding proxy streams the request body directly using HTTP/1.1 and adds an `X-Exofind-Forwarded` header. If the target candidate no longer holds the index, it rejects the forwarded request with `409 Conflict` rather than forwarding it again, preventing proxy loops while the leadership table updates.

Forwarding works transparently, but introduces an extra network hop across the internal network. For bulk loads and high-throughput ingestion pipelines, sending writes through search nodes consumes search pod CPU and network bandwidth. Routing write traffic directly to an indexer service bypasses the forwarding hop.

## Scaling limits with many indexes

As a deployment grows to hundreds of indexes, resource contention appears on indexer candidates before search nodes. A growing deployment reaches three primary limits:

### Open Lucene writers

Every open index on an indexer candidate maintains an active Lucene writer with dedicated document buffers and merge threads.

The `EXOFIND_INDEXES_MAX_OPEN` variable caps how many indexes a node keeps open simultaneously. Setting this value too high exhausts JVM heap space and causes out-of-memory crashes. Setting it too low causes thrashing, where the node repeatedly closes and reopens writers as writes arrive for different indexes.

### Commit request volume against refresh polls

Each active index commits and pushes updates to object storage based on `EXOFIND_INDEXES_COMMIT_MAX_INTERVAL` (defaulting to 5 seconds). With hundreds of active indexes, frequent commits produce a high volume of upload requests and conditional manifest writes against the storage backend.

Search nodes poll storage for changes based on `EXOFIND_INDEXES_REFRESH_INTERVAL` (defaulting to 30 seconds). Committing to storage more frequently than the search refresh interval generates storage API operations without making changes visible to searchers any sooner. Aligning `EXOFIND_INDEXES_COMMIT_MAX_INTERVAL` with `EXOFIND_INDEXES_REFRESH_INTERVAL` reduces commit overhead.

### Write throughput per index

Adding indexer candidates distributes multiple indexes across more nodes. However, all writes for an individual index route to the single candidate holding its claim.

Write throughput for a single index cannot be split across multiple candidate nodes. If write ingestion for one index exceeds what a single node can process, adding candidate nodes to the deployment does not increase throughput for that index.

## Related

- [Node memory and JVM configuration](node-resources.md) — Why the heap is sized against the page cache, and how the two roles differ.
- [Architecture](architecture.md) — The storage model, and how a request reaches the node that answers it.
- [Synchronization](synchronization.md) — Manifests, epochs, and the leadership table.
- [Deploy on Kubernetes](../how-to/deploy-on-kubernetes.md) — The manifests that put this into practice.
- [Configuration](../reference/configuration.md) — The variables named here.
