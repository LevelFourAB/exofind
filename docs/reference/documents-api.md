# Documents API

Putting documents into an index and taking them out again, under
`/v1alpha1/indexes/{name}/documents`.

```
POST   /v1alpha1/indexes/{name}/documents                  # index documents
POST   /v1alpha1/indexes/{name}/documents/actions/update   # change some fields
DELETE /v1alpha1/indexes/{name}/documents/{key}            # remove one document
POST   /v1alpha1/indexes/{name}/documents/actions/delete   # remove by keys or query
```

A document carries its own primary key, so indexing one is desired state the
way a definition is: sending it again leaves the index holding what it says,
replacing whatever sat under that key before. An index without a primary key
gets a new document for every request instead.

Removing a document says the same kind of thing, which is why a key nothing
was indexed under is not an error - the index holds no document under that
key either way.

Only the indexer writes. A request that reaches another node is answered
with a `307` pointing at the indexer when one is known, and refused with
`index:readonly` when it is not - the same as the write endpoints of the
[admin API](admin-api.md).

Changes become searchable when the index is committed, which is also what
pushes them to the remote. The indexer commits on its own once enough has been
indexed or enough time has passed - see
[Committing](configuration.md#committing) for how long that is and how to
change it - so nothing has to ask for a change to show up.

Asking anyway commits whatever is waiting there and then:

```
POST /v1alpha1/admin/indexes/{name}/actions/commit
```

Loading a dataset is many requests here and one commit at the end, rather than
a commit per batch.

## Indexing documents

### Request

With `Content-Type: application/json`, the documents come as a list:

```json
{
  "documents": [
    {
      "id": "1",
      "name": "blåbärssylt",
      "tags": ["sylt", "bär"],
      "energy": 234
    }
  ]
}
```

With `Content-Type: application/x-ndjson`, one document per line and no
wrapper:

```
{"id": "1", "name": "blåbärssylt"}
{"id": "2", "name": "rågbröd"}
```

The two are the same request in different clothes. Newline delimited JSON is
indexed as it is read, so how much a request may carry is a question of the
connection rather than of memory, which is what a dataset is loaded as.

The answer says how many documents were taken:

```json
{ "indexed": 2 }
```

## How a document is shaped

A document is written the way a search reads one back, so a hit can be sent
straight back to be indexed again:

| The field | Is written as | Example |
|-----------|---------------|---------|
| Holding one value | the value itself | `"name": "rågbröd"` |
| Declared `multiple` | an array | `"tags": ["sylt", "bär"]` |
| Locale specific | an object keyed by locale tag | `"name": { "sv": "sylt", "en": "jam" }` |
| A geo point | an object of `lat` and `lon` | `"origin": { "lat": 59.33, "lon": 18.07 }` |
| A vector | an array of numbers | `"embedding": [0.12, -0.4]` |
| An object | a JSON object of the fields it declares | `"variants": { "size": "S" }` |
| A timestamp | an ISO 8601 instant | `"published": "2026-08-16T09:00:00Z"` |

What a JSON object means therefore depends on the field it was given to, and
the definition is what decides - which is why the same object is a locale map
for one field and a point for another. A locale specific field given a value
without any locale keeps it in the field's default locale.

An object field is written the same way whichever mode it declares - a
document gives the value inside the object, never under the dotted path a
search names. A field written as `"dimensions.width"` directly is refused
with `index:update:field_inside_object`, so there is one way to write a
value.

A field written as `null` is a field that was not given, so a document can be
built with a key for every field the caller knows about. A field the
definition marks `required` is still missing, and reported as missing.

## Changing some of the fields

A price or availability feed touches most of a catalogue and changes two fields
of each document. Sending only those fields is what `actions/update` is for:

```
POST /v1alpha1/indexes/{name}/documents/actions/update
```

```json
{
  "documents": [
    { "id": "1", "price": 34.50, "inStock": true },
    { "id": "2", "price": 12.00, "discount": null }
  ]
}
```

`application/x-ndjson` works here too, one change per line and no wrapper.

Each change carries the primary key of the document it changes, and the unit of
a change is a field:

| The change | What it does |
|------------|--------------|
| A field with a value | replaces everything that field held |
| A field written as `null` | empties the field |
| A field left out | leaves it as it is |

This is the one place `null` empties a field rather than meaning a field that
was not given - an update that could not say "empty this" would have no way to
take a discount off.

A field is replaced whole, so a locale specific field named by a change holds
the locales that change gives it and no others, and an object field holds the
values it gives rather than merging into the ones already there. Sending the
whole document is what changes part of one of those.

The document is read, changed and written back as one, so several changes to
the same document take effect in the order they were given whether or not
anything was committed in between. What comes out is indexed as if it had been
sent whole, which is why a change that leaves the document failing validation
is refused and leaves it as it was.

The answer says how many documents were changed:

```json
{ "updated": 2, "missing": [] }
```

### A key nothing is indexed under

There is nothing to change, and by default that fails the request the way any
other refused document does. A feed running against a catalogue that is pruned
elsewhere wants the rest applied instead:

```
POST /v1alpha1/indexes/{name}/documents/actions/update?missing=skip
```

```json
{ "updated": 1998, "missing": ["sku-9", "sku-40"] }
```

`missing` is `fail` or `skip`, and defaults to `fail`.

### Indexes that keep no copy of their documents

Changing part of a document needs the copy of it as it was given, which is what
`source` being `none` in the [definition](admin-api.md) turns off. Such an index
refuses `actions/update` with `index:source:not_kept` rather than indexing a
document made of only the fields it was sent. The same answer comes back for a
document that was indexed while the index kept nothing, on an index set to keep
them now.

An index without a primary key has no way to name the document to change, and
refuses with `index:no_primary_key`.

## Removing documents

One document is removed by its key in the path, which answers `204` whether
or not anything was indexed under it:

```
DELETE /v1alpha1/indexes/foods/documents/1
```

The key arrives as text and is read as the type of the key field, so a
numeric key is written the way it is written in a document. A key that is not
a value of that type is refused with `index:query:invalid_value`, and an
index whose definition declares no primary key with `index:no_primary_key` -
there is nothing there to name a document by.

Several documents go in one request, named either by their keys or by a query
they match. A request carries exactly one of the two:

```json
{ "keys": ["1", "2", "3"] }
```

```json
{
  "query": [ { "field": "category", "match": { "value": "sylt" } } ],
  "locale": "sv"
}
```

The clauses are the ones a [search](search-api.md) is written with and mean
the same here: what a search of those clauses brings back is what a delete of
them removes. `locale` says which variant of a locale specific field is
matched, and only belongs with a `query`. An empty `query` matches every
document and empties the index; an empty `keys` names nothing and removes
nothing.

The answer says how many documents the request removed:

```json
{ "deleted": 3 }
```

For keys that is the number of keys the request carried, as a key nothing was
indexed under is not an error. For a query it is how many documents it
matched among the searchable ones - documents indexed since the last commit
are removed as well, and not counted, because nothing is searchable until it
has been committed.

Every key is read before anything is removed, so a key the index refuses
leaves the keys around it in place.

## Failures

Documents are taken in the order they were sent, and the first one the index
refuses fails the request with `400`. Which document it was, and what about
it was wrong, is in the `path` of each error:

```json
{
  "code": "validation",
  "message": "Field `nonexistent` does not exist in index",
  "errors": [
    {
      "code": "index:update:field_not_found",
      "message": "Field `nonexistent` does not exist in index",
      "path": "documents[1].nonexistent",
      "arguments": { "name": "nonexistent" }
    }
  ]
}
```

The documents before it are already in the index and are committed with
everything else, so a request that failed halfway is sent again after fixing
it rather than undone - which is safe, because a document replaces whatever
sat under its key.

| Status | When |
|--------|------|
| `200` | Every document was taken, or the removal was made |
| `204` | A document was removed by the key in the path |
| `400` | A document or a key was refused, or the body could not be read |
| `404` | No index of that name on this node |
| `307` | This node is not the indexer, and the indexer is known |
| `409` | This node is not the indexer, or the index is being synchronized |
| `503` | The index was closed to make room; repeating the request opens it again |
