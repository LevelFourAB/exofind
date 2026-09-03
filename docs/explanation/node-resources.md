# Node memory and JVM configuration

This document explains how an Exofind node uses system memory and why its Java Virtual Machine (JVM) is configured with specific startup flags. For an overview of how nodes fit into the broader system, see [Architecture](architecture.md). For variable definitions, see the [Configuration reference](../reference/configuration.md).

## Page cache and heap

A node spends memory in two distinct places: the Java heap and the operating system page cache.

Lucene accesses index files on disk through memory mapping (`mmap`). The kernel manages these mapped files by caching file pages in operating system memory outside the Java heap. When a query searches an index, Lucene reads directly from these cached memory pages. If a page is not present in the page cache, the kernel must fetch it from physical disk, introducing I/O latency.

Because container and host memory limits encompass both the JVM process and the operating system page cache, every byte allocated to the JVM heap is a byte unavailable for caching index data. Sizing node memory is a balance between giving the JVM enough heap for runtime objects and leaving enough free memory for the kernel to hold index files in cache.

## Heap sizing trade-offs

By default, the Exofind container image configures `-XX:MaxRAMPercentage=50`. This allocates 50% of the container memory limit to the JVM heap and leaves the remaining 50% for the operating system page cache.

```
+-----------------------------------+-----------------------------------+
|         Java Heap (50%)           |         Page Cache (50%)          |
| In-flight requests, write buffers | Memory-mapped Lucene index files  |
+-----------------------------------+-----------------------------------+
|<----------------------- Total Container Memory ---------------------->|
```

The optimal balance between heap and page cache depends on node workload and total instance size.

### Search nodes compared to indexer nodes

Searching and indexing place different demands on memory:

- **Search nodes** require minimal heap. Heap memory on a search node holds in-flight query states, decompressed document fields, and the facet columns: the values of each faceted field that a search has counted, laid out flat per segment so that counting reads an array instead of decoding doc values. A column costs about 4 bytes per string value and 8 bytes per numeric value, for every open segment. A search that matches a fifth of a segment or more also builds the inverted form of each column it counts, which costs about as much again. A facet counted over the whole index keeps what each segment counted, about 4 bytes per distinct value of the field per segment, and every reader keeps the answers of its most recently asked facet scopes. The actual index structures reside in the page cache. Search nodes perform best with a smaller heap allocation (for example, 25% of memory), leaving 75% of available memory to cache index files.
- **Indexer nodes** require substantial heap. An indexer holds uncommitted document batches in memory and runs segment merge operations that allocate temporary working buffers. Reducing the heap too far on an indexer causes frequent garbage collection or out-of-memory errors during heavy write loads.

### The 32 GB compressed object pointer boundary

On 64-bit JVMs, the runtime uses Compressed Object Pointers (Compressed OOPs) to represent object references as 32-bit pointers rather than 64-bit pointers. This optimization significantly reduces memory footprint and cache pressure.

When heap size exceeds approximately 32 GB, the JVM can no longer address all heap memory with 32-bit references and disables compressed pointers. Every object reference then occupies 8 bytes instead of 4, so the same heap size holds fewer objects. How much fewer depends on how many references the objects contain.

The result is that a heap set just above the boundary can hold less than a heap set just below it. Keep the maximum heap at 31 GB or less, and give any memory beyond what that heap needs to the operating system page cache instead. To find the boundary for a specific JVM, run `java -Xmx<size> -XX:+PrintFlagsFinal -version` and read the value of `UseCompressedOops`.

## Default JVM options

The Exofind container image configures `JAVA_OPTS` with four startup flags:

```text
-XX:MaxRAMPercentage=50 --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -XX:+ExitOnOutOfMemoryError
```

### Flag purposes and fallback behavior

Each flag controls a specific JVM runtime behavior:

