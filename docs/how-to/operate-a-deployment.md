# Operate a deployment

What to check on a deployment that is already running: what each node is
serving, whether it is current, which node is writing, and what to do when
disk, an upgrade or a lost node gets in the way. Setting one up is [Run more
than one node](run-multiple-nodes.md); this is the day after.

There is no separate metrics or health endpoint. What a node will tell you is
the status of the indexes it holds, and its log.

## Check what a node came up as

A node names its own configuration before it answers anything, so what it is
never has to be reconstructed from the environment it was started with:

```
INFO  storage=object auth=keys indexer=true bucket=exofind directory=/var/lib/exofind Starting node, which may take the indexer role
INFO  node=node-a-7f21 address=http://node-a:8080 Competing for the indexer role
INFO  exofind 1.0.0-SNAPSHOT on JVM … started in 1.4s. Listening on: http://0.0.0.0:8080
```

The last line is the node ready to answer, and the address in it is the one
to reach it on. Ahead of it are the settings that decide what the node does -
`storage`, `auth`, `indexer` and where the indexes live - and the name it
holds the lease under, which every later line about the indexer role is keyed
by. A node that may not write says `only answers searches` instead and
competes for nothing. A node storing locally names no bucket and takes the
role uncontested.

Anything worth a second look arrives as a `WARN` of its own among those
lines: `local` mode, authentication turned off, or settings the named mode
never reads.

Stopping closes the open indexes, and an index this node has changed is pushed
as it closes. `Closing the open indexes` is what a stop that takes its time is
waiting on.

## See what a node is serving

```http
GET /v1alpha1/admin/indexes
```

The list is the registry - what the deployment holds, the same on every node.
What differs per node is the status, which is observed rather than stored:

```http
GET /v1alpha1/admin/indexes/products
```

```json
"status": {
  "state": "USABLE",
  "readOnly": true,
  "luceneCompatibility": "CURRENT"
}
```

Ask each node in turn to see the deployment as its nodes see it - a load
balancer answers for whichever it picks, which is not what you want here.

