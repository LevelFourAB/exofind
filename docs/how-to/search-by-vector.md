# Search by vector

Finding documents by what they mean rather than by the words they hold:
define a vector field, index the vectors your model produces, and search for
the nearest of them with the `knn` clause. The vectors arrive with the
documents - the engine never produces them, and never calls a model on your
behalf.

## Define the field

```json
"embedding": {
  "type": "vector",
  "dimensions": 768,
  "similarity": "cosine"
}
```

`dimensions` is required and has to be what the model produces, up to 4096.
It is fixed with the documents already written, so changing it - or
`similarity` - on an index holding documents is a
[rollout into a new generation](roll-out-a-definition-change.md) rather than
a change in place.

`similarity` is how near two vectors are judged to be:

- `cosine` (default) compares direction and ignores length, which is what
  most text embedding models want. A vector of only zeros has no direction
  and is refused with `index:update:vector:zero_vector`.
- `dot_product` is cheaper but only orders sensibly when every vector is unit
  length. Use it when the model documents that its output is normalized, or
  when you normalize the vectors yourself.
- `euclidean` compares position, for vectors where length carries meaning.

A vector is searched by similarity and by nothing else, so `filter`, `sort`,
`facet`, `multiple` and `locales` are refused on the field. Narrowing by
metadata is a job for the ordinary fields beside it.

## Index the vectors

A vector is written as an array of numbers, in the same document as
everything else:

```http
POST /v1alpha1/indexes/products/documents
Content-Type: application/x-ndjson

{"id": "1", "name": "Rain jacket", "category": "Outerwear", "embedding": [0.02, -0.13, ...]}
```

Every value has to have exactly the dimensions the field declares
(`index:update:vector:wrong_dimensions`) and every component has to be a
finite number (`index:update:vector:not_finite`). A document may leave the
field out; it is then found by the other clauses and never by a `knn` one.

The same model has to produce the query vector as produced the documents',
including its version and any prompt or prefix it expects. Nothing checks
this - two models of the same dimensions give nearest neighbours that mean
nothing. Changing models is a rollout into a new generation, the same as
changing the dimensions.

## Find the nearest documents

```json
{
  "query": [
    { "type": "knn", "field": "embedding", "vector": [0.01, -0.2], "k": 50 }
  ],
  "fields": ["name", "category"]
}
```

`k` is how many neighbours the clause returns, and it bounds the results
whatever `limit` and `offset` ask for - paging through a `knn` search is
paging through those `k`. Ask for a `k` that covers the pages the UI offers
rather than only the first one.

Name the fields the page renders. Left to itself a result carries every
stored field, and for an index holding vectors that means sending them back
with every hit.

## Narrow the neighbours

Filtering conditions belong *inside* the clause:

```json
{ "type": "knn", "field": "embedding", "vector": [0.01, -0.2], "k": 50,
  "filter": [
    { "field": "category", "match": { "value": "Outerwear" } },
    { "field": "inStock", "match": { "value": true } }
  ] }
```

`filter` narrows which documents may be neighbours before the nearest are
picked, so a filtered search still returns up to `k` results. A condition
written beside the clause instead intersects with the `k` nearest documents
of the whole index afterwards, which for a narrow filter leaves almost
nothing - a page that empties as the user ticks a box, and nothing the caller
can do about it.

## Combine with a text search

A `knn` clause scores, so putting it in an `or` beside a `text` clause adds
the two rankings together - documents the words found, documents the vector
found, and the ones both found on top:

```json
{
  "query": [
    { "type": "or", "clauses": [
      { "type": "text", "text": "waterproof jacket", "fields": { "name": null } },
      { "type": "knn", "field": "embedding", "vector": [0.01, -0.2], "k": 50 }
    ] }
  ]
}
```

Wrap either side in a [`boost`](../reference/search-api.md#boost) to change
how much it counts. Which side should weigh more depends on the model and the
text, so it is worth settling against searches you know the right answers to
rather than by argument.

Scores from the two sides are on different scales, so a search that mixes
them is ranked by their sum rather than by anything normalized. Keep the
conditions that must hold - visibility, tenancy - out of the `or` and in
`query` beside it, where they narrow rather than contribute.

## Trade recall for space and speed

Vectors are searched through an HNSW graph, and both of its knobs cost
indexing time and space for recall:

```json
"embedding": {
  "type": "vector",
  "dimensions": 768,
  "hnsw": { "m": 16, "efConstruction": 100 },
  "quantization": "int8"
}
```

- `m` is how many neighbours each node keeps, `efConstruction` how many
  candidates are considered while building. Raise them when a search misses
  neighbours it should have found; the defaults are Lucene's.
- `quantization` gives up precision in the stored vectors for space: `none`
  (default), `int8` or `int4`. It decides how the segments written from then
  on are stored, so it takes full effect once everything has been written
  again.

Vectors are read through memory maps like the rest of the index, so a node
serving them wants the memory as page cache rather than heap - see [heap
against page cache](../reference/configuration.md#heap-against-page-cache).
Distances are computed through the JVM's vector module, which the shipped
JVM options enable and a node without them logs at startup.

## Related

- [`vector` fields](../reference/field-types.md#vector) - every property.
- [The `knn` clause](../reference/search-api.md#knn) - the request shape.
- [Search an index](search-an-index.md) - the rest of the search request.
- [Roll out a definition change](roll-out-a-definition-change.md) - changing
  the model or the dimensions without callers noticing.
