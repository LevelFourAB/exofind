# Synchronization

How does the system keep an index in object storage consistent when nodes join, leave, or fail? Two complementary mechanisms maintain consistency and coordinate writes:

- **Leadership table:** A shared table that tracks node liveness and assigns index write responsibility.
- **Conditional writes:** Atomic updates and epoch-scoped storage keys that prevent stale or concurrent writers from corrupting data.

Neither mechanism replaces the other. The leadership table provides liveness and efficient resource use, while conditional writes enforce data safety.

## The manifest is what a synchronized index is

A push writes the files of a pinned Lucene commit together with the index definition and a manifest. The manifest lists those files with their sizes and checksums. The system compares manifests to decide whether to push or pull data, rather than comparing files directly or relying on Lucene segment numbers, because an index definition can change without a new Lucene commit.

Replacing the remote manifest requires a conditional write using an `If-Match` header on the ETag of the manifest the writer last saw, or `If-None-Match: *` for the initial write. If a push is based on a manifest that the remote storage no longer holds, the write fails instead of overwriting changes pushed by another writer. This conditional check provides safety: a node that erroneously assumes it is still the designated writer can attempt a push, but storage rejects it. At startup, candidate nodes verify that the storage backend enforces conditional writes and refuse to run against storage that does not.

## The registry carries change hints

Nodes periodically poll storage for two objects: the manifest of each open index (to pull changes) and the search settings object of each served index. Both reads are conditional. However, checking quiet indexes still incurs one request per index per interval per node.

To reduce polling requests, the registry includes version hints for each index. Every node already polls the registry regardless of how many indexes exist. For each index, the registry records the version of the most recent manifest push for each generation and the version of the stored settings object. A node skips storage requests if its local copy matches the hinted version, and fetches the object only when the hint changes. This design keeps steady-state request costs proportional to the number of nodes rather than the number of indexes.

The node that updates an object also reports its hint. A manifest push reports the manifest version, and updating settings reports the settings version. Because both document writes and settings updates execute on the designated index writer, the writer always reports its own updates. The writer buffers hints for several seconds and applies them to the registry in a single conditional write. This batching avoids contending for the registry on every push. For entries that predate hints, the index writer reads storage and populates hints over multiple passes, avoiding request bursts during upgrades.

Hints provide efficiency rather than authoritative state. Even if hints indicate no change, nodes verify every local copy directly against storage after at most `INDEXES_VERIFY_INTERVAL`. If a writer crashes before reporting a hint, or if an older node overwrites the registry without hints, the system experiences temporary staleness or extra requests, but never corrupted state. Like the leadership table, hints optimize performance while conditional reads and writes enforce safety.

## Why writes are scoped to epochs

Lucene names its files by sequential numbering. Two independent writer sessions can both produce a file named `_5.cfs`. If both sessions uploaded files using that name, a writer that fails the manifest race could overwrite a file referenced by the winning writer's manifest.

To prevent collisions, object keys are scoped to epochs. Before its first upload, a writer session claims an epoch by conditionally updating the manifest, and then uploads all files under `e<epoch>/`. If storage rejects the epoch claim, the session does not upload any files. File names remain local, while object keys are remote.

Unchanged files retain their keys across epochs, and the manifest records each key alongside its corresponding file name. This mapping avoids re-uploading files that a previous indexer already pushed, making failovers efficient. After adopting a pulled manifest, a session claims a new epoch because the adopted manifest can reference keys from the epoch where it originated.

When pushing an update, the system diffs the old and new manifests to identify and delete unreferenced objects. If this cleanup is interrupted, a periodic listing sweep removes any remaining unreferenced objects. The sweep only processes objects older than a grace period to avoid racing against uploads that are not yet recorded in a manifest.

## Leadership is liveness, not safety

The bucket maintains a single leadership table that tracks which node writes to each index. The table contains a claim entry for each index naming its holder, alongside an entry for each candidate node indicating that the candidate is alive. The table is updated as a whole, conditionally based on its version. If two candidates attempt concurrent updates, only one write succeeds.

Every candidate runs a coordination round at an interval equal to one-third of the claim duration (`INDEXER_LEASE_DURATION`). During a round, a candidate performs the following actions:

- Renews its own entries.
- Takes over claims whose holders stopped renewing due to crashes, hangs, or network partitions.
- Rebalances index distribution by claiming unassigned indexes if it holds fewer than its fair share, or handing over an index if it holds more than its share while another candidate holds less.

The candidate selects the most idle index for handover based on a write count that halves every few minutes. This keeps active indexes on writers with warm Lucene state, while quiet indexes absorb the cost of pulling and reopening data. The time required for a claim to lapse determines approximate failover duration.

Equal index counts can still result in uneven load, such as when one node holds all active indexes and another holds only idle ones. To balance load, each claim includes a write load metric equal to the bit length of the decaying write count, which increments when write traffic approximately doubles.

When a node's load total substantially exceeds that of the least-loaded candidate, the node marks an index claim as offered. An underloaded candidate responds by recording itself as the taker in the claim, and the holder transfers the claim. Only the holder transfers a claim, ensuring an index changes hands only when its writer initiates the transfer. The count-balancing process completes the exchange by moving an idle index back in the opposite direction.

A node offers an index only when the index load metric fits twice into the difference between the two nodes' totals. Moving the index must narrow the load gap rather than reverse it. This threshold prevents two nodes from repeatedly trading a single active index back and forth.

Index handovers follow a strict order to protect acknowledged writes. A holder releases a claim only after committing and pushing all pending index data:

1. The index stops accepting writes. Incoming writes during this transition are rejected, and the caller retries them.
2. The holder flushes and pushes pending data to storage.
3. In a subsequent round, the holder transfers the claim to the taker, or drops the claim for an under-capacity candidate to acquire.

Because of this order, a successor node always pulls a manifest that includes the flush, preserving acknowledged documents across rebalances. A shutting-down node follows the same order for all held indexes: it flushes first, then removes itself from the table. If a flush exceeds the lease duration, the claims lapse instead of being released mid-flush. When a node loses a claim because the lease lapsed, it pushes nothing, because a successor might already be writing. The expired node drops unpushed data, while conditional writes prevent storage corruption.

An unassigned index does not wait for a coordination round. The first candidate node that receives a write claims the index immediately. This ensures newly created indexes acquire writers immediately and routes writes promptly if a holder fails.

The leadership table ensures that at most one node expends effort writing to each index, and informs other nodes where to forward writes. The table does not guarantee data safety. For example, clock drift or a paused process can cause a stale node to attempt writes after its claim has lapsed. Conditional manifest writes and epoch scoping prevent stale writers from corrupting data. The leadership table maintains system liveness, while conditional writes provide safety.
