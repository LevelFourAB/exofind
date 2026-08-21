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
  It is recorded in the lease, and is what lets other nodes redirect writes
  to the current indexer - without it, writes sent to other nodes are
  refused with `409` instead of redirected with `307`.

The lease only decides who tries to write. What keeps a stale writer from
corrupting anything is the storage refusing its pushes - see
[Synchronization](../explanation/synchronization.md). That protection
requires the storage to enforce conditional writes; the node checks at
startup and refuses to run as an indexer against storage that does not.

## Send writes anywhere

Clients do not need to know which node is the indexer. Send definition
changes and commit actions to any node and follow redirects - the redirect
repeats the request as it was. Only when no indexer is running (or the
indexer set no `NODE_ADDRESS`) does a write fail, with `409 Conflict`.

## Bound what a node holds

A node opens every index it is asked for and keeps it synchronized. On a
node that serves many indexes, `INDEXES_MAX_OPEN` bounds how many are kept
open at once - the least recently used are closed to make room, and open
again when next asked for.
