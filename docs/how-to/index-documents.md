# Index documents

Getting documents into an index, keeping them current afterwards, and
knowing when a change is searchable. The shape of every request and answer is
in the [documents API](../reference/documents-api.md); this guide is about
which one to reach for.

The index has to be [defined](define-an-index.md) first - a field a document
carries and the definition does not know is refused rather than ignored.

## Send documents

```http
POST /v1alpha1/indexes/products/documents
Content-Type: application/json

{
  "documents": [
    { "id": "1", "name": "Rain jacket", "category": "Outerwear", "price": 129 },
    { "id": "2", "name": "Wool hat", "category": "Hats", "price": 39 }
  ]
}
```

Each document carries the field the definition marked as the primary key, so
sending it again replaces what sat under that key. There is no separate
create and update: indexing is desired state, and a feed can send everything
it has without knowing what the index already holds.

Documents become searchable when the index is committed. Its writer commits
on its own once enough has been indexed or enough time has passed - see
[committing](../reference/configuration.md#committing) - so a steady feed
needs to ask for nothing.

## Write to the index's writer

Each index is written by one node at a time, but nothing has to aim at it: a
write that reaches another node is forwarded to the node holding the index
and answered with what it answered, so any node works. Where there is no
candidate to hold it the write is refused with `indexer:unavailable`, which
is what a cluster with no writers looks like - [Run more than one
node](run-multiple-nodes.md) covers keeping candidates running.

Searches have no such rule and are answered by the node that receives them.

## Load a dataset

Send newline delimited JSON and commit once at the end:

```http
POST /v1alpha1/indexes/products/documents
Content-Type: application/x-ndjson

{"id": "1", "name": "Rain jacket", "category": "Outerwear", "price": 129}
{"id": "2", "name": "Wool hat", "category": "Hats", "price": 39}
```

Documents are indexed as the body is read, so the size of a load is a
question of how long the connection stays up rather than of memory. Stream
the whole file as one request where it can be reissued, or split it into
requests of a few thousand documents where a retry should not start from the
top.

```http
POST /v1alpha1/admin/indexes/products/actions/commit
```

One commit at the end rather than one per batch. A commit writes a Lucene
commit and pushes it to the remote, so committing per batch pays that cost
for every batch and delays nothing else.

## Change a few fields of many documents

A price or stock feed touches most of the catalogue and changes two fields of
each document. Sending only those fields keeps the rest as it is:

```http
POST /v1alpha1/indexes/products/documents/actions/update

{
  "documents": [
    { "id": "1", "price": 99, "inStock": true },
    { "id": "2", "discount": null }
  ]
}
```

A field with a value replaces what that field held, `null` empties it, and a
field left out is untouched. A field is replaced whole, so a change naming a
locale specific field or an object field gives the values that field is to
hold from then on - send the whole document to change part of one of those.

Two things to know before building a feed on this:

- The index has to keep a copy of its documents. `"source": "none"` in the
  definition turns that off and makes updates refuse with
  `index:source:not_kept`.
- A key nothing is indexed under fails the request by default. A feed running
  against a catalogue that is pruned elsewhere sends `?missing=skip` and
  reads which keys were missing out of the answer.

## Remove documents

```http
DELETE /v1alpha1/indexes/products/documents/1
```

Answers `204` whether or not anything was indexed under the key, because the
index holds no document under it either way.

Several go in one request, by keys or by a query:

```http
POST /v1alpha1/indexes/products/documents/actions/delete

{ "query": [ { "field": "category", "match": { "value": "Hats" } } ] }
```

The clauses are the ones a [search](../reference/search-api.md) is written
with and mean the same, so run the search first to see what the delete will
take. An empty `query` matches every document and empties the index.

## Handle a failed request

Documents are taken in the order they were sent, and the first one the index
refuses fails the request with `400`. The documents before it are already
indexed. Which document it was is in the `path` of the error:

```json
{
  "code": "validation",
  "errors": [
    {
      "code": "index:update:required_field_missing",
      "path": "documents[41].name"
    }
  ]
}
```

Fix the document and send the request again rather than trying to undo it -
every document replaces whatever sat under its key, so the ones that went
through the first time land the same way the second time.

A `503` means the index was closed to make room on disk; repeating the
request opens it again. A `409` means the index has no writer right now, or
is being synchronized - both are worth a retry.

## See what is in the index

```http
POST /v1alpha1/indexes/products/search

{ "limit": 0 }
```

A `limit` of `0` answers only how many documents match, which for an empty
query is how many are searchable. Documents indexed since the last commit are
not counted, because nothing is searchable until it has been committed - if
the number is short of what was sent, commit and ask again.

## Related

- [Documents API](../reference/documents-api.md) - every request, answer and
  status code.
- [Define an index](define-an-index.md) - what the documents are validated
  against.
- [Roll out a definition change](roll-out-a-definition-change.md) - reindexing
  everything when the definition changes what is held.
- [Search an index](search-an-index.md) - reading the documents back.
