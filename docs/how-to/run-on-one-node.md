# Run on one node

A node in `local` mode keeps everything on its own disk - the indexes, the
registry naming them and the keys that reach them. Nothing else has to be
running, which makes it what you want on a laptop, in a compose file beside
the application it serves, or in a test.

It is also the default, so a node started with no storage settings at all runs
this way. Read [What you give up](#what-you-give-up) before putting one
somewhere you would mind losing.

## Start one

```shell
mise run image
docker compose -f docker-compose.local.yml up -d
```

That runs the node on port 8080 with a volume behind it. Everything below
assumes the root key from the compose file; replace it with one of your own
before the node is reachable by anything but you.

Define an index, put a document in and search for it - the node holds every
index on its own, so writes work without asking:

```shell
curl -X PUT localhost:8080/v1alpha1/admin/indexes/books \
  -H "Authorization: Bearer exok_change_me" \
  -H "Content-Type: application/json" \
  -d '{"fields": {
        "id": {"type": "string", "primaryKey": true, "required": true},
        "title": {"type": "string", "matching": {}}
      }}'
```

Without Docker, the same node runs from a checkout:

```shell
EXOFIND_STORAGE_MODE=local \
LOCAL_STORAGE_DIRECTORY=data/indexes \
EXOFIND_AUTH_ROOT_KEY=exok_change_me \
mise run run
```

## Hand out keys

Keys work as they do anywhere else - create the first one with the root key
and give each thing that holds one only what it needs:

```shell
curl -X POST localhost:8080/v1alpha1/admin/keys \
  -H "Authorization: Bearer exok_change_me" \
  -H "Content-Type: application/json" \
  -d '{"description": "the app", "grants": [
        {"role": "reader", "indexes": ["books"]}
      ]}'
```

They are written to a file beside the indexes, readable by the user running
the node alone. [Secure a deployment](secure-a-deployment.md) covers the rest,
all of which applies here.

## Back it up

The volume is the deployment. Copy it while the node is stopped, or take a
snapshot of the file system underneath it - a copy made while the node is
writing may catch an index mid-commit.

## What you give up

Everything a second node would have given you:

- **No second copy.** Losing the volume loses the indexes and the keys
  together, and reindexing from the source data is the only way back.
- **No failover.** The node is the deployment; while it is down there is
  nothing answering.
- **No adding a node later without moving.** A second node needs a storage
  both can reach, so growing means switching to `object` mode and reindexing
  into it.
- **One node per directory.** The directory is claimed for as long as the node
  runs, and a second node pointed at it refuses to start. Keep it on a disk
  attached to the node - the claim is a file lock, and NFS and SMB implement
  those unreliably.
- **`INDEXES_DISK_MAX_SIZE` frees nothing.** The sweep only removes copies the
  storage already holds, and here there is no storage and no copies.

A node started this way says so in its log, once, at startup.

## Move to object storage

There is no migration: the indexes are Lucene files a node holds, not
something the engine can hand over. Point a node at a bucket with
`EXOFIND_STORAGE_MODE=object` and the settings in
[Configuration](../reference/configuration.md#object-storage), define the
indexes again, and load the documents again from wherever they came from.
[Run more than one node](run-multiple-nodes.md) covers what to set from there.

Doing this later is why the mode is named rather than guessed at: a node that
was meant to join a bucket and got a variable wrong would otherwise come up
alone, with a registry of its own, and look like it was working.
