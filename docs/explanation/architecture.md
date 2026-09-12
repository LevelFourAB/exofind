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

```d2 animateInterval=2500 title="Three nodes with local copies of the index files a bucket holds, and no connection between the nodes. One node is lost, the others keep answering, and a replacement pulls the files back from the bucket."
direction: right

# The picture runs through four frames. Every frame holds the same shapes and
# the same connections, and a step changes only what a shape is drawn with,
# because the layout is computed once per frame: an added shape, or a label of
# a different length, moves everything around it and the picture jumps. This
# is also why no connection carries a label and why the caption states a width
# of its own.

searches: Searches

a: Node A {
  copy: Local copies
}

b: Node B {
  copy: Local copies
}

c: Node C {
  copy: Local copies
}

bucket: Object storage {
  registry: Registry
  leadership: Leadership table
  files: Index files
}

searches -> a
searches -> b
searches -> c

a.copy <-> bucket.files
b.copy <-> bucket.files
c.copy <-> bucket.files

caption: Each node holds local copies of the indexes it serves. {
  near: bottom-center
  width: 660
  height: 46
  style: {
    fill: transparent
    stroke: transparent
    font-size: 17
    # A shape carries a bold label, and this shape is a sentence rather than a
    # part of the drawing.
    bold: false
  }
}

# The two colours are the ones the API pages spend: red says a thing was
# removed and green says a thing was created. Each is stated as one value
# rather than as the pair `website/src/styles/site.css` holds, because D2 is
# handed a colour and reads no stylesheet, and the site's theme overrides
# reach the greys alone. Each is a middle tone that clears 3:1 against the
# paper of both themes, and each is spent on a stroke rather than on text: no
# one colour clears 4.5:1 against both.

steps: {
  lost: {
    b.style: {
      stroke: "#c04f4f"
      stroke-dash: 4
      stroke-width: 2
      opacity: 0.6
    }
    b.copy.style.opacity: 0.3
    (searches -> b)[0].style: {
      opacity: 0.15
      stroke-dash: 4
    }
    (b.copy <-> bucket.files)[0].style: {
      opacity: 0.15
      stroke-dash: 4
    }
    caption: A node is lost. The others answer from their own copies.
  }

  pull: {
    b.style: {
      stroke: "#2f8f63"
      stroke-dash: 0
      stroke-width: 2
      opacity: 1
    }
    b.copy.style.opacity: 1
    (b.copy <-> bucket.files)[0]: {
      # The arrowhead into the bucket is dropped for this frame alone, so a
      # connection that is drawn in every frame points one way while the node
      # reads the files back.
      target-arrowhead.shape: none
      style: {
        opacity: 1
        stroke-dash: 0
        stroke: "#2f8f63"
        stroke-width: 2
      }
    }
    caption: A replacement starts with an empty disk and pulls the files back.
  }

  back: {
    (searches -> b)[0].style: {
      opacity: 1
      stroke-dash: 0
      stroke: "#2f8f63"
    }
    (b.copy <-> bucket.files)[0].target-arrowhead.shape: triangle
    caption: It serves from local copies again. No node held the only copy.
  }
}
```

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

The node that receives a request takes one of these paths:

```d2 title="A search answered from the local copy, and a write applied locally, forwarded to the holder, or refused"
direction: down

request: A request reaches a node

kind: Search or write? {
  shape: diamond
}

search: Answer from the local copy

holder: Which node holds the index? {
  shape: diamond
}

here: Apply the write here
forward: Forward to the holder and return its answer
refuse: Refuse with 409 Conflict

request -> kind
kind -> search: Search
kind -> holder: Write
holder -> here: This node
holder -> forward: Another node
holder -> refuse: No candidate
```

## The life of an index on a node

An index on a node progresses through the lifecycle states listed in
[the admin API reference](../reference/admin-api.md#index-states):

- `needs_pull`: The node discovers the index in the registry.
- `pulling`: The node downloads index files from storage.
- `usable`: The node serves reads and search queries from the local copy.
- `modified`: The writer node accumulates changes locally.
- `pushing`: The writer node commits and pushes changes back to storage.

Two states indicate errors rather than lifecycle steps:

- `unsupported`: The index definition requires a capability that the current
  node build does not have. You resolve this by upgrading the node.
- `incompatible`: The Lucene files are too old for the current build to open.
  Upgrading the node makes this issue worse rather than better.

The index moves between these states as pulls, writes, and pushes finish:

```d2 title="States of one index on one node, from the first pull through writes and pushes"
direction: down

needs_pull
pulling
usable
modified
pushing
unsupported
incompatible

needs_pull -> pulling: Pull starts
pulling -> needs_pull: Pull fails
pulling -> usable: Pull finishes
pulling -> unsupported: Needs a newer node
pulling -> incompatible: Files too old to open
usable -> modified: Write arrives
modified -> pushing: Commit starts
pushing -> usable: Push accepted
pushing -> modified: More writes arrived
pushing -> needs_pull: Push refused
```

For more details on index version differences, see
[Lucene compatibility](lucene-compatibility.md).

## What a restart costs

Opening an index requires a manifest request to storage and a Lucene reader over
the local directory. A newly started node holds no open indexes, so the first
request for each index waits for this work to finish. When a node serves hundreds
of indexes, an upgrade can result in hundreds of slow initial requests.

To avoid these delays, a node reopens indexes as soon as it reads the registry.
It ranks local copies using the same access records used by the disk sweep, then
opens the most frequently used indexes on background threads.

The node reports itself as unready while opening indexes. During a rolling
upgrade, this status holds traffic back until the node can serve requests from
an open index. The wait has a time limit: once the limit passes, the node
reports itself ready and opens any remaining indexes in the background.

The ranking counts search requests only. Opening an index at startup does not
increase its score. If a node preloads an index that receives no searches, it
stops preloading that index once other indexes outrank it.

Preloading works only if the local directory persists across the restart, such
as with a persistent volume or an in-place process restart. A node that restarts
with an empty directory has nothing to rank or open. Like a new node, it pulls
each index from storage when a request requires it.

For settings that limit how many indexes a node opens and how long it waits
before reporting ready, see
[Index management](../reference/configuration.md#index-management).
