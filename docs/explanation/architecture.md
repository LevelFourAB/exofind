# Architecture

Exofind stores its indexes in S3-compatible object storage and runs as a set
of interchangeable nodes in front of it. This document explains how Exofind is
structured, why it uses object storage as the source of truth, and how nodes
coordinate index reads and writes. For the mechanics that ensure safety, see
[Synchronization](synchronization.md).

## Storage is the source of truth

The authoritative copy of an index is the copy stored in the bucket. A node
holds local copies—Lucene directories on its own disk—of the indexes it serves.
A local copy functions as a cache. If you wipe and replace a node, the node
pulls all data back from storage. A local node never holds the only copy of
committed data.

This design makes nodes inexpensive to run. Nodes need disk space for the
indexes they serve, but they do not require volumes that need backups,
replication between nodes, or a membership protocol. Two nodes never
communicate directly with each other. Nodes coordinate all actions through the
bucket.

Local disk space functions as a cache budget rather than a permanent
commitment. A node retains files for every index it has served because
reopening an index from an existing directory has low overhead. When you
configure [a disk bound](../reference/configuration.md#disk-use), the node
removes local copies of indexes that have been unused the longest. If a request
requires a removed index, the node pulls the complete index back from storage.
Because the node removes only copies that are fully stored in the bucket, the
bound never deletes the last copy of an index.

You can also configure a node to store all data on its local disk without an
object storage bucket. In this single-node configuration, the local directory
serves as the deployment rather than a cache. The registry is stored in a local
file, and the single node holds every index without contention. Single-process
execution replaces bucket-level conditional writes, and the node enforces this
by claiming the directory while the process runs. You set this mode explicitly
in configuration rather than by omitting bucket settings, preventing a node
from falling back to local storage due to a misspelled variable. For guidance on
when to use this mode, see [Run on one node](../how-to/run-on-one-node.md).

## One writer per index, many readers

At any given moment, only one node can modify an index. Different indexes can
be written by different nodes. Any number of nodes can have the `indexer`
property, which makes them candidates. Candidate nodes divide indexes among
themselves through a leadership table in the bucket, where each candidate holds
a claim for each index it writes. When a holder stops renewing, its claims lapse
and other candidates pick them up. Running two or three candidates ensures that
writes continue if a node fails. Running multiple candidates also distributes
the write workload across nodes.

Every node also reads. Nodes poll storage at regular intervals, detect new
indexes and changes to existing indexes, and pull the updates. A node learns
about all indexes in the deployment by reading a single registry object rather
than listing the bucket. Discovering all indexes requires only one conditional
request regardless of the total index count. For more information, see
[Generations](generations.md).

A search request runs on whichever node receives it, against the current local
state of that node. Search requests never need to reach a writer node. You
scale search capacity by adding nodes. You scale write capacity by adding
candidate nodes, up to a maximum of one node per index. Writes to a single
index do not spread across multiple nodes.

The trade-off of this architecture is data freshness: a reader is only as
current as its most recent pull from storage. Exofind trades away seconds of
freshness to avoid the complexity of a coordinated cluster.

## How requests flow

Any node answers search requests (`POST /v1alpha1/indexes/{name}/search`)
locally.

Write requests—such as index definitions, document updates, and commit
actions—run on the node that holds the target index. If another node receives a
write request, it checks the leadership table, forwards the request to the
holder node, and returns the holder's response to the client. This proxy
behavior allows clients to send any request to any node.

When an index has no assigned writer, its first write request assigns one: a
candidate node that receives the write claims the index immediately. Newly
created indexes also receive their writer this way. A writer is appointed only
for indexes that exist in the deployment. If a write request names an index that
does not exist, the receiving node returns `404` directly instead of claiming a
writer for the name. If no candidate node is available to handle or forward a
write request, the node refuses the request with `409 Conflict`.

## The life of an index on a node

An index on a node progresses through the lifecycle states listed in
[the admin API reference](../reference/admin-api.md#index-states):

- `NEEDS_PULL`: The node discovers the index in the registry.
- `PULLING`: The node downloads index files from storage.
- `USABLE`: The node serves reads and search queries from the local copy.
- `MODIFIED`: The writer node accumulates changes locally.
- `PUSHING`: The writer node commits and pushes changes back to storage.

Two states indicate errors rather than lifecycle steps:

- `UNSUPPORTED`: The index definition requires a capability that the current
  node build does not have. You resolve this by upgrading the node.
- `INCOMPATIBLE`: The Lucene files are too old for the current build to open.
  Upgrading the node makes this issue worse rather than better.

For more details on index version differences, see
[Lucene compatibility](lucene-compatibility.md).