- `-XX:MaxRAMPercentage=50`: Sizes the maximum heap relative to the container memory limit rather than the host's physical RAM. Without this flag, the JVM uses default ergonomics that might size the heap according to total host memory, depriving the kernel of memory needed for page cache.
- `--add-modules jdk.incubator.vector`: Exposes the Java Vector API to Lucene. Lucene uses SIMD vector instructions for calculating vector distances and decoding postings lists. Without this module, Lucene falls back to scalar calculations, reducing search and vector throughput, and logs a warning at startup.
- `--enable-native-access=ALL-UNNAMED`: Permits Lucene to invoke native operating system calls such as `madvise`. Lucene uses these calls to advise the kernel on access patterns, such as requesting sequential read-ahead during merges while avoiding read-ahead during random-access searches. Without this flag, current JVM versions log a warning on startup, and future JVM versions will refuse native calls.
- `-XX:+ExitOnOutOfMemoryError`: Directs the JVM to terminate the process immediately when an `OutOfMemoryError` occurs.

### Modifying startup flags

The container startup script appends `JAVA_OPTS_APPEND` to `JAVA_OPTS`. When a JVM flag that accepts a value is passed multiple times, the JVM uses the last value provided. You can override heap sizing by passing `-XX:MaxRAMPercentage=<value>` in `JAVA_OPTS_APPEND`.

However, the Java module system does not support removing modules. A module enabled via `--add-modules` cannot be disabled by appending a flag. If you must run without the incubator vector module, replace `JAVA_OPTS` entirely instead of using `JAVA_OPTS_APPEND`.

## Garbage collection and cache headroom

The Exofind image does not set a garbage collector flag, leaving the JVM to choose its default collector. On systems with at least two CPU cores and at least 2 GB of RAM, the JVM selects the Garbage-First (G1) collector. On smaller single-core systems, it selects the Serial collector.

Low-latency garbage collectors like ZGC and Shenandoah reduce pause times by performing collection concurrently with application threads. However, concurrent collectors introduce trade-offs:

- **Lower throughput:** Concurrent collection consumes additional CPU cycles during normal request processing.
- **Allocation headroom:** Concurrent collectors require larger heap headroom to accommodate allocations while a collection cycle runs. This extra heap allocation directly reduces memory available for the page cache.
- **Reference size (ZGC):** ZGC does not use compressed object pointers, which increases memory overhead for all objects in the heap.

For most deployments, G1 provides the best balance of throughput, low memory overhead, and predictable page cache availability.

## Heap exhaustion and failover safety

When a node exhausts its heap, remaining alive in an unstable state causes cluster-wide coordination failures:

1. **Storage directory locks:** An unresponsive node continues to hold operating system file locks on its `EXOFIND_STORAGE_LOCAL_DIRECTORY` (`/data`), preventing replacement processes from opening index files.
2. **Lease renewals:** An indexer node that encounters heap starvation can continue executing background timer threads, renewing its leadership leases in object storage while failing to process incoming writes. Other candidate nodes cannot claim the indexes until the lease expires.

Setting `-XX:+ExitOnOutOfMemoryError` ensures that a failing node terminates immediately. Process termination releases local file locks and stops lease renewal heartbeats, allowing standby candidate nodes to take over indexing immediately.

## Cost of a single search

Heap sizing decides how much memory a node has. How much of it one request may spend is a separate question. A search request describes its own work: how many results to rank, how far into a vector index to read, and how many clauses to evaluate. A caller asking for a page of a million hits, or a query of a hundred thousand clauses, exhausts the heap of a node that was sized correctly.

