# Synchronization

How does the system keep an index in object storage consistent when nodes join, leave, or fail? Two complementary mechanisms maintain consistency and coordinate writes:

- **Leadership table:** A shared table that tracks node liveness and assigns index write responsibility.
- **Conditional writes:** Atomic updates and epoch-scoped storage keys that prevent stale or concurrent writers from corrupting data.

Neither mechanism replaces the other. The leadership table provides liveness and efficient resource use, while conditional writes enforce data safety.

## The manifest is what a synchronized index is

A push writes the files of a pinned Lucene commit together with the index definition and a manifest. The manifest lists those files with their sizes and checksums. The system compares manifests to decide whether to push or pull data, rather than comparing files directly or relying on Lucene segment numbers, because an index definition can change without a new Lucene commit.

Replacing the remote manifest requires a conditional write using an `If-Match` header on the ETag of the manifest the writer last saw, or `If-None-Match: *` for the initial write. If a push is based on a manifest that the remote storage no longer holds, the write fails instead of overwriting changes pushed by another writer. This conditional check provides safety: a node that erroneously assumes it is still the designated writer can attempt a push, but storage rejects it. At startup, candidate nodes verify that the storage backend enforces conditional writes and refuse to run against storage that does not. Google Cloud Storage states the same condition on the generation of the object instead of on its ETag, so a node pointed at it translates the headers of a write. For the headers themselves, see [Object storage requirements](../reference/object-storage.md).

## The registry carries change hints

A node keeps its own copy of the manifest of each open index and of the search settings object of each index it serves. Keeping a copy current means asking storage whether the object changed. Both reads are conditional, but asking about each index separately still costs one request per index per interval per node, so the cost grows with the number of indexes rather than with the number of changes.

To reduce polling requests, the registry includes version hints for each index. For each index, the registry records the version of the most recent manifest push for each generation and the version of the stored settings object. A node skips storage requests if its local copy matches the hinted version, and fetches the object only when the hint changes. This design keeps steady-state request costs proportional to the number of nodes rather than the number of indexes.

The node that updates an object also reports its hint. A manifest push reports the manifest version, and updating settings reports the settings version. Because both document writes and settings updates execute on the designated index writer, the writer always reports its own updates. The writer buffers hints for several seconds and applies them to the registry in a single conditional write. This batching avoids contending for the registry on every push. For entries that predate hints, the index writer reads storage and populates hints over multiple passes, avoiding request bursts during upgrades.

A version reaches a reading node through these steps:

```d2 title="A version travels from a push through the registry to a reading node, which fetches the object only when the version differs"
direction: down

change: A push or a settings update
buffer: The writer buffers the version for several seconds
write: One conditional write puts the versions in the registry
read: A node reads the registry once

compare: Does the version match the local copy? {
  shape: diamond
}

skip: Make no storage request
fetch: Fetch the manifest or the settings object

change -> buffer
buffer -> write
write -> read
read -> compare
compare -> skip: Matches
compare -> fetch: Differs
```

A single read of the index registry serves the whole node. Two parts of a node work from the registry: open indexes (which pull manifests) and the search settings of the indexes the node serves. Each part receives the result of that one read. Each part states how often it wants the registry read, and the node reads at the shortest interval any part requests:

- The open indexes request `EXOFIND_INDEXES_REFRESH_INTERVAL` (default 30 seconds).
- The search settings request `EXOFIND_SETTINGS_REFRESH_INTERVAL` (default 10 seconds), and request nothing while the node holds no settings. A node that has served no searches reads at the index interval alone.

Each refresh interval provides two guarantees for the objects it names:

- The node makes no two storage requests for the same index inside the interval. An index written continuously therefore costs a reader at most one manifest request per interval, regardless of how often the registry reports a new version.
- The node considers every index at least that often.

Because a part acts on every read rather than on a schedule of its own, a change usually reaches a node sooner than its own interval promises. An index that has been quiet is pulled on the first read after its version changes.

