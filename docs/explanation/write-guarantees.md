# What a write guarantees

When an API request to index, update, or remove a document returns a `2xx`
status code, what does the system guarantee about the safety and visibility
of that data?

This document explains the lifecycle of a write in Exofind, when a document
becomes durable in remote storage, when it becomes visible to search queries,
and how to design clients around these guarantees.

## What a 2xx status means

A `200 OK` or `204 No Content` response means that the designated writer node
accepted the document into its Lucene index writer.

At this point, the change is not yet committed to the writer's disk and not yet
pushed to remote storage.

If the writer node crashes, loses power, or stops unexpectedly before a commit
occurs, uncommitted changes are lost. A `2xx` status code acknowledges
acceptance by the writer process, not permanent persistence in storage.

## When a change becomes durable

A change becomes durable when the writer node executes a commit. During a commit,
the writer performs several operations:

1. It writes and flushes a Lucene commit to its local disk.
2. It uploads the new segment files to the object storage bucket under the
   current epoch prefix.
3. It updates the index manifest in the bucket with a conditional write.

Once remote storage accepts the updated manifest, the change is durable. From
that moment on, the change survives the complete loss or termination of the
writer node because object storage is the authoritative source of truth.

Commits occur automatically based on two triggers configured in the
[configuration reference](../reference/configuration.md):

- **Change count**: `EXOFIND_INDEXES_COMMIT_MAX_CHANGES` (default: 10,000
  changes) triggers a commit when enough uncommitted changes accumulate.
- **Elapsed time**: `EXOFIND_INDEXES_COMMIT_MAX_INTERVAL` (default: 5 seconds)
  triggers a commit when the oldest uncommitted change waits this long.

You can also force an immediate commit by calling the commit endpoint
(`POST /v1alpha1/admin/indexes/{name}/actions/commit`).

## When a change becomes visible

Search queries do not see uncommitted writes. Visibility occurs in two separate
stages:

1. **On the writer node**: Changes become visible to searches on the writer node
   as soon as the commit finishes.
2. **On reader nodes**: Other nodes do not see the commit immediately. Reader
   nodes poll the shared index registry periodically and pull new index
   manifests and segment files from object storage.

A reader node checks for updates within `EXOFIND_INDEXES_REFRESH_INTERVAL`
(default: 30 seconds). Because search requests execute locally on whichever node
receives them, two nodes behind the same load balancer can return different
results for the same query while a reader node waits for its next refresh.

For strategies on managing this delay, see
[Make a write visible to search](../how-to/make-writes-visible.md).

## What a failover means for a write

Candidate nodes manage index write assignments through a shared leadership table
in object storage. If a writer node stops renewing its claim, its lease expires
after `EXOFIND_INDEXER_LEASE_DURATION` (default: 30 seconds). Another candidate
node then claims the index and begins a new writer session under a new storage
epoch. The new writer claims that epoch as it opens, before it accepts a single
write, so the entity tag the previous writer holds is already stale by the time
the new writer answers its first request.

If the previous writer was partitioned or paused and later attempts to commit,
its push fails:

- Remote storage only refuses the old writer's conditional manifest write,
  because the manifest's entity tag changed.
- Files the old writer uploaded sit under its own epoch prefix and never
  overwrite the new writer's files.

The old writer's uncommitted or unpushed changes cannot overwrite or corrupt the
new writer's state. When a failover happens, any writes that were acknowledged
with a `2xx` but not yet committed and pushed to object storage by the old writer
are lost rather than merged.

To learn more about epochs and conditional manifest writes, see
[Synchronization](synchronization.md).

## Architectural trade-offs and client strategies

Exofind uses an asynchronous durability model by design:

- **Single writer per index**: Only one node writes to an index at a time,
  avoiding distributed transaction protocols. For more details on node roles,
  see [Architecture](architecture.md).
- **Object storage as source of truth**: Nodes do not coordinate directly with
  each other. All state synchronizes through the bucket.
- **Cost of remote pushes**: Uploading segment files and updating manifests on
  every single HTTP request would introduce prohibitive latency and request
  costs.

Accepting writes into the index writer before committing allows Exofind to
provide high ingestion throughput while keeping infrastructure simple.

### Client design patterns

Because of these trade-offs, client applications should adopt the following
patterns:

- **Batch ingestion**: Stream multiple document writes together without
  requesting manual commits between requests.
- **Explicit commit at completion**: Send an explicit commit request to the
  admin API at the end of a bulk ingestion job to ensure durability and trigger
  replication.
- **Route for read-your-writes**: If an application requires immediate read
  visibility after writing, send search queries directly to the writer node's
  network address rather than through a general load balancer.
- **Retry failed requests**: If a write fails due to network interruptions or
  node failover, resend the idempotent document write. For error handling and
  retry guidance, see [Handle API errors](../how-to/handle-api-errors.md).

## Related

- [Make a write visible to search](../how-to/make-writes-visible.md) - Closing
  the gap between an accepted write and a search that can see it.
- [Indexing documents](../how-to/index-documents.md) - Sending documents, and
  committing once at the end of a load.
- [Handle errors in a client](../how-to/handle-api-errors.md) - Routing a
  failure by its code, and retrying without indexing anything twice.
- [Synchronization](synchronization.md) - Manifests, epochs, and the leadership
  table.
- [Architecture](architecture.md) - Why storage is the source of truth.
- [Storage layout](../reference/storage-layout.md) - The manifest and the epoch
  prefix a commit writes.
- [Configuration](../reference/configuration.md) - The commit, refresh, and
  lease variables named here.
