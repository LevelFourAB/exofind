# Search an index

Building a search out of the clauses, facets and options a request takes. The
full vocabulary is in the [search API](../reference/search-api.md); this guide
puts the pieces together in the order a search page needs them.

Every node answers searches from its own copy of the index, so a search goes
to whichever node is nearest and never has to reach the indexer.

## Ask for everything

```http
POST /v1alpha1/indexes/products/search

{}
```

An empty request matches every document and brings back the first ten, which
is what a page shows before anyone has typed or picked anything. Results come
back as `hits`, each with the primary key as `id` and the stored fields as
`document`.

## Match what someone typed

```json
{
  "query": [
    { "type": "text", "text": "rain jack", "fields": { "name": 3, "description": null } }
  ]
}
```

The `text` clause is the search box. `fields` says where to look and how much
a hit in each counts; left out it looks in every field that can be matched
on. The last word is treated as still being typed and matched as a prefix, so
this finds `Rain jacket` while somebody is halfway through.

Two options are worth setting deliberately:

- `"match": "user"` reads quotes and minus signs the way a person writing in
  a search box means them. Use it for text that came from a human, and leave
  the default `all` for text your own code assembled.
- `"relax"` decides what may be let go of rather than answering with
  nothing. The default drops words nothing holds, and the response says what
  it dropped in `relaxed` so the page can tell the user.

A field has to be defined for the way a search uses it. Using one the
definition never opted in is refused with `index:query:usage_not_enabled`
rather than answered with no results - a definition mistake and an empty
result would otherwise look the same.

## Separate the scope from the refinements

Both `query` and `filters` narrow the hits, and where a condition goes
decides what the facet counts say:

- `query` is the scope the whole page runs in - the search box, the section
  of the catalogue, the tenant. It narrows every facet count.
- `filters` are the refinements a user has ticked. A facet leaves out the
  filters on its own field, so a ticked value does not collapse its own list
  to itself.

```json
{
  "query": [
    { "type": "text", "text": "jacket" },
    { "field": "published", "match": { "value": true } }
  ],
  "filters": [
    { "field": "category", "match": { "type": "in", "values": ["Outerwear"] } },
    { "field": "price", "match": { "type": "range", "gte": 50, "lt": 200 } }
  ]
}
```

Putting the visibility rule in `query` and the user's choices in `filters` is
what makes the category list keep showing the other categories while
`Outerwear` is ticked.

## Build the filter list

```json
{
  "facets": [
    { "field": "category", "limit": 20 },
    { "field": "price", "ranges": [
      { "to": 100 },
      { "from": 100, "to": 500 },
      { "from": 500 }
    ] }
  ]
}
```

The counts come back under `facets`, keyed by name - `values` for a value
facet, `buckets` for one given `ranges`. Either can be sent straight back as
a filter on the same field, a value as it came back and a bucket's bounds as
a `range`, so rendering a checkbox and handling the click need no mapping
between them.

A search with `"limit": 0` and facets refreshes the counts without fetching
hits, which is what a filtering UI asks for when only the counts changed.

Fields the definition marked `facet` can answer this; see
[facets](../reference/search-api.md#facets) for counting down a category tree
and for bucketing dates.

## Decide the order

Left out, results come back best match first. A search that only narrows
still has an order - the index's ranking signals - which is what puts the
best sellers on top of a browsed category.

```json
{ "sort": [ { "field": "price", "order": "asc" } ] }
```

Giving a `sort` takes relevance out of it, so a page that offers "sort by
price" should offer a way back to relevance - an option that sends no `sort`
at all, or `[ { "type": "score" } ]`.

## Return only what the page renders

```json
{
  "fields": ["name", "price", "image"],
  "highlight": { "fields": { "name": {}, "description": { "fragments": 1 } } }
}
```

`fields` keeps the response to what is drawn; left out, every stored field
comes back. A field inside an object is named by its dotted path -
`"fields": ["variants.price"]` answers with the variants holding their price
and nothing else. A field the index has no way to return is refused rather
than left out of the hits, so a page asking for one finds out when it asks.

`highlight` answers each hit with fragments showing what the text
matched, wrapped in `<em>` unless `pre` and `post` say otherwise. The
fragments are the stored text with the markers spliced in and nothing is HTML
escaped, so escape the text around the markers when rendering.

Highlighting follows the scoring part of the search: a document is not
highlighted for the category it happens to be filtered into.

## Search in a language

```json
{ "query": [ { "type": "text", "text": "regnjakke" } ], "locale": "nb" }
```

`locale` says which variant of a locale specific field the search reads and
analyzes against. The tag is matched as closely as each field's declared
locales tell apart, so passing a browser's `nb-NO` straight through works.
Left out, every field is read in its own default locale. Which languages
there are rules for is in the [locale reference](../reference/locales.md).

## Move through the results

```json
{ "limit": 20, "after": "AW8..." }
```

Cursors from the previous response continue where it ended and cost the same
at any depth. An `offset` is capped by `SEARCH_MAX_PAGE_DEPTH`, and numbered
pages are asked for with `pages`. Which to use for which kind of page is in
[Paginate search results](paginate-search-results.md).

## Count without fetching

```json
{ "query": [ ... ], "limit": 0, "total": "exact" }
```

A `limit` of `0` answers only how many documents match. The total is a lower
bound by default, which is enough for "more than 1000 results"; `exact`
counts every match and is what a numbered pager needs.

## Related

- [Search API](../reference/search-api.md) - every clause, matcher, sort and
  option.
- [Relevance](../explanation/relevance.md) - what decides the order when the
  search asks for none.
- [Search by vector](search-by-vector.md) - finding documents by meaning
  rather than by words.
- [Use sub-documents](use-sub-documents.md) - asking several things of one
  value of an `object` field, and ordering and counting by it.
- [Paginate search results](paginate-search-results.md) - offsets, cursors
  and numbered pages.
- [Localize fields](localize-fields.md) - holding and searching values in
  several languages.
- [Define an index](define-an-index.md) - opting fields into the ways a
  search may use them.