A part never runs on the reading thread, and a part still working from an earlier read is passed over until it finishes. A pull that takes minutes does not delay search settings from reaching the node.

The read reports whether the registry changed. A node removes the local copies of indexes and generations the deployment no longer holds only when it changed, and once when the node starts. A registry that says the same thing twice costs the node nothing.

Hints stay advisory rather than authoritative state. A node verifies every local copy directly against storage after at most `EXOFIND_INDEXES_VERIFY_INTERVAL` (default 10 minutes), whatever the hints say. Both refresh intervals are held under the verify interval, so a refresh interval configured above it cannot suppress the verification it promises. If a writer crashes before reporting a hint, or if an older node overwrites the registry without hints, the system experiences temporary staleness or extra requests, but never corrupted state. Like the leadership table, hints optimize performance while conditional reads and writes enforce safety.

## Visibility is bounded staleness, not a watch

A node learns of changes by polling the registry at its refresh interval. Storage never notifies a node that an index changed.

S3 has no watch, subscribe, or long-poll API. Reads are pull-only. Storage notifications, such as AWS S3 Event Notifications, MinIO bucket notifications, SeaweedFS metadata subscriptions, and Ceph RGW notifications, are not portable across storage backends and operate on a best-effort basis. A notification can shorten the window between a push and the pull that follows, but it cannot close the window or serve as a source of truth.

The engine defines a staleness bound instead. A local copy is at most one refresh interval behind the registry, and the registry is at most a few seconds behind a push. Because every poll is a conditional request, an up-to-date copy costs one storage request per interval.

A read that cannot accept this bound carries a freshness token naming an index state. The answering node closes the gap for that read alone: a conditional read of the registry for a generation, a conditional read of the settings object for a settings version, and a pull for a commit sequence. The requests this costs grow with the reads that demand freshness, not with the number of indexes. Background polling remains unchanged.