The caps in the [search configuration](../reference/configuration.md#search) bound each of those numbers. A node answers whoever reaches it, and the [Trust model](trust-model.md) allows browsers to search a public node directly. A rate limit in front of the node counts requests, and one request can cost a thousand times more than another.

Two kinds of cap do the work:

- **Caps on the request** bound what a caller may ask for before the search runs: the size of a page, the depth of a `knn` or `fuse` clause, and the number and nesting of clauses. A request over any of them is rejected with a `400` naming the setting it exceeded, and the node does no search work.
- **The time budget** bounds what a search costs once it runs. `EXOFIND_SEARCH_TIMEOUT` stops collection that runs longer, and the node answers `search:timeout` with a `503`.

The results collected before the budget ran out are dropped. A partial page carries no mark of being partial. Its totals, facet counts, and cursors describe an index the node did not finish reading, and a caller cannot tell that page from a complete one.

The two kinds are not interchangeable. Request caps are predictable, so a client can be written against them, but they bound a description of the work instead of the work itself. A two-clause query over a large index costs more than a twenty-clause query over a small one. The time budget bounds the work itself, and a caller cannot tell in advance which searches it stops.

## Threads of a single search

A search arrives on a single request thread, but the node can split its execution into independent pieces:

- Lucene ranks the index across slices of segments.
- The node collects each index segment separately.
- The node counts each facet separately.

The `EXOFIND_SEARCH_THREADS` setting controls how many worker threads the node lends to these pieces across all active searches.

Distributing a search across multiple threads trades throughput for latency. When your node serves many concurrent small requests, allocating cores across distinct requests yields higher overall throughput. When your node serves fewer large requests, allocating cores across the pieces of each request reduces query latency.

The search thread pool adapts to both workloads:

- **Fallback to the request thread:** The request thread submits every piece to the pool and immediately executes any piece that the pool has not yet claimed. If worker threads are idle, the pieces run concurrently and the query finishes sooner. If all pool threads are busy, the request thread runs every remaining piece in sequence. On a saturated node, the only overhead of the pool is the task handover.
- **Small searches stay on the request thread:** Waking a pool thread costs about as much as counting a few thousand matches. A search with less work than that to split, such as a query matching a few hundred documents, runs on its request thread and answers as fast as it does with no pool. Small segments are handed over together for the same reason.
- **Unified time budgets:** Worker threads run each piece under the time budget of the originating request. When `EXOFIND_SEARCH_TIMEOUT` expires, all threads working on that request stop together.
- **Isolated maintenance threads:** Reindexing and index writing run on dedicated threads. They never borrow threads from the search pool, preventing background writes and reindexes from slowing down search queries.

Because a busy pool introduces minimal overhead, `EXOFIND_SEARCH_THREADS` defaults to `auto`. In `auto` mode, the node allocates one thread per CPU core available to the process, honoring container CPU limits.

Configure this setting based on your node's workload:

- Set `EXOFIND_SEARCH_THREADS=0` on a node where query throughput under heavy load takes priority over single-request latency. Setting `0` disables the pool and runs every search entirely on its request thread.
- Set `EXOFIND_SEARCH_THREADS` to a lower number to reserve CPU cores for request handling and other process tasks.

The `exofind.search.pieces` meter records whether pieces run on pool threads or on request threads. If most pieces run on the request thread, the pool is busy when requests arrive, indicating that the node is running near its CPU capacity.

Worker threads do not alter search results or facet counts. Worker threads collect each segment into a bitset of its own, and the node reads the bitsets in index order. Each facet is counted from start to finish on a single thread. The node keeps facet counts for later searches only after all pieces complete within the time budget. If a search exceeds its time budget on any thread, the node keeps nothing and drops the partial results, as explained in [Cost of a single search](#cost-of-a-single-search).

## Kernel memory mapping limits

Lucene maps multiple files for each index segment. A node serving numerous indexes or handling frequent segment merges can open tens of thousands of memory mappings.

Linux limits the maximum number of memory mappings per process through the `vm.max_map_count` kernel parameter (often defaulted to 65530). When a node reaches this limit, subsequent attempts to open an index file fail with an `IOException` whose message starts with `Map failed` and names `sysctl vm.max_map_count` as one thing to review.

Because `vm.max_map_count` is a system-wide kernel setting and is not namespaced within Linux containers, you cannot configure it inside an unprivileged container. You must raise `vm.max_map_count` on the host operating system or through a privileged initialization process before starting node containers.

## Related

- [Architecture](architecture.md) — Node roles, index caching, and object storage coordination.
- [Configuration reference](../reference/configuration.md) — Reference documentation for node environment variables.
