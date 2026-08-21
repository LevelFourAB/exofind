# Architecture

Exofind stores its indexes in S3 compatible object storage and runs as a set
of interchangeable nodes in front of it. This page explains the shape that
follows from that choice; [Synchronization](synchronization.md) covers the
mechanics that make it safe.

## Storage is the source of truth

The copy of an index that matters is the one in the bucket. A node holds
local copies - Lucene directories on its own disk - of the indexes it serves,
but a local copy is a cache: a node can be wiped and replaced, and it will
pull everything back from storage. Nothing a node holds locally is ever the
only copy of anything that has been committed.

This is what makes the nodes cheap. They need disk for the indexes they
serve, but no volumes worth backing up, no replication between them, and no
membership protocol - two nodes never talk to each other directly. Everything
they coordinate, they coordinate through the bucket.

Even the disk is a budget rather than a commitment. A node keeps the files of
every index it has served, because reopening from a warm directory is nearly
free - but with [a bound configured](../reference/configuration.md#disk-use)
it removes the copies of the indexes that have gone longest without use, and
pulls one back in full if it is asked for again. Only copies the bucket fully
holds are ever removed, so the bound never deletes the last copy of anything.

A node can also be told to keep everything on its own disk instead, which is
this shape with the bucket taken out: one node, nothing to pull back from, and
a directory that is the deployment rather than a copy of it. What follows below
still holds, with nothing to hold it against - the registry is a file, every
index is held uncontested, and what the bucket's conditional writes were
protecting against is instead prevented by there being one process, which the
node enforces by claiming the directory for as long as it runs. The mode is
named in configuration rather than inferred from which settings are present, so
a node meant for a bucket cannot arrive here through a misspelt variable. [Run
on one node](../how-to/run-on-one-node.md) covers when that trade is the right
one.

## One writer per index, many readers

At any moment at most one node may modify an index, but different indexes
may be written by different nodes. Any number of nodes may hold the
`indexer` property, which makes them candidates; the candidates divide the
indexes among themselves through a leadership table in the bucket, each
holding a claim per index it writes. When a holder stops renewing, its
claims lapse and the other candidates pick them up, so running two or three
candidates is how writing survives a node dying - and, with more than one,
how write work spreads across them.

Every node also reads. It polls storage on an interval, notices indexes it
has not seen and changes to the ones it has, and pulls them. What the
deployment holds is read from one registry object rather than by listing the
bucket, so learning about every index costs one conditional request however
many there are - see [Generations](generations.md). A search runs on
whichever node receives it, against the state that node has - no search ever
needs to reach a writer. Search capacity scales by adding nodes; write
capacity scales by adding candidates, up to one node per index - the writes
to a single index never spread further.

The cost of this shape is freshness: a reader is as current as its last pull.
Exofind trades away the seconds of freshness that a coordinated cluster buys
with its complexity.

## How requests flow

Searches (`POST /v1alpha1/indexes/{name}/search`) are answered locally by any
node. Writes - index definitions, documents, the commit action - run on the
node holding the index they are about. Another node forwards a write there
when the table says where that is, and answers with whatever the holder
answered, so a client can send any request to any node without doing
anything for it. An index nothing holds is appointed a writer by its first
write: a candidate that receives one claims the index on the spot, which is
also how a just-created index gets its writer. Only an index the deployment
holds is appointed one - a write naming an index that does not exist is
answered `404` where it lands, rather than claiming a writer for the name.
Only when there is no candidate to forward to is the write refused, with
`409 Conflict`.

## The life of an index on a node

An index on a node moves through the states in
[the admin API reference](../reference/admin-api.md#index-states): it is
discovered (`NEEDS_PULL`), pulled (`PULLING`), served (`USABLE`), and on the
node holding it accumulates changes (`MODIFIED`) that a commit pushes back
(`PUSHING`). Two states are refusals rather than steps: `UNSUPPORTED` means
the definition needs a capability this build does not have - fixed by
upgrading the node - and `INCOMPATIBLE` means the Lucene files are too old
for this build to open, which upgrading makes worse, not better. The
difference is explained in [Lucene compatibility](lucene-compatibility.md).