Four [states](../reference/admin-api.md#index-states) are steps a healthy
index moves through: `NEEDS_PULL`, `PULLING`, `USABLE`, and on the indexer
`MODIFIED` and `PUSHING`. Two are refusals and want a decision:

- `UNSUPPORTED` - the definition uses something this build does not have. The
  node is older than whatever wrote the definition; upgrade it.
- `INCOMPATIBLE` - the Lucene files are too old for this build to open.
  Upgrading moves further away, not closer; see [Survive Lucene
  upgrades](survive-lucene-upgrades.md).

`CLOSED` is not a fault: the index was closed to free disk or an open slot,
and asking for it opens it again.

## Tell whether a node is current

A change is searchable on a reader within the commit delay plus one refresh
interval - `INDEXES_COMMIT_MAX_INTERVAL` (5s) plus `INDEXES_REFRESH_INTERVAL`
(30s) with the defaults. A node showing `NEEDS_PULL` or `PULLING` is inside
that window, not behind.

To stop waiting for a particular index:

```http
POST /v1alpha1/admin/indexes/products/actions/pull
```

That fetches the latest remote state right away and answers the resulting
status. Reach for it after a promotion or a bulk load rather than as a habit -
lowering `INDEXES_REFRESH_INTERVAL` is the way to make every node fresher, at
the cost of storage requests on every node on every interval.

A node that stays behind is a storage problem rather than an index problem.
The log says which request failed, keyed by `index` and `bucket`.

## Know which node is writing

`status.readOnly` is `false` on the indexer and `true` everywhere else. The
node that holds the role says so once, and the log is where a failover is
visible:

```
INFO  node=node-a-7f21 Acquired the indexer role
ERROR node=node-a-7f21 Giving up the indexer role, <reason>
```

`Giving up the indexer role` is worth alerting on. Losing it is not itself a
fault - a candidate that stops renewing hands over, which is the design - but
a node giving it up while it is still running is saying the storage stopped
answering, or that another node took the lease from under it.

A write sent to a node that is not the indexer answers `307` with the indexer
in `Location`, so `curl -i` against any node names the current writer.

## Keep the disk in hand

Without `INDEXES_DISK_MAX_SIZE`, a node keeps the files of every index it has
ever served. With it set, a sweep removes the coldest local copies - and only
copies the bucket fully holds, which is why two log lines matter more than the
number itself:

```
WARN  Index holds changes the remote never got, keeping its local copy
WARN  Local copies exceed the disk budget and nothing more can be removed
```

The first means a commit or definition never reached storage; the copy stays
whatever the bound says, because removing it would lose the only copy. Chase
the push failure above it in the log. The second means every remaining copy is
either in use or unpushed, and the bound cannot be met - the node needs more
disk, fewer indexes, or a shorter `INDEXES_DISK_MIN_IDLE`.

A search or write that raced an index being closed for room answers `503`, and
repeating it opens the index again. Steady `503`s mean `INDEXES_MAX_OPEN` is
below what the node is actually being asked for.

## Replace a node

A node in `object` mode holds nothing that is not in the bucket. Stop it,
start a new one with the same configuration, and it pulls back what it is
asked for - there is no volume to migrate and nothing to restore. A wiped node
is slow rather than broken while it refills.

Stopping a node cleanly matters for one thing only: an indexer hands its lease
back on shutdown, so another candidate takes over at once instead of after
`INDEXER_LEASE_DURATION`.

A node in `local` mode is the opposite in every respect - its directory is the
deployment, not a copy of it. See [Run on one node](run-on-one-node.md).

## Upgrade the engine

Nodes of different versions run against the same bucket, so upgrade them one
at a time. Two rules decide the order:

- Upgrade every node **before** writing a definition that uses something new.
  A definition records the features it needs, and a node that lacks one
  reports the index `UNSUPPORTED` rather than serving it wrongly - which is
  the safe outcome, but it is an outage for that index on that node.
- Reindex every index reporting `luceneCompatibility: "ENDING"` **before**
  upgrading across a Lucene major, while there is still a node that can read
  it. See [Survive Lucene upgrades](survive-lucene-upgrades.md).

Rolling back to an older build has the same shape from the other side: it can
read what it wrote, and reports `UNSUPPORTED` for anything the newer build
defined.

## Back up

The bucket is the deployment. Whatever it holds - indexes, the registry, the
keys - is what a new set of nodes comes up on, so backup means whatever your
object storage offers: versioning, replication, or a copy of the prefix.
Nothing on a node's disk needs backing up.

Two things to know about the bucket's contents:

- Deleting an index removes it from the registry, and the nodes remove their
  copies, but what the remote holds under it is left. Reclaiming that space is
  a job against the bucket.
- `REMOTE_STORAGE_PREFIX` is what keeps a shared bucket separable, and it is
  also what a backup or a restore is scoped by.

In `local` mode there is no bucket, and `LOCAL_STORAGE_DIRECTORY` holds the
only copy of the indexes and the keys both. It is backed up like any other
data directory, with the node stopped.

## Read the log

A line names the thing it is about as `key=value` after the message -
`index`, `generation`, `node`, `bucket`, `object` - so it is matched on the
index it concerns rather than on the wording of the message. [JSON
output](../reference/configuration.md#json-output) makes each of them a field
of its own instead, and leaves the message the sentence alone. The lines worth
routing somewhere:

| Line | Means |
|------|-------|
| `Giving up the indexer role` | This node stopped writing; something took the lease or the storage stopped answering |
| `Index holds changes the remote never got` | A push failed and the local copy is now the only copy |
| `Local copies exceed the disk budget` | The bound cannot be met by sweeping |
| `Index was created with Lucene …` | Compatibility is `ENDING` or already `UNREADABLE` |
| `Authentication is turned off` | The node answers every request as allowed everything - see [Secure a deployment](secure-a-deployment.md) |
| `Storing everything on this node's disk` | The node came up in `local` mode, which is worth noticing when it was meant for a bucket |

## Related

- [Admin API](../reference/admin-api.md) - the status shape and every state.
- [Configuration](../reference/configuration.md) - the intervals and bounds
  named here.
- [Run more than one node](run-multiple-nodes.md) - candidacy, failover and
  where writes go.
- [Architecture](../explanation/architecture.md) - why a node holds nothing
  worth backing up.
