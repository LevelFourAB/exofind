# Synchronization

Two mechanisms keep an index in object storage consistent while nodes come
and go: a leadership table that decides who *tries* to write each index, and
conditional writes that stop anyone else from *succeeding*. They answer
different questions and neither replaces the other.

## The manifest is what a synchronized index is

A push writes the files of a pinned Lucene commit together with the index
definition, and a *manifest* listing those files with their sizes and
checksums. Whether there is anything to push or pull is decided by comparing
manifests - not by comparing files, and not by the Lucene segment number
alone, because a definition can be replaced without Lucene committing
anything.

Replacing the remote manifest is conditional: `If-Match` on the ETag of the
manifest the writer last saw, or `If-None-Match: *` for the first write ever.
A push built on a manifest the remote no longer holds fails instead of
overwriting what another writer pushed. This is the safety mechanism - a
node that wrongly still believes it writes an index can attempt a push, but
storage refuses it. A candidate checks at startup that the storage actually
enforces conditional writes, and refuses to run as one against storage that
does not.

## Why writes are scoped to epochs

Lucene names its files by counting - two writer sessions can both produce a
`_5.cfs` that have nothing to do with each other. If both uploaded under that
name, the loser of the manifest race would have overwritten a file the
winner's manifest references.

So object keys are scoped: before its first upload a writer session claims an
*epoch* by conditionally rewriting the manifest, then uploads everything
under `e<epoch>/`. A session whose claim is refused never uploads at all.
File names stay local; keys are remote.

A file that does not change keeps its key across epochs - the manifest
records the key alongside the name. This is what makes a failover cheap: the
new indexer re-uploads nothing that the previous one already pushed. After
adopting a pulled manifest a session claims a fresh epoch of its own, because
the adopted manifest may reference keys from the epoch it came from.

Objects that no manifest references anymore are removed by diffing the old
and new manifests on push. When that cleanup is interrupted, a listing sweep
catches what is left - touching only objects older than a grace period, so
it can never race an upload that has not made it into a manifest yet.

## Leadership is liveness, not safety

Which node writes which index is kept in one *leadership table* in the
bucket: a claim per index naming its holder, next to an entry per candidate
saying it is alive. The table is replaced whole, conditionally on its
version like everything else, so two candidates changing it race for one
write exactly one wins. Every candidate runs a round at a third of the claim
duration (`INDEXER_LEASE_DURATION`): it renews its own entries, takes claims
whose holder stopped renewing - crashed, hung, partitioned - and divides the
indexes up, claiming free ones while it holds fewer than its fair share and
handing one over per round while it holds more and another candidate holds
less. A claim lapsing is roughly how long a failover takes.

Handing an index over is deliberate, so the node commits and pushes what it
still holds before reopening it read-only - documents that were acknowledged
but not yet committed survive a rebalance. Losing an index the uncertain way
- claims lapsing before they could be renewed - pushes nothing, because a
successor may already be writing; whatever was never pushed is dropped, and
the conditional writes below are what keep even that from corrupting
anything.

An index nothing holds does not wait for a round: the first write to reach a
candidate claims it there and then, which is how a just-created index gets a
writer and how a write lands somewhere useful the moment after a holder
died.

The table exists so that at most one node *spends effort* writing each
index, and so the other nodes know where to forward its writes. It is not
what protects the data: a clock can drift, a paused process can wake up
convinced it still holds claims that lapsed. When that happens, the
conditional manifest write and the epoch scoping above are what stand
between the stale writer and corruption. The table keeps the system live;
the conditional writes keep it safe. Keep both: neither alone is enough.
