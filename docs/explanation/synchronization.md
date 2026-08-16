# Synchronization

Two mechanisms keep an index in object storage consistent while nodes come
and go: a lease that decides who *tries* to write, and conditional writes
that stop anyone else from *succeeding*. They answer different questions and
neither replaces the other.

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
node that wrongly still believes it is the indexer can attempt a push, but
storage refuses it. An indexer checks at startup that the storage actually
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

## The lease is liveness, not safety

Indexer candidates compete for the role through a lease object in the bucket,
renewed by conditional writes at a third of its duration
(`INDEXER_LEASE_DURATION`). When the holder stops renewing - crashed, hung,
partitioned - the lease lapses and another candidate claims it, which is
roughly how long a failover takes.

The lease exists so that at most one node *spends effort* writing, and so
readers know where to redirect writes. It is not what protects the data: a
clock can drift, a paused process can wake up convinced it still holds a
lease that lapsed. When that happens, the conditional manifest write and the
epoch scoping above are what stand between the stale writer and corruption.
The lease keeps the system live; the conditional writes keep it safe. Keep
both: neither alone is enough.