A freshness token cannot make an uncommitted write survive a writer crash. If a writer stops before committing, it loses the write. When a successor node takes over the index, its commits advance the sequence to the number named in the token without the lost write. For more details, see [What a write guarantees](write-guarantees.md#what-a-freshness-token-promises).

## Why writes are scoped to epochs

Lucene names its files by sequential numbering. Two independent writer sessions can both produce a file named `_5.cfs`. If both sessions uploaded files using that name, a writer that fails the manifest race could overwrite a file referenced by the winning writer's manifest.

To prevent collisions, object keys are scoped to writer sessions. A writer session claims an epoch by conditionally updating the manifest, and picks a random session id. It then uploads all files under `e<epoch>-<session>/`. If storage rejects the epoch claim, the session does not upload any files and does not open its writer. File names remain local, while object keys are remote.

A session makes a conditional manifest write twice: once to claim its epoch, and once for every push:

```d2 title="A session claims an epoch before it opens a writer, and every push uploads its files before it replaces the manifest"
direction: down

open: A writer session starts

claim: Claim the epoch {
  shape: diamond
}

stop: Upload nothing and open no writer
writer: Open the writer and acknowledge writes
upload: "Upload the files of the commit under e<epoch>-<session>/"

push: Replace the manifest with If-Match {
  shape: diamond
}

accepted: Record the objects the manifest drops, then sweep
conflict: Mark the index needs_pull and pull

open -> claim
claim -> stop: Rejected
claim -> writer: Accepted
writer -> upload: A commit runs
upload -> push
push -> accepted: Accepted
push -> conflict: Refused
accepted -> writer: The session continues
```

When the remote holds no manifest, because the index is new or because the manifest was removed, the claim writes nothing. The first push then writes the manifest on the condition that there still is none, so of two sessions that both found the remote empty, only the first to push is accepted. A claim written as a manifest that names no files would make every reader remove its copy and answer with nothing until that push lands.

A manifest that was removed is not always a manifest that was lost. A delete removes it too, and the first generation of every index has the same name, so an index created again under a deleted name uses the prefix of the deleted one. A push from a copy that was in step with a manifest that is gone therefore writes the manifest again only when the registry in the bucket still names the generation of the copy. Each manifest records when its generation was created, as the registry states it, and the copy keeps that time in its local manifest. A registry that names the generation with another time, or does not name it, refuses the push with a conflict. A prefix emptied by hand or by a lifecycle rule, with the generation still registered, is written again from the copy. A node that opens a generation whose local copy records another time than the registry drops that copy, because it holds a deleted index. A node that already holds such a copy open closes it without a push when its next registry read states another time, so it stops serving the deleted documents before the new index pushes.

The registry check and the manifest write are two requests, and a delete can land between them. A writer of the deleted index can then still write its manifest into the prefix that the new index took over. That manifest records the time of the deleted generation. A node of the new generation does not pull a manifest that records another time while the registry in the bucket names the new generation with its own time. Its writer replaces that manifest on the condition that the manifest still has the same tag. The check of the registry keeps a node whose own registry read is behind from replacing the manifest of the new generation, because the bucket names the new time and not the time of that node.

Every manifest write also carries a random nonce. Two claims made from the same manifest are otherwise the same bytes, and a node whose claim was refused would read the claim of the other node as its own write that landed.

Two sessions that both found the remote empty claim the same epoch, and both upload their files before the first manifest write decides between them. The epoch alone therefore does not keep their uploads apart. The session id does: the session that loses uploads under a prefix of its own, so it cannot replace an object that the manifest of the winner names. An epoch can also come back after a manifest is removed, when a node that holds an older manifest pushes first. The session id keeps those sessions apart too.

The claim happens when the writer opens, before the node acknowledges any write, rather than before the first upload. A node that takes an index over acknowledges writes from the moment its writer opens, but pushes only at the next commit interval. Claiming at the first push would leave that whole span with nothing refusing the node the index was taken from: a stale push accepted in it wins the manifest race, and the successor then abandons and pulls over the documents it has already acknowledged.

Session prefixes keep the uploads of two sessions apart, but not two uploads of one session. Lucene never writes to a name twice, so that is enough for its files. The files the engine keeps beside the segments, which end in `.ef.bin` (such as the definition and the change log), are rewritten in place under the same name. Their keys also carry the checksum of the contents, so a push whose manifest is refused cannot have replaced an object that the accepted manifest still names. A pull verifies the size and checksum of every file it downloads against the manifest, so an object that does not hold what the manifest describes fails the pull instead of entering the index. A push checks each file against the manifest before the upload and sends its MD5 digest with it. The storage refuses a body that was changed on its way, so no manifest names an object that every pull then fails on.

The last pulled manifest does not vouch for files the engine rewrites in place, because the node can rewrite them after that pull. For example, a node can write a definition update whose push is refused. A pull therefore checks the local copy of each file ending in `.ef.bin` against the manifest and replaces any file that differs with the copy the manifest names.

Unchanged files retain their keys across epochs, and the manifest records each key alongside its corresponding file name. This mapping avoids re-uploading files that a previous indexer already pushed, making failovers efficient. After adopting a pulled manifest, a session claims a new epoch because the adopted manifest can reference keys from the epoch where it originated.

When a push replaces the manifest, the writer records the objects the new manifest no longer names. The writer deletes them at the first push after a one-hour grace period. This delay exists because a reader that pulled the old manifest can still be downloading an object, and a missing object fails its whole pull. A first pull of a large index takes longer than the commit interval, so a writer that deleted at once could fail every attempt while merges run.

A periodic listing sweep removes unreferenced objects that a push did not clean up. The sweep skips objects younger than the grace period. The sweep also skips objects the writer stopped naming less than a grace period ago, regardless of upload age. The storage clock gives the age of an object, and the clock of the node can run ahead of it. So the sweep measures the grace period back from the newest object in its listing when that object is older than the node clock says.

The recorded list lives in memory, so a writer that stops loses it. The next writer cannot know which objects an earlier writer stopped naming, or when. It knows only that this happened before it read the manifest it starts from. So for one grace period after a writer reads a manifest that it did not write, from its disk at start or from a pull, its sweep removes nothing.

A failed pull keeps the files it verified. The retry compares size and checksum against the manifest and downloads only what is missing or differs. The pull always downloads an entry without a checksum, because a file with the same name and size that a writer on the node left behind would otherwise pass.

A pull moves every file into place before it writes the manifest, so a pull that stops part way leaves files of a newer manifest beside the older one. Before it changes the first file, the pull writes a local record of the manifest it moves to, and it removes the record when it writes the manifest. A node asks whether a closed copy holds commits or a definition that the remote never got before it hands the index over or evicts the copy. A file that the record names came from the remote, so it does not count.

## How merged files leave the bucket

When Lucene merges small segments into larger ones, a commit includes the merged segment and drops the old segment files from the index. The merge deletes nothing from the bucket directly. The push following the commit records the objects the new manifest no longer names, and the writer deletes them at the first push after a one-hour grace period.

Commit triggers determine when the push that records obsolete objects runs:

- `EXOFIND_INDEXES_COMMIT_MAX_CHANGES` triggers a commit after reaching a change threshold (default 10,000 changes).
- `EXOFIND_INDEXES_COMMIT_MAX_INTERVAL` triggers a commit after a time threshold (default 5 seconds).

Under a normal write load, the writer deletes obsolete objects at the first push after the grace period, so the bucket holds about an hour of merged segments beside the index. When writes stop, no further pushes occur. Every node that can index therefore also runs the orphan sweep on a timer.

The timer ticks every `EXOFIND_INDEXES_REMOVAL_SWEEP_INTERVAL` (default: `10m`), the same interval the removal sweep uses. On every tick the node asks each open generation it writes to sweep. A generation lists its objects at most once per grace period, whether a push or the timer asks, and a new instance seeds its last sweep at a random point inside the grace period, so a node that opens many generations does not list all of them at once.

A push sweeps right after its conditional manifest write, so it sweeps with the manifest the remote holds. A timer sweep has no such guarantee: the node may have lost the index without knowing it yet, and its successor may name again a checksum-keyed object this node stopped naming. A timer sweep therefore reads the entity tag of the remote manifest first and removes nothing when the tag differs from the one the node last synchronized. A successor replaces the manifest when it claims the index, so a changed tag also catches a claim the node has not learned of.

As a known limit, only open generations are swept. A held index that nothing has opened has no manifest in memory, and opening it costs a writer and a pull, so its remote is left until it opens again.

A push uploads all new files before writing the manifest conditionally. If a push stops halfway and uploads only some of its files, readers remain unharmed because they continue following the previous manifest. The unreferenced uploads sit harmlessly in the bucket until a later sweep removes them after the one-hour grace period. In contrast, publishing a manifest that names a missing object would break readers by causing their pulls to fail. To prevent missing objects, a push uploads all required files before updating the manifest, checks the remote baseline by entity tag before uploading, and treats a missing remote manifest as empty so all files are uploaded again.

## A pull replaces the files an open writer holds

Only the node that holds the index claim writes to an index, using a single Lucene `IndexWriter` over a local directory. Two events can put an index in the `needs_pull` state while its writer remains open: object storage refuses the conditional manifest write of a push because another node pushed since, causing a conflict, or the node loses the index claim while paused and discovers the loss during the next coordination round. The refresh loop then pulls every index in `needs_pull`.

An index accepts writes for as long as a push runs, so a write can record its change after the conflict. The index stays in `needs_pull` and refuses the writes that follow. An index that said it held changes again would never be pulled: it would go on accepting writes, conflict on every commit, and drop everything it accepted when a pull finally came.

Calling `IndexWriter.rollback` restores the local directory to the last commit that writer made, deleting every `segments` file and segment file not named in that commit. Because a pull downloads files with those names from the manifest pushed by another node, rolling back after the download deletes the newly pulled files. The directory is left with no commit or with files from two writers. If the node only reads, it answers searches with no hits, and subsequent pulls receive HTTP 304 because the local manifest already matches remote storage. If the node writes, it opens a new empty index over the directory and pushes it using the current entity tag, deleting every segment object named in the previous manifest.

To prevent this corruption, the engine rolls the writer back before the pull starts, while the index is marked as pulling. Uncommitted documents are dropped, which the pull does in either case because the node continues from the copy in storage. The index holds no writer and refuses writes until the pull finishes and opens the next one. If a pull fails, the index remains in `needs_pull` so that a subsequent pull opens a writer.

Three checks protect a local copy that loses files for another reason. When the local manifest names a commit, the node opens the writer in Lucene append mode, so Lucene reports a missing commit as an error instead of creating an empty index. A node that only reads refuses to open such a copy as an empty index for the same reason. Local storage mode keeps no manifest and does not use append mode. There, the directory is the only copy of the index, and only the creation of an index or a generation makes it. A node in local storage mode does not open a generation that the registry names when its directory is gone, because the writer would create an empty index in its place. In addition, a push fails if its files contain no Lucene commit while the last synchronized manifest names one, preventing a manifest with no segments from deleting remote segment objects and making the index unrecoverable.

A pull repairs such a copy. Before each pull, the node checks that the `segments` file of the commit that the local manifest names is on disk. When the directory holds no commit, the pull does not send the entity tag, reads the manifest in full, and downloads the missing files, also when the remote manifest has not changed. A process that stops while it removes a local copy cannot cause this state, because the removal deletes the manifest before any other file.

## A writer that Lucene closes is replaced

Lucene closes an `IndexWriter` on its own when one of its own writes fails, such as a commit, a flush, or a merge on a full disk. It rolls the writer back to its last commit, and the writer then refuses every write and every commit. If the engine kept that writer, the index would refuse writes until the process restarts, also after the disk has free space again. In object storage mode the node would also call off every handover, because the flush of the handover fails on the closed writer, so no other node could take the index over.

When a commit or a write finds the writer closed, the engine lets the writer go and marks the index `needs_pull`. It logs an error with the number of acknowledged changes that Lucene dropped, because the clients that sent those changes keep no list of them. Writes answer `index:out_of_date` until the refresh loop pulls the index and opens a new writer over the last commit. A handover goes ahead, because the closed writer holds nothing that a push could save.

## A close waits for the pull that is running

A node closes an open generation when the cache evicts it, when the node hands the index over, and when it shuts down. The next request opens a new instance over the same local directory, and that instance pulls into it. A close therefore stops further pulls of the instance it closes and waits for the pull that is running. Two pulls of one directory download into the same paths, write their own manifest over each other, and delete the files the other one brought.

The close tells the synchronization to stop before it waits, so the pull returns after the file it is downloading instead of after the whole index. A stopped pull writes no manifest and deletes no local file. It leaves the local copy in the state a pull that failed part way leaves, and the pull of the next instance checks the downloaded files against the manifest it applies.

## Leadership is liveness, not safety

The bucket maintains a single leadership table that tracks which node writes to each index. The table contains a claim entry for each index naming its holder, alongside an entry for each candidate node indicating that the candidate is alive. The table is updated as a whole, conditionally based on its version. If two candidates attempt concurrent updates, only one write succeeds.

Every candidate runs a coordination round at an interval equal to one-third of the claim duration (`EXOFIND_INDEXER_LEASE_DURATION`), measured from the start of the previous round rather than from its end. Three rounds have to fit inside a lease, so an interval that grew with the time storage took would let the claims of a running node lapse. During a round, a candidate performs the following actions:

- Renews its own entries.
- Takes over claims whose holders stopped renewing due to crashes, hangs, or network partitions.
- Rebalances index distribution by claiming unassigned indexes if it holds fewer than its fair share, or handing over an index if it holds more than its share while another candidate holds less.

The candidate selects the most idle index for handover based on a write count that halves every few minutes. This keeps active indexes on writers with warm Lucene state, while quiet indexes absorb the cost of pulling and reopening data. The time required for a claim to lapse determines approximate failover duration.

A candidate that cannot renew its claims gives them up on its own one-tenth of the claim duration before they lapse. Other candidates may take a claim from the moment it lapses, so the holder stops writing first. A timer separate from the rounds makes this check, because a round can wait on a storage request that does not return. Each request for the table also has a limit of one-third of the claim duration.

Two processes can run under one node name. An earlier process may not have released its claims before a restart, or two processes may start with the same `EXOFIND_NODE_ID`. Each process therefore picks a random session when it starts, and writes the session into its claims and its candidate entry. A process treats a claim that names its node under another session like the claim of another node: it waits for the claim to lapse before it takes the index. Without the session, both processes take every claim that names their node, and both write the same indexes. As a result, a node that restarts after a crash waits up to one claim duration for its indexes, the same as any other candidate. A claim without a session comes from a version that did not record one, and a process takes such a claim as its own.

Equal index counts can still result in uneven load, such as when one node holds all active indexes and another holds only idle ones. To balance load, each claim includes a write load metric equal to the bit length of the decaying write count, which increments when write traffic approximately doubles.

When a node's load total substantially exceeds that of the least-loaded candidate, the node marks an index claim as offered. An underloaded candidate responds by recording itself as the taker in the claim, and the holder transfers the claim. Only the holder transfers a claim, ensuring an index changes hands only when its writer initiates the transfer. The count-balancing process completes the exchange by moving an idle index back in the opposite direction.

A node offers an index only when the index load metric fits twice into the difference between the two nodes' totals. Moving the index must narrow the load gap rather than reverse it. This threshold prevents two nodes from repeatedly trading a single active index back and forth.

Index handovers follow a strict order to protect acknowledged writes. A holder releases a claim only after committing and pushing all pending index data:

1. The index stops accepting writes. Incoming writes during this transition are rejected, and the caller retries them.
2. The holder flushes and pushes pending data to storage.
3. In a subsequent round, the holder transfers the claim to the taker, or drops the claim for an under-capacity candidate to acquire.

A failed flush and a lapsed lease each end the handover in a different way:

```d2 title="The steps of a handover, with the branch a failed flush takes and the branch a lapsed lease takes"
direction: down

decided: A round decides to hand the index over
stop: The index stops accepting writes
flush: The holder flushes and pushes pending data

result: How did the flush end? {
  shape: diamond
}

transfer: A later round moves the claim
cancel: Writes are accepted here again
lapsed: The claim lapses with no push

decided -> stop
stop -> flush
flush -> result
result -> transfer: Finished
result -> cancel: Failed
result -> lapsed: Outlasted the lease
cancel -> decided: A later round starts again
```

Because of this order, a successor node always pulls a manifest that includes the flush, preserving acknowledged documents across rebalances. A shutting-down node follows the same order for all held indexes: it flushes first, then removes itself from the table. While the flush runs, the node renews the claims of the indexes it flushes, for up to one lease duration. If the flush takes longer, the node stops renewing, and the claims lapse instead of being released mid-flush. When a node loses a claim because the lease lapsed, it pushes nothing, because a successor might already be writing. The expired node drops unpushed data, while conditional writes prevent storage corruption.

A failed flush cancels the handover instead of completing it. The index keeps its writer and the data the flush could not push, the claim stays with the holder, and the index accepts writes here again. A subsequent round starts the handover anew and flushes again. A shutting-down node whose flush fails leaves its claims to lapse rather than releasing them, so a successor waits the lease out instead of pulling a manifest that is missing acknowledged documents.

The flush covers every local copy of the index that can hold acknowledged documents:

- The open generations commit and push what they hold.
- A generation that the node evicted and is still closing pushes as it closes, and the flush waits for that close.
- A close whose push failed leaves its commit on disk. The flush pushes that copy.

If one of these pushes fails, or a close does not finish within 30 seconds, the flush fails and the handover is called off. Without this, an evicted generation could hold the only copy of acknowledged documents when the claim moves.

A node tells the two cases apart by how the claim left it. A claim it still holds while the index drains marks a handover it chose, so the index flushes. A claim that ends up naming another node, or that the round dropped along with a deleted index, marks a loss. This is also true while the index drains: a claim that leaves before the flush is done is a loss, not the end of the handover. Every generation of a lost index then stops pushing at once, including a flush that a handover queued moments earlier and the push that closing an index normally makes.

An unassigned index does not wait for a coordination round. The first candidate node that receives a write claims the index immediately. This ensures newly created indexes acquire writers immediately and routes writes promptly if a holder fails.

The leadership table ensures that at most one node expends effort writing to each index, and informs other nodes where to forward writes. The table does not guarantee data safety. For example, clock drift or a paused process can cause a stale node to attempt writes after its claim has lapsed. Conditional manifest writes and epoch scoping prevent stale writers from corrupting data. The leadership table maintains system liveness, while conditional writes provide safety.

## Claims are timed without the wall clock

Each entry in the table carries an expiry: the wall clock time of the node that wrote it, plus one claim duration. The wall clocks of two nodes do not always agree, and the wall clock of one node can step forward or back. If a node compared the expiry of another node with its own wall clock, a node whose clock runs ahead could take a claim that its holder renews every round. The holder then loses the index and the documents it acknowledged and did not push yet. A forward step on the holder would make it give up claims that are still valid, and a backward step would let it write after the other nodes see its claim as lapsed.

Each node therefore times claims with its monotonic clock, which measures elapsed time and does not step:

- The holder measures its claims from the moment the round that renewed them read the table. That moment comes before the storage takes the renewal, so no other node can see the claims lapse earlier.
- Another node finds an entry lapsed only when it has read the same entry, with the same expiry, for one full claim duration on its own monotonic clock. A renewal writes a new expiry, so an entry whose holder renews it never stays the same for that long.

The holder gives its claims up one-tenth of the claim duration before they lapse, and the other nodes start counting only after they read the renewal. The difference is the margin that keeps the holder and its successor from writing at the same time. The expiry in the entry still counts: a node also waits until its own wall clock passes the expiry. That condition can make a node wait longer, but never shorter. As a result, a candidate that has just started, or that reads an entry for the first time, waits one claim duration before it takes the claim of a crashed node. A failover takes between one claim duration and one claim duration plus two rounds after the last renewal.

The table has no new fields for this, so nodes of older versions read and write it as before. A node of an older version still compares expiries with its wall clock, and clock skew can make it take a claim early. A node logs a warning when its wall clock steps between two rounds. It also logs a warning when the entry of another candidate expires more than 1.1 claim durations after the current time on its own wall clock. Only a wall clock that runs ahead writes such an entry, so only the node whose clock runs behind sees this sign.

## Related

- [What a write guarantees](write-guarantees.md) - What a client can assume after a write returns, and what a failover does to it.
- [Storage layout](../reference/storage-layout.md) - The manifests, the leadership table, and the epoch prefixes as objects in the bucket.
- [Object storage requirements](../reference/object-storage.md) - The conditional write headers a bucket has to enforce.
- [Glossary](../reference/glossary.md) - Claims, epochs, manifests, and the other terms used here.
- [Generations](generations.md) - Why an index is a name with generations under it.
- [Running multiple nodes](../how-to/run-multiple-nodes.md) - Configuring candidacy, failover, and write routing.
