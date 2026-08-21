# Run more than one node

Nodes coordinate through the object storage alone - they never talk to each
other. Adding a node is starting another process against the same bucket.

## Add search capacity

Start any number of nodes with the same remote storage configuration and
`INDEXER` left off. Each finds the indexes in the bucket on its own, pulls
them, and answers searches from its local copy - a search runs on whichever
node receives it, so put the nodes behind any load balancer. Point its check
at `/q/health/ready`, which a node answers without a key and only once it has
read the registry - see [Ask whether a node is
up](operate-a-deployment.md#ask-whether-a-node-is-up).

A reader is as current as its last pull, on the interval set by
`INDEXES_REFRESH_INTERVAL` (30s by default). Lower it for fresher reads at
the cost of more storage traffic, or hit the `pull` action to take the
latest state of an index right away.

## Make the writer survive failures

Set `INDEXER=true` on two or three nodes. That makes them *candidates*: they
compete for the indexer role through a lease in the bucket, exactly one
holds it at a time, and when the holder stops renewing another takes over.
There is no benefit to many candidates beyond surviving the loss of a few.

- `INDEXER_LEASE_DURATION` (30s) is how long the role is held without
  renewal - roughly how long a failover takes. Renewal happens at a third
  of it.
- `NODE_ID` names the node in the lease; the default of hostname plus a
  random suffix is usually right.
- Set `NODE_ADDRESS` on every candidate to the address it serves writes on.
  It is recorded in the lease, and is what lets other nodes forward writes
  to the current indexer - without it, writes sent to other nodes are
  refused with `409`. The address only has to be reachable from the other
  nodes, not from clients.

The lease only decides who tries to write. What keeps a stale writer from
corrupting anything is the storage refusing its pushes - see
[Synchronization](../explanation/synchronization.md). That protection
requires the storage to enforce conditional writes; the node checks at
startup and refuses to run as an indexer against storage that does not.

## Send writes anywhere

Clients do not need to know which node is the indexer. A write that reaches
another node is forwarded to the indexer by the node itself - same request,
same credential - and answered with whatever the indexer answered, so the
client never sees which node did the work. Only when no indexer is running
(or the indexer set no `NODE_ADDRESS`) does a write fail, with
`409 Conflict` and the code `indexer:unavailable`; an indexer that cannot be
reached answers `502` with `indexer:unreachable`.

## Bound what a node holds

A node opens every index it is asked for and keeps it synchronized. On a
node that serves many indexes, `INDEXES_MAX_OPEN` bounds how many are kept
open at once - the least recently used are closed to make room, and open
again when next asked for.

## Related

- [Deploy on Kubernetes](deploy-on-kubernetes.md) - this deployment as
  manifests, with the candidates in a pool of their own.
- [Operate a deployment](operate-a-deployment.md) - what to check once it is
  running.
