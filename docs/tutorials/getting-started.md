# Getting started

In this tutorial you will run an Exofind node on your machine against local
object storage, define an index, and make a first search. At the end you will
have seen the whole shape of the system: object storage holding the index,
a node acting as the indexer, and the admin and search APIs.

You need [mise](https://mise.jdx.dev/) and Docker.

> Exofind is experimental and its API is `v1alpha1` - it changes without
> keeping compatibility.

## 1. Install the toolchain

From a checkout of the repository:

```shell
mise install
```

This installs the Java and Maven versions the project expects. With mise
activated in your shell they are on the `PATH` whenever you are inside the
project.

## 2. Start object storage and create a bucket

```shell
mise run storage
```

[SeaweedFS](https://github.com/seaweedfs/seaweedfs) is now serving an S3 API
on `localhost:9000` (access key `exofind`, secret `exofind123`), with a file
browser on `localhost:8888`. Create the bucket the indexes will live in:

```shell
echo "s3.bucket.create -name exofind" | docker exec -i seaweedfs weed shell
```

## 3. Run a node

Start the node in dev mode, pointed at the storage and allowed to act as the
indexer:

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

`EXOFIND_STORAGE_MODE=object` is what makes the node use the bucket. Left out,
it keeps everything on its own disk instead and the settings above go unread -
which is a fine way to run one node, and is what [Run on one
node](../how-to/run-on-one-node.md) is about.

`INDEXER=true` makes this node a candidate for the indexer role. Being the
only candidate, it claims the role through a lease it writes into the
bucket - you now have a one node cluster.

Dev mode checks no credentials, which is why nothing below carries one.
Anywhere else a node wants `Authorization: Bearer <key>` on every request -
[Secure a deployment](../how-to/secure-a-deployment.md) covers getting the
first key.

## 4. Define an index

Send the index the definition you want it to have:

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

The answer is `201 Created`, carrying the definition now in effect, the
status of the index, and an `ETag` naming the version of the definition:

```json
{
  "name": "books",
  "version": "9f2c1a0b3d4e5f60",
  "definition": { "...": "as sent" },
  "status": { "state": "USABLE", "readOnly": false, "...": "..." }
}
```

Each field opted into the ways it can be used: `title` can be matched
against and sorted by, `published` can be filtered on. Browse to
[localhost:8888/buckets/exofind/](http://localhost:8888/buckets/exofind/) -
the definition is already there, which is all another node would need to
serve this index.

Sending the same request again with a change updates the index; the
definition is desired state, so it can live in version control and be
applied whenever it changes.

## 5. Index some books

Documents go in through the index's own endpoint, and become searchable when
the index is committed:

```shell
curl -X POST http://localhost:8080/v1alpha1/indexes/books/documents \
  -H 'Content-Type: application/json' \
  -d '{
    "documents": [
      { "id": "1", "title": "Silent Spring", "published": true },
      { "id": "2", "title": "Spring Cleaning", "published": false }
    ]
  }'

curl -X POST http://localhost:8080/v1alpha1/admin/indexes/books/actions/commit
```

The answer to the first is `{"indexed": 2}`. The commit writes the documents
into a Lucene commit and pushes it, with the definition and a manifest, to
the bucket - which is what makes them searchable here and available to every
other node.

Each document carries its own `id`, which the definition marks as the
primary key, so sending the same document again replaces it rather than
adding a second copy.

## 6. Search

```shell
curl http://localhost:8080/v1alpha1/indexes/books/search \
  -H 'Content-Type: application/json' \
  -d '{ "query": [ { "type": "text", "text": "spring" } ] }'
```

Both books match, because `title` opted into being matched against. Add a
filter and only one does:

```shell
curl http://localhost:8080/v1alpha1/indexes/books/search \
  -H 'Content-Type: application/json' \
  -d '{ "query": [
        { "type": "text", "text": "spring" },
        { "field": "published", "match": { "value": true } }
      ] }'
```

Try misusing a field to see validation answer:

```shell
curl http://localhost:8080/v1alpha1/indexes/books/search \
  -H 'Content-Type: application/json' \
  -d '{ "query": [ { "field": "title", "match": { "value": "x" } } ] }'
```

The definition never asked for `title` to be filtered on, so the search is
refused with `index:query:usage_not_enabled` instead of answered with no
results - a definition mistake and an empty result would otherwise look the
same.

## Where to go next

- [Define an index](../how-to/define-an-index.md) walks the definition
  itself: field types, wildcards, document source, tie breakers.
- [Index documents](../how-to/index-documents.md) turns two curl calls into
  a dataset load and a feed that keeps it current.
- [Search an index](../how-to/search-an-index.md) turns one query into a
  search page: filters, facets, ordering and highlighting.
- [Run more than one node](../how-to/run-multiple-nodes.md) turns this into
  a cluster.
- [Architecture](../explanation/architecture.md) explains the shape you just
  ran.
