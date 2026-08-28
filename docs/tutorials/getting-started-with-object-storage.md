# Getting started with object storage

In this tutorial, you run an Exofind node against an S3-compatible object
storage bucket using Docker Compose and SeaweedFS. You create an API key, define
an index, add documents, and verify persistence by deleting the node's local
disk and recovering the index from the bucket. At the end, you have a working
node backed by object storage, with the index and credentials stored in the
bucket.

## Prerequisites

Before you begin, ensure you have the following tools installed:

- Docker, with Docker Compose
- `curl`

**Note:** Exofind is experimental and its API is `v1alpha1`. It can change
without backward compatibility.

## 1. Creating the configuration files

In an empty directory, create two configuration files: `s3-config.json` and
`docker-compose.yml`.

Create `s3-config.json` to define the credentials SeaweedFS uses for its S3 API:

```json
{
  "identities": [
    {
      "name": "exofind",
      "credentials": [
        { "accessKey": "exofind", "secretKey": "exofind123" }
      ],
      "actions": ["Admin", "Read", "Write", "List", "Tagging"]
    }
  ]
}
```

Create `docker-compose.yml` to define the SeaweedFS and Exofind services:

```yaml
services:
  seaweedfs:
    image: chrislusf/seaweedfs:4.41
    container_name: seaweedfs
    ports:
      # The S3 API, for reaching the bucket from the host
      - "9000:8333"
      # The filer UI, for browsing what is in the bucket
      - "8888:8888"
    volumes:
      - seaweedfs-data:/data
      - ./s3-config.json:/etc/seaweedfs/s3-config.json:ro
    command: server -dir=/data -s3 -s3.config=/etc/seaweedfs/s3-config.json

  exofind:
    image: ghcr.io/levelfourab/exofind:main-latest
    container_name: exofind
    depends_on:
      - seaweedfs
    ports:
      - "8080:8080"
    environment:
      EXOFIND_STORAGE_MODE: object
      REMOTE_STORAGE_URL: http://seaweedfs:8333
      REMOTE_STORAGE_ACCESS_KEY: exofind
      REMOTE_STORAGE_SECRET_KEY: exofind123
      REMOTE_STORAGE_BUCKET: exofind
      INDEXER: "true"
      EXOFIND_AUTH_ROOT_KEY: exok_tutorial
    volumes:
      - exofind-data:/data

volumes:
  seaweedfs-data:
  exofind-data:
```

The Compose file sets the following environment variables on the `exofind`
service:

- `EXOFIND_STORAGE_MODE: object` stores the indexes, the index registry, and
  authentication keys in the bucket. The mode must be set explicitly; the node
  refuses to start if storage settings are invalid.
- `REMOTE_STORAGE_URL: http://seaweedfs:8333` points to the SeaweedFS S3
  endpoint inside the Compose network. The host reaches the same endpoint on
  `localhost:9000`.
- `REMOTE_STORAGE_ACCESS_KEY` and `REMOTE_STORAGE_SECRET_KEY` provide the S3
  credentials defined in `s3-config.json`.
- `REMOTE_STORAGE_BUCKET: exofind` specifies the bucket name where indexes are
  stored.
- `INDEXER: "true"` marks this node as a candidate for writing indexes. In
  `object` mode, `INDEXER` defaults to `false`. Search-only nodes do not need
  this setting, but writer nodes require it.
- `EXOFIND_AUTH_ROOT_KEY: exok_tutorial` sets the initial per-node
  administrative credential.
- `exofind-data` mounted at `/data` holds the node's local copies of the
  indexes, not the only copy. `LOCAL_STORAGE_DIRECTORY` defaults to `/data` in
  the image.

## 2. Starting the storage and creating the bucket

The bucket must exist before the Exofind node starts. Start SeaweedFS:

```shell
docker compose up -d seaweedfs
```

Create the `exofind` bucket in SeaweedFS:

```shell
echo "s3.bucket.create -name exofind" | docker compose exec -T seaweedfs weed shell
```

