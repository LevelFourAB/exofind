# Running on one node

This guide shows you how to run a node in `local` mode on a single machine. In `local` mode, the node stores indexes, the registry, and keys on its local disk without external dependencies. Use `local` mode for development on a laptop, in a Docker Compose file beside an application, or in tests.

`local` mode is the default when you start a node without storage settings. Read [What you give up](#what-you-give-up) before deploying a node with data you cannot afford to lose.

## Prerequisites

Before you start, ensure you have the following:

- `mise` installed.
- Docker installed, if you run the node in a container.

## Starting the node

1. Start the node with Docker Compose or from a local checkout:

   - If you use Docker, run the following commands:

     ```shell
     mise run image
     docker compose -f docker-compose.local.yml up -d
     ```

     This command runs the node on port 8080 backed by a storage volume. Replace the root key `exok_change_me` from `docker-compose.local.yml` with your own key before exposing the node to other clients.

   - If you run without Docker from a repository checkout, run the following command:

     ```shell
     EXOFIND_STORAGE_MODE=local \
     LOCAL_STORAGE_DIRECTORY=data/indexes \
     EXOFIND_AUTH_ROOT_KEY=exok_change_me \
     mise run run
     ```

2. Create an API key for your application by running the following command:

   ```shell
   curl -X POST localhost:8080/v1alpha1/admin/keys \
     -H "Authorization: Bearer exok_change_me" \
     -H "Content-Type: application/json" \
     -d '{"description": "the app", "grants": [
           {"role": "reader", "indexes": ["books"]}
         ]}'
   ```

   The node writes keys to a file beside the indexes. The file is readable only by the user running the node. For more information, see [Secure a deployment](secure-a-deployment.md).

## Confirming the result

To confirm that the node is running and accepting writes, define an index:

```shell
curl -X PUT localhost:8080/v1alpha1/admin/indexes/books \
  -H "Authorization: Bearer exok_change_me" \
  -H "Content-Type: application/json" \
  -d '{"fields": {
        "id": {"type": "string", "primaryKey": true, "required": true},
        "title": {"type": "string", "matching": {}}
      }}'
```

The node holds every index locally and processes writes directly.

## Backing up the deployment

To back up the deployment, use one of the following methods:

- Stop the node and copy the storage volume or directory.
- Take a snapshot of the underlying file system.

**Note:** Do not copy the directory while the node is writing. A copy made during writes can capture an index mid-commit.

## What you give up

Running a single node in `local` mode has the following limitations compared to a multi-node deployment:

- **No second copy:** If you lose the volume, you lose both the indexes and the keys. You must reindex from the source data to recover.
- **No failover:** The node is the deployment. If the node is down, nothing answers requests.
- **No adding a node later without moving:** A second node requires storage that both nodes can access. Scaling out requires switching to `object` mode and reindexing into it.
- **One node per directory:** The running node locks the directory. A second node pointed at the same directory fails to start. Keep the directory on a disk attached directly to the node. File locks on Network File System (NFS) and Server Message Block (SMB) are unreliable.
- **`INDEXES_DISK_MAX_SIZE` frees nothing:** The disk sweep removes only copies that object storage already holds. In `local` mode, there is no remote storage and no secondary copies.

A node started in `local` mode logs this status once at startup.

## Move to object storage

There is no direct migration path from `local` mode to `object` mode. Indexes are Lucene files stored locally on the node and cannot be transferred automatically.

To switch to object storage:

1. Point a node at a bucket with `EXOFIND_STORAGE_MODE=object` and the settings in [Configuration](../reference/configuration.md#object-storage).
2. Define the indexes again.
3. Load the documents again from their original data source.

For more information, see [Run more than one node](run-multiple-nodes.md), and
[Architecture](../explanation/architecture.md) for why the storage mode is
configured explicitly rather than guessed.
