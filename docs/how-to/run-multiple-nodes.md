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

## Share the writing, and make it survive failures

Set `INDEXER=true` on two or three nodes. That makes them *candidates*: they
divide the indexes among themselves through a leadership table in the
bucket, each index held by exactly one of them at a time. A candidate that
stops renewing hands its indexes to the others, so a second candidate is how
writing survives a node dying - and each candidate writes its own share, so
more candidates also spread the write work. Writes to a single index never
spread: one node writes an index however many candidates run, so past two or
three the benefit thins out unless the deployment holds many busy indexes.

- `INDEXER_LEASE_DURATION` (30s) is how long an index is held without
  renewal - roughly how long a failover takes. Renewal happens at a third
  of it.
- `NODE_ID` names the node in the table; the default of hostname plus a
  random suffix is usually right.
- Set `NODE_ADDRESS` on every candidate to the address it serves writes on.
  It is recorded in the table, and is what lets other nodes forward writes
  to whichever candidate holds an index - without it, writes sent to other
  nodes are refused with `409`. The address only has to be reachable from
  the other nodes, not from clients.

The table only decides who tries to write. What keeps a stale writer from
corrupting anything is the storage refusing its pushes - see
[Synchronization](../explanation/synchronization.md). That protection
requires the storage to enforce conditional writes; the node checks at
startup and refuses to run as a candidate against storage that does not.

Upgrade every candidate together when a release changes how they
coordinate: candidates on different versions do not corrupt anything - the
conditional writes hold regardless - but they can contest each other's
indexes and churn instead of settling.

## Send writes anywhere

Clients do not need to know which node writes which index. A write that
reaches another node is forwarded to the holder - same request, same
credential - and answered with whatever the holder answered, so the client
never sees which node did the work. An index nothing holds yet - just
created, or its holder just died - is claimed on the spot by the candidate
the write reaches. Only when no candidate is running (or none set a
`NODE_ADDRESS`) does a write fail, with `409 Conflict` and the code
`indexer:unavailable`; a holder that cannot be reached answers `502` with
`indexer:unreachable`.

An operator who does want to know asks any node:
`GET /v1alpha1/admin/indexers` lists which node writes which index - see
[Operate a deployment](operate-a-deployment.md#know-which-node-is-writing).

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
