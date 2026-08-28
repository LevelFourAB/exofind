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

- **Search nodes** require minimal heap. Heap memory on a search node holds only in-flight query states, facet calculations, and decompressed document fields. The actual index structures reside in the page cache. Search nodes perform best with a smaller heap allocation (for example, 25% of memory), leaving 75% of available memory to cache index files.
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

1. **Storage directory locks:** An unresponsive node continues to hold operating system file locks on its `LOCAL_STORAGE_DIRECTORY` (`/data`), preventing replacement processes from opening index files.
2. **Lease renewals:** An indexer node that encounters heap starvation can continue executing background timer threads, renewing its leadership leases in object storage while failing to process incoming writes. Other candidate nodes cannot claim the indexes until the lease expires.

Setting `-XX:+ExitOnOutOfMemoryError` ensures that a failing node terminates immediately. Process termination releases local file locks and stops lease renewal heartbeats, allowing standby candidate nodes to take over indexing immediately.

## Kernel memory mapping limits

Lucene maps multiple files for each index segment. A node serving numerous indexes or handling frequent segment merges can open tens of thousands of memory mappings.

Linux limits the maximum number of memory mappings per process through the `vm.max_map_count` kernel parameter (often defaulted to 65530). When a node reaches this limit, subsequent attempts to open an index file fail with an `IOException` whose message starts with `Map failed` and names `sysctl vm.max_map_count` as one thing to review.

Because `vm.max_map_count` is a system-wide kernel setting and is not namespaced within Linux containers, you cannot configure it inside an unprivileged container. You must raise `vm.max_map_count` on the host operating system or through a privileged initialization process before starting node containers.

## Related

- [Architecture](architecture.md) — Node roles, index caching, and object storage coordination.
- [Configuration reference](../reference/configuration.md) — Reference documentation for node environment variables.