SeaweedFS provides a filer web UI at [http://localhost:8888](http://localhost:8888).
After the bucket is created, you can browse bucket contents at
`http://localhost:8888/buckets/exofind/`.

## 3. Starting the node

Start the Exofind node:

```shell
docker compose up -d exofind
```

Verify that the node is ready:

```shell
curl http://localhost:8080/q/health/ready
```

The endpoint responds without requiring an API key once the node reads the
index registry from the bucket.

At startup, the node verifies that the storage backend enforces conditional
writes (`If-Match` on `PUT`). It refuses to run as an indexer against storage
that does not support them, preventing concurrent writers from corrupting an
index. Both SeaweedFS and Amazon S3 enforce conditional writes.

## 4. Creating an API key

Create an administrative API key with permissions across all indexes:

```shell
curl -X POST http://localhost:8080/v1alpha1/admin/keys \
  -H 'Authorization: Bearer exok_tutorial' \
  -H 'Content-Type: application/json' \
  -d '{
    "description": "the tutorial",
    "grants": [ { "role": "admin", "indexes": ["*"] } ]
  }'
```

The server returns a `201 Created` response containing the generated credential
and key metadata:

```json
{
  "credential": "exok_4ff6b760264c1918_ePQcdT1O9HSATZoXfDbT8hhHGsP9VpZH",
  "key": {
    "id": "4ff6b760264c1918",
    "description": "the tutorial",
    "grants": [ { "permissions": ["..."], "indexes": ["*"] } ],
    "createdAt": "2026-08-16T12:09:33.198275Z"
  }
}
```

In `object` mode, the generated API key is stored in the bucket. Any node that
reads from the same bucket accepts this key. Revoking a key removes it across all
nodes without redeploying. The root key configured via `EXOFIND_AUTH_ROOT_KEY` is
the only node-local credential: it is never stored in the bucket and cannot be
listed or revoked through the API. For details on key roles, see
[Getting started](getting-started.md#2-creating-an-api-key).

Save the credential in an environment variable in your shell, replacing the
example string with the `credential` value from your response:

```shell
export EXOFIND_KEY="exok_4ff6b760264c1918_ePQcdT1O9HSATZoXfDbT8hhHGsP9VpZH"
```

## 5. Defining an index

Send a definition for a new index named `books`:

```shell
curl -i -X PUT http://localhost:8080/v1alpha1/admin/indexes/books \
  -H "Authorization: Bearer $EXOFIND_KEY" \
  -H 'Content-Type: application/json' \
  -d '{
    "fields": {
      "id": { "type": "string", "primaryKey": true, "required": true },
      "title": { "type": "string", "matching": {}, "sort": {} },
      "published": { "type": "boolean", "filter": {} }
    }
  }'
```

The server returns a `201 Created` response with the active definition, the
index status, and an `ETag` header:

```json
{
  "name": "books",
  "version": "9f2c1a0b3d4e5f60",
  "definition": { "...": "as sent" },
  "status": { "state": "USABLE", "readOnly": false, "...": "..." }
}
```

The index definition is saved to the bucket and is visible in the filer UI at
`http://localhost:8888/buckets/exofind/`.

## 6. Indexing documents and committing

Add documents to the `books` index:

```shell
curl -X POST http://localhost:8080/v1alpha1/indexes/books/documents \
  -H "Authorization: Bearer $EXOFIND_KEY" \
  -H 'Content-Type: application/json' \
  -d '{
    "documents": [
      { "id": "1", "title": "Silent Spring", "published": true },
      { "id": "2", "title": "Spring Cleaning", "published": false }
    ]
  }'
```

The server returns the number of indexed documents:

```json
{"indexed": 2}
```

Commit the pending changes:

```shell
curl -X POST http://localhost:8080/v1alpha1/admin/indexes/books/actions/commit \
  -H "Authorization: Bearer $EXOFIND_KEY"
```

A commit writes the documents into a Lucene commit and uploads that commit, the
definition, and the manifest to the bucket. Before the commit, changes exist only
on the node that received them. After the commit, changes are searchable locally
and available to every other node that reads from the bucket. The node also
commits automatically after 10 000 changes or 5 seconds, whichever comes first.

## 7. Searching the index

Search for documents matching the text `spring`:

```shell
curl http://localhost:8080/v1alpha1/indexes/books/search \
  -H "Authorization: Bearer $EXOFIND_KEY" \
  -H 'Content-Type: application/json' \
  -d '{ "query": [ { "type": "text", "text": "spring" } ] }'
```

Both documents match because `title` is configured for text matching:

```json
{
  "hits": [
    { "id": "1", "score": 8.42, "document": { "title": "Silent Spring", "published": true } }
  ],
  "total": { "count": 2, "exact": true },
  "page": { "limit": 10, "offset": 0 },
  "tookMs": 7.412
}
```

Run a search that filters on the `published` field:

```shell
curl http://localhost:8080/v1alpha1/indexes/books/search \
  -H "Authorization: Bearer $EXOFIND_KEY" \
  -H 'Content-Type: application/json' \
  -d '{ "query": [
        { "type": "text", "text": "spring" },
        { "field": "published", "match": { "value": true } }
      ] }'
```

Only the published book matches the query.

## 8. Recovering the index after deleting local storage

In `object` mode, the bucket is the source of truth. The node holds only a local
copy that it can rebuild at any time.

Stop and remove the Exofind container, and delete its local volume:

```shell
docker compose rm -sf exofind
docker volume rm exofind-data
```

Start the node again:

```shell
docker compose up -d exofind
```

Run the search request again using the same API key:

```shell
curl http://localhost:8080/v1alpha1/indexes/books/search \
  -H "Authorization: Bearer $EXOFIND_KEY" \
  -H 'Content-Type: application/json' \
  -d '{ "query": [ { "type": "text", "text": "spring" } ] }'
```

The search succeeds and returns the indexed documents. The new node read the
registry from the bucket, pulled the index data, and accepted the API key
because keys are stored in the bucket.

Any additional node configured with the same storage settings operates the same
way. Search nodes that are not indexer candidates discover committed changes
within `INDEXES_REFRESH_INTERVAL` (`30s` by default).

## 9. Cleaning up

To stop all services and remove all containers and volumes, including the
SeaweedFS bucket:

```shell
docker compose down -v
```

Removing the volumes with `-v` deletes the bucket data. Without `-v`, the bucket
and its indexes persist across restarts.

## Using Amazon S3 instead of SeaweedFS

To use Amazon S3 instead of SeaweedFS, update the `exofind` environment
variables in `docker-compose.yml` and remove the `seaweedfs` service:

- Set `REMOTE_STORAGE_URL` to your S3 endpoint URL.
- Set `REMOTE_STORAGE_ACCESS_KEY` and `REMOTE_STORAGE_SECRET_KEY` to your AWS
  credentials.
- Set `REMOTE_STORAGE_BUCKET` to your S3 bucket name.
- Optionally set `REMOTE_STORAGE_REGION` to your AWS region.
- Optionally set `REMOTE_STORAGE_PREFIX` if sharing the bucket with other
  services.

The target storage must enforce conditional writes. Amazon S3 enforces
conditional writes.

## Where to go next

You have a running Exofind node backed by S3-compatible object storage, with
verified index recovery from the bucket.

For more information on multi-node deployments, security, and architecture, see
the following documents:

- [Run more than one node](../how-to/run-multiple-nodes.md): configure indexer
  candidacy, failover, and write routing across multiple nodes.
- [Deploy on Kubernetes](../how-to/deploy-on-kubernetes.md): set up search-only
  and writer node pools on Kubernetes.
- [Secure a deployment](../how-to/secure-a-deployment.md): manage client API
  keys and credential rotation.
- [Operate a deployment](../how-to/operate-a-deployment.md): monitor node
  status, track lag, and interpret log output.
- [Architecture](../explanation/architecture.md): learn why object storage is
  the source of truth and how nodes coordinate.
- [Synchronization](../explanation/synchronization.md): understand manifests,
  epochs, and the leadership table.
- [Configuration](../reference/configuration.md): explore all supported
  environment variables and options.
