# Configuration

Exofind is configured through environment variables. Configuration is read
through MicroProfile Config, so each variable is also available as the
corresponding dotted property (`REMOTE_STORAGE_URL` is
`remote.storage.url`), which is how tests and `application.properties`
set them.

## Storage

The mode decides where the indexes, the registry naming them and the keys
that reach them are kept. It is named rather than inferred from which other
variables are set, so a node meant for a cluster whose storage settings are
wrong refuses to start instead of coming up alone with a registry of its own.

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_STORAGE_MODE` | `object` to keep everything in an S3 compatible bucket that every node shares, `local` to keep everything on this node's disk | `local` |
| `LOCAL_STORAGE_DIRECTORY` | Directory this node writes in. In `object` mode it holds copies of the indexes; in `local` mode it holds the only copy there is, along with the registry and the keys | required |

`local` is for one node: a laptop, a container in a compose file, a test. It
needs nothing else running, but nothing is copied anywhere, no second node can
be added, no other node can take over, and losing the directory loses the
indexes and the keys together. A node started this way says so in its log.
`INDEXES_DISK_MAX_SIZE` frees nothing in this mode - see [Disk
use](#disk-use).

`INDEXER` defaults to true in `local` mode, because the one node there is has
to be the one that writes.

Only one node may run against a directory. It is claimed for as long as the
node runs, and a second node pointed at the same one refuses to start rather
than write over the first one's indexes. The claim relies on file locking, so
the directory belongs on a disk attached to the node - NFS and SMB implement
locking unreliably.

### Object storage

Read in `object` mode, and ignored in `local`.

| Variable | Description | Default |
|----------|-------------|---------|
| `REMOTE_STORAGE_URL` | URL of the S3 compatible storage | required |
| `REMOTE_STORAGE_ACCESS_KEY` | Access key | required |
| `REMOTE_STORAGE_SECRET_KEY` | Secret key | required |
| `REMOTE_STORAGE_REGION` | Region of the storage | none |
| `REMOTE_STORAGE_BUCKET` | Bucket the indexes are stored in | required |
| `REMOTE_STORAGE_PREFIX` | Key prefix within the bucket, for sharing a bucket with something else | none |

## Decompounding data

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_DECOMPOUND_DIRECTORY` | Directory holding the per-locale data that splits compound words, see [Analysis](analysis.md#compound-words). One folder per locale with `patterns.txt` and `words.txt`, each optionally gzipped. A locale whose folder is missing indexes its compounds whole | `decompound-data` under the working directory |

## Indexer role

| Variable | Description | Default |
|----------|-------------|---------|
| `INDEXER` | Whether this node may act as the indexer. Any number of candidates may run; they coordinate through a lease in the object storage and exactly one holds the role at a time, with another taking over when the holder stops or stalls | `false`, and `true` in `local` mode |
| `INDEXER_LEASE_DURATION` | How long the indexer role is held before it lapses without renewal, which is roughly how long a failover takes. Renewal happens at a third of this | `30s` |
| `NODE_ID` | Name this node holds the indexer lease under | hostname plus a random suffix |
| `NODE_ADDRESS` | Address this node serves writes on, recorded in the lease so other nodes can redirect writes to the indexer. Without it, writes to other nodes are refused instead of redirected | none |

An indexer relies on the storage enforcing conditional writes (`If-Match` on
`PUT`) to refuse a second writer instead of being corrupted by it. Amazon S3
and SeaweedFS enforce them; the node checks at startup and refuses to run as
the indexer against a storage that does not.

## Authentication

Keys live in the storage rather than here, so only what differs per node is
configuration. See [Authentication](auth.md) for the permissions a key holds
and the keys API.

| Variable | Description | Default |
|----------|-------------|---------|
| `EXOFIND_AUTH_MODE` | `keys` to check a credential on every request, `none` to check nothing and answer every request as allowed everything | `keys`, and `none` in dev mode |
| `EXOFIND_AUTH_ROOT_KEY` | A credential allowed everything, accepted by this node alone and stored nowhere. Either the key itself or `sha256:` and its hash. Used to create the first key and to recover from deleting the last one that could manage keys | none |
| `EXOFIND_AUTH_ANONYMOUS_KEY` | Id of the key requests carrying no credential are answered as. The key may only be granted `search`, or the node refuses to start. Without it such requests are refused | none |
| `EXOFIND_AUTH_REFRESH_INTERVAL` | How often a node re-reads the keys, which is how long revoking one can take to reach a node already holding it. A key a node has not seen is looked up right away, at most once per interval | `10s` |

A node in `keys` mode that can neither read the stored keys nor find a root key
of its own refuses to start - a node nobody can administer is worse than one
that does not come up. A node that named `object` mode but cannot reach the
storage has nowhere to keep keys and can only be reached with its root key; one
in `local` mode keeps them on disk like everything else.

## Index management

| Variable | Description | Default |
|----------|-------------|---------|
| `INDEXES_MAX_OPEN` | How many indexes are kept open at once | unbounded |
| `INDEXES_REFRESH_INTERVAL` | How often a node re-reads which indexes and generations the deployment holds and pulls the ones it holds open. Also how long promoting a generation takes to reach a node still answering from the previous one. An index a node has not seen is looked up right away, at most once per interval | `30s` |
| `INDEXES_REFRESH_CONCURRENCY` | How many indexes are refreshed at the same time | `4` |
| `INDEXES_CLOSE_GRACE_PERIOD` | How long an index evicted from the open set waits for in-flight use before closing | `10s` |

## Committing

The indexer commits on its own, so what is indexed becomes searchable without
anything asking for it. A commit is also a push, so these decide how long a
change takes to reach the storage - and a searching node sees it one
`INDEXES_REFRESH_INTERVAL` after that at worst. Committing much more often than
that interval costs requests against the storage without the other nodes seeing
anything sooner.

Either trigger is turned off by setting it to zero, and with both off an index
only commits when
[asked to](admin-api.md). Loading a dataset is still one commit at the end
rather than one per batch: raise `INDEXES_COMMIT_MAX_CHANGES` for the load, or
commit by hand with both triggers off.

| Variable | Description | Default |
|----------|-------------|---------|
| `INDEXES_COMMIT_MAX_CHANGES` | How many changed documents may be waiting before the index commits | `10000` |
| `INDEXES_COMMIT_MAX_INTERVAL` | How long the oldest waiting change may go uncommitted | `5s` |

A commit that fails is tried again, waiting twice as long before each attempt up
to a minute, and the changes stay counted meanwhile. Two failures are not
retried and give up what they were counting: another node having written the
storage first, where this node is about to pull the index over, and the index
having stopped being this node's to write.

## Disk use

Closing an index keeps its files, so without a bound the disk fills with every
index a node has ever served. With `INDEXES_DISK_MAX_SIZE` set, a periodic
sweep removes the local copies of the coldest indexes - ranked by how often
they are opened, with opens counting for half after every half-life - until
the total is a tenth under the bound. An index whose copy was removed stays
known and usable; asking for it pulls everything back from storage.

A copy is only removed when the storage holds everything it does. One with a
commit or definition that never reached the storage is kept and warned about,
whatever the bound says - which also means the bound removes nothing in `local`
mode, where every copy is the only one there is. A node started that way with a
bound set says so in its log.

| Variable | Description | Default |
|----------|-------------|---------|
| `INDEXES_DISK_MAX_SIZE` | How much disk the local copies may take together, as bytes with an optional `K`, `M`, `G` or `T` suffix (binary multiples) | unbounded |
| `INDEXES_DISK_MIN_IDLE` | How recently a copy has to have been used to be kept regardless of the bound | `24h` |
| `INDEXES_DISK_HALF_LIFE` | How long an index has to go unopened for its opens to count half | `168h` |
| `INDEXES_DISK_SWEEP_INTERVAL` | How often disk use is checked against the bound | `1h` |

## Search

| Variable | Description | Default |
|----------|-------------|---------|
| `SEARCH_MAX_PAGE_DEPTH` | How deep into the results offset paging may reach - the deepest result a page may end at. Requests past it are refused with `search:page:too_deep`, and numbered pages past it are never offered. Following `next`/`previous` cursors is not capped | `10000` |
