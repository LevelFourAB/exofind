# Getting started

In this tutorial, you run an Exofind node in a Docker container using local disk
storage, create an API key, define an index, add documents, and run search
queries. You do not need to check out the repository or build the application.
At the end, you have a working node running in a container, an API key, and an
index with searchable documents stored in a Docker volume.

## Prerequisites

Before you begin, ensure you have the following tools installed:

- Docker
- `curl`

**Note:** Exofind is experimental and its API is `v1alpha1`. It can change
without backward compatibility.

## Starting the container

The Exofind container image is published to GitHub Packages. The `main-latest`
tag represents the newest development build. For production deployments, pin a
release version such as `0.1.0` or a specific commit tag.

The container image enforces authentication by default (`EXOFIND_AUTH_MODE=keys`).
When starting a node with no stored keys, you must set `EXOFIND_AUTH_ROOT_KEY` to
provide an initial administrative credential.

Start the Exofind container:

```shell
docker run -d --name exofind -p 8080:8080 \
  -v exofind-data:/data \
  -e EXOFIND_AUTH_ROOT_KEY=exok_tutorial \
  ghcr.io/levelfourab/exofind:main-latest
```

The command sets the following options:

- `-p 8080:8080` publishes the HTTP API port to the host.
- `-v exofind-data:/data` mounts a named volume to the default storage
  directory `/data`. The container process runs as uid `1001`, which owns
  `/data` inside the image.
- `-e EXOFIND_AUTH_ROOT_KEY=exok_tutorial` sets the root key for initial
  administration. Replace this value with a randomly generated secret before
  exposing the node over a network.
- `EXOFIND_STORAGE_MODE` defaults to `local`. In this mode, the node stores all
  indexes, the index registry, and keys in `/data`.

Verify that the node is ready to accept requests:

```shell
curl http://localhost:8080/q/health/ready
```

The readiness endpoint answers without requiring an API key once the node reads
its index registry.

## Creating an API key

The root key allows you to bootstrap real API keys. Create an administrative API
key that has permissions across all indexes:

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

**Note:** The full `credential` value is returned only once upon creation. Key
secrets are stored only as hashes. A lost credential cannot be recovered and
must be replaced.

Save the credential in an environment variable in your shell, replacing the
example string with the `credential` value from your response:

```shell
export EXOFIND_KEY="exok_4ff6b760264c1918_ePQcdT1O9HSATZoXfDbT8hhHGsP9VpZH"
```

## Defining an index

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

The server returns a `201 Created` response containing the active definition,
the index status, and an `ETag` header:

```json
{
  "name": "books",
  "version": "9f2c1a0b3d4e5f60",
  "definition": { "...": "as sent" },
  "status": { "state": "USABLE", "readOnly": false, "...": "..." }
}
```

Each field defines its allowed operations: `title` enables matching and sorting,
`published` enables filtering, and `id` serves as the primary key.

## Indexing documents

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

Each document contains an `id` field defined as the primary key. Indexing a
document with an existing `id` replaces the previous document under that key.

The node automatically commits changes after 10 000 operations or 5 seconds. To
make documents searchable immediately, send a commit request:

```shell
curl -X POST http://localhost:8080/v1alpha1/admin/indexes/books/actions/commit \
  -H "Authorization: Bearer $EXOFIND_KEY"
```

## Searching the index

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

Only the published document matches the query.

To test field validation, run a query with an operation not enabled in the
schema:

```shell
curl http://localhost:8080/v1alpha1/indexes/books/search \
  -H "Authorization: Bearer $EXOFIND_KEY" \
  -H 'Content-Type: application/json' \
  -d '{ "query": [ { "field": "title", "match": { "value": "x" } } ] }'
```

Because the index definition does not enable filtering on `title`, the server
rejects the query with the error `index:query:usage_not_enabled`.

## Managing data persistence and cleaning up

Stop and restart the container to verify that data persists:

```shell
docker stop exofind
docker start exofind
```

The node retains the index definitions, documents, and API keys because they are
stored in the `exofind-data` volume.

To delete the container and all associated data:

```shell
docker rm -f exofind
docker volume rm exofind-data
```

In `local` mode, no second copy of the data exists. Removing the volume
permanently deletes the indexes, the registry, and all stored API keys.

## Where to go next

You now have a running Exofind container using local storage, an API key, and an
index with verified search results.

For more information on specific tasks and deployment patterns, see the
following documents:

- [Run on one node](../how-to/run-on-one-node.md): learn about single-node local
  storage trade-offs and volume backups.
- [Define an index](../how-to/define-an-index.md): learn about field types,
  wildcards, and document source settings.
- [Index documents](../how-to/index-documents.md): load larger datasets and
  maintain a continuous feed.
- [Search an index](../how-to/search-an-index.md): configure queries with
  filters, facets, sorting, and highlighting.
- [Secure a deployment](../how-to/secure-a-deployment.md): manage client API
  keys and credential rotation.
- [Configuration](../reference/configuration.md): explore all supported
  environment variables and options.
- [Getting started with object storage](getting-started-with-object-storage.md):
  run the same walkthrough against a bucket, which is what more than one node
  needs.
- [Run more than one node](../how-to/run-multiple-nodes.md): set up a
  multi-node cluster using shared object storage. There is no migration from
  `local` mode: you define the indexes again and load the documents again.
