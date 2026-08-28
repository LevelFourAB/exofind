# Getting started

In this tutorial, you run an Exofind node on your machine against local
object storage, define an index, and run search queries against the index. At the
end, you have a working system with object storage holding the index, a node
acting as the indexer, and usable admin and search APIs.

## Prerequisites

Before you begin, ensure you have the following tools installed:

- [mise](https://mise.jdx.dev/)
- Docker

**Note:** Exofind is experimental and its API is `v1alpha1`. It can change
without backward compatibility.

## 1. Installing the toolchain

From a checkout of the repository, install the required tools:

```shell
mise install
```

This installs the Java and Maven versions that the project expects. With mise
activated in your shell, they are on the `PATH` whenever you are inside the
project directory.

## 2. Starting object storage and creating a bucket

Start the local object storage service:

```shell
mise run storage
```

[SeaweedFS](https://github.com/seaweedfs/seaweedfs) serves an S3 API on
`localhost:9000` with the access key `exofind` and secret key `exofind123`. A
file browser is available at `localhost:8888`.

Create the bucket where indexes are stored:

```shell
echo "s3.bucket.create -name exofind" | docker exec -i seaweedfs weed shell
```

## 3. Running a node

Start the node in development mode, configured to use object storage and to act
as the indexer:

```shell
EXOFIND_STORAGE_MODE=object \
REMOTE_STORAGE_URL=http://localhost:9000 \
REMOTE_STORAGE_ACCESS_KEY=exofind \
REMOTE_STORAGE_SECRET_KEY=exofind123 \
REMOTE_STORAGE_BUCKET=exofind \
LOCAL_STORAGE_DIRECTORY=data/indexes \
INDEXER=true \
mise run dev
```

The environment variables configure the following behavior:

- `EXOFIND_STORAGE_MODE=object` directs the node to use the bucket.
- `INDEXER=true` makes this node a candidate for writing indexes. Because it is
  the only candidate, it claims every index through a leadership table in the
  bucket.

Development mode disables credential checks. Requests in this tutorial do not
require an `Authorization` header.

## 4. Defining an index

Send a definition for a new index named `books`:

```shell
curl -i -X PUT http://localhost:8080/v1alpha1/admin/indexes/books \
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
and `published` enables filtering.

You can view the definition in the file browser at
[localhost:8888/buckets/exofind/](http://localhost:8888/buckets/exofind/).

## 5. Indexing documents

Add documents through the index endpoint:

```shell
curl -X POST http://localhost:8080/v1alpha1/indexes/books/documents \
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

Commit the index to make the documents searchable:

```shell
curl -X POST http://localhost:8080/v1alpha1/admin/indexes/books/actions/commit
```

The commit action writes the documents to a Lucene commit and uploads the
commit, definition, and manifest to the bucket. This makes the documents
searchable on this node and available to other nodes.

Each document includes an `id` field defined as the primary key. Sending a
document with an existing `id` replaces the previous document.

## 6. Searching the index

Search for documents matching the text `spring`:

```shell
curl http://localhost:8080/v1alpha1/indexes/books/search \
  -H 'Content-Type: application/json' \
  -d '{ "query": [ { "type": "text", "text": "spring" } ] }'
```

Both documents match because `title` is configured for text matching.

Run a search with a filter on the `published` field:

```shell
curl http://localhost:8080/v1alpha1/indexes/books/search \
  -H 'Content-Type: application/json' \
  -d '{ "query": [
        { "type": "text", "text": "spring" },
        { "field": "published", "match": { "value": true } }
      ] }'
```

Only the published book matches the query.

To test field validation, run a query with an unsupported operation:

```shell
curl http://localhost:8080/v1alpha1/indexes/books/search \
  -H 'Content-Type: application/json' \
  -d '{ "query": [ { "field": "title", "match": { "value": "x" } } ] }'
```

Because the index definition does not enable filtering on `title`, the server
rejects the query with the error `index:query:usage_not_enabled`.

## Where to go next

You now have a running Exofind node backed by local object storage, an index with
documents, and verified search results.

For more details on specific tasks and concepts, see the following documents:

- [Define an index](../how-to/define-an-index.md): learn about field types,
  wildcards, document source, and tie breakers.
- [Index documents](../how-to/index-documents.md): load larger datasets and
  maintain a continuous feed.
- [Search an index](../how-to/search-an-index.md): configure queries with
  filters, facets, sorting, and highlighting.
- [Run more than one node](../how-to/run-multiple-nodes.md): set up a
  multi-node cluster.
- [Run on one node](../how-to/run-on-one-node.md): run a single node without
  object storage.
- [Secure a deployment](../how-to/secure-a-deployment.md): configure
  authentication keys and tokens.
- [Architecture](../explanation/architecture.md): learn about Exofind system
  design and storage coordination.
