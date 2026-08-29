# Search API

Executes a search query against an index on the node that receives the request.

```
POST /v1alpha1/indexes/{name}/search
```

## Request

```json
{
  "query": [
    { "type": "text", "text": "silent spr", "fields": { "name": 3, "description": null } },
    { "field": "published", "match": { "value": true } }
  ],
  "filters": [
    { "field": "category", "match": { "type": "in", "values": ["fiction"] } }
  ],
  "facets": [ { "field": "category" } ],
  "sort": [ { "type": "score" }, { "field": "name", "order": "asc" } ],
  "locale": "sv",
  "fields": ["name", "price"],
  "highlight": { "fields": { "name": {} } },
  "limit": 20,
  "offset": 0,
  "total": "estimate"
}
```

All request properties are optional. An empty request matches all documents in the index.

| Property | Type | Default | Description |
|---|---|---|---|
| `query` | Array | `[]` | Clauses that a matching document must satisfy. Clauses in the array are combined with an implicit `AND`. Evaluated clauses narrow all facet counts. If omitted, matches all documents. |
| `filters` | Array | `[]` | Refinement clauses, specified as `field` clauses or `nested` clauses. Filters narrow hits, but facets on the filtered field exclude their own filter entries from counts by default (see [Facets](#facets)). Unsupported clause types return `search:filter:clause_invalid`. Clauses that score results return `search:filter:scores`. |
| `facets` | Array | `[]` | Fields to aggregate match counts for. See [Facets](#facets). If omitted, no facet counts are calculated. |
| `sort` | Array | `[{"type": "score"}]` | Order in which results are returned. If omitted, results are sorted by relevance score in descending order. |
| `signals` | Array | Index ranking signals | Document ranking signals used to adjust relevance scoring. See [Signals](#signals). If omitted, uses the ranking signals configured on the index. |
| `locale` | String | Field defaults | BCP-47 locale tag used to read and return locale-specific fields. Matches the closest declared locale on each field (for example, `sv-SE` falls back to `sv`). If no matching variant exists, uses the field default. |
| `fields` | Array | All stored fields | Document fields to return with each result. Fields inside an [`object`](field-types.md#object) are specified by dotted path and returned nested inside the object. Requesting unretrievable fields returns an error (see [Document source](field-types.md#document-source)). The primary key is always included. |
| `highlight` | Object | None | Fields to return highlighted snippets for. See [Highlighting](#highlighting). |
| `matched` | Object | None | Nested object fields for which to return matched values with each hit. See [Matched values](#matched-values). |
| `hits` | Object | None | Specifies an object field whose matched values return as individual hits instead of full documents. See [What a hit stands for](#what-a-hit-stands-for). |
| `limit` | Integer | `10` | Maximum number of results to return. Setting `limit` to `0` returns the total match count without hits. |
| `offset` | Integer | `0` | Number of matching results to skip. Specify at most one of `offset`, `after`, or `before`. |
| `after` | String | None | Cursor string from the `next` property of a previous response to fetch the next page. |
| `before` | String | None | Cursor string from the `previous` property of a previous response to fetch the preceding page. |
| `pages` | Object | None | Requests numbered page metadata. Accepts an optional `{ "max": n }` object to limit the number of page entries (default `9`). Implies `"total": "exact"`. |
| `total` | String | `"estimate"` | Counting mode for the total matching document count: `"estimate"` counts until exceeding the returned window; `"exact"` counts every matching document. |

## Clauses

Clauses represent search conditions. They are structured as a tagged union where `type` selects the clause type. If `type` is omitted, the clause defaults to a `field` clause containing `field` and `match`.

### `field`

Matches documents by the value of a single field:

```json
{ "field": "category", "match": { "value": "fiction" } }
```

The targeted field must be indexed for the requested matcher usage. If the field is not configured for that usage, the request returns `index:query:usage_not_enabled`.

### `text`

Matches query text across one or more fields:

```json
{ "type": "text", "text": "silent spr", "fields": { "name": 3, "description": null } }
```

The `text` clause accepts the following options:

- `fields`: An object mapping field names to score weights. A field mapped to `null` uses the weight from its field definition. If omitted, searches all searchable fields.
- `match`: Term matching mode: `"all"` (default), `"any"`, `"phrase"`, or `"user"`. `"phrase"` requires terms to appear in exact order and adjacent. `"user"` parses search syntax such as quotes and negation (see [Reading what was typed](#reading-what-was-typed)).
- `prefix`: Prefix matching behavior on the final query term: `"last_token"` (default) matches the trailing word as a prefix; `"off"` requires an exact word match.
- `typos`: Typo tolerance handling: `"auto"` (default) follows each field's `typoTolerance` configuration; `"off"` disables typo tolerance for the clause.
- `slop`: Number of intervening words permitted between terms in a phrase. Defaults to `0` (words must be adjacent).
- `relax`: Query relaxation strategy when no documents match: `"unmatched"` (default), `"words"`, or `"off"`. See [Finding something rather than nothing](#finding-something-rather-than-nothing).
- `combine`: Scope for multi-field term matching: `"term"` (default) or `"field"`.

Phrase queries operate within a single field. In phrase queries, `combine` is ignored and terms are matched exactly as typed, regardless of field `typoTolerance`. Stopwords removed during text analysis leave empty positions: searching for `spring of 1962` matches that sequence, but searching for `spring 1962` does not. Fields defined only for `autocomplete` do not support phrase matching; queries omitting `fields` skip autocomplete-only fields, and explicitly targeting one returns `index:query:usage_not_enabled`.

The `slop` parameter allows intervening words between phrase terms without changing their relative order. For example, `silent spring` with `"slop": 1` matches `silent green spring`, but does not match `spring silent`. Closer terms rank higher than terms separated by more words. Setting `slop` above `0` with `"match": "all"` or `"match": "any"` returns `search:clause:slop_not_applicable`.

Under `"combine": "term"`, each term is evaluated across all targeted fields and scored in the highest-scoring field, allowing terms to appear in different fields. Document score is the sum of term scores. Under `"combine": "field"`, a single field must satisfy `match` on its own, and the document is scored by its single best-matching field.

When fields produce different token counts during analysis (such as through decompounding or stopword removal), fields with identical tokenizations are grouped and evaluated together, scoring the document by the best-matching group.

#### Reading what was typed

Setting `"match": "user"` parses input using search syntax and combines remaining terms with `all`:

```json
{ "type": "text", "text": "running shoes \"trail ready\" -leather", "match": "user" }
```

| Syntax | Description |
|---|---|
| `"apple watch"` | Matches the enclosed terms as an ordered phrase. |
| `-leather` | Excludes documents containing the term. |
| `-"apple watch"` | Excludes documents containing the phrase. |

All other punctuation characters are treated as literal text. Hyphens inside words (`e-mail`), quotation marks inside words (`it"s`), and hyphens without a following term remain part of the search string. An unclosed quotation mark extends to the end of the text.

User query syntax does not generate parse errors. Options configured on the clause apply to the parsed terms: `fields` and `combine` determine search targets, `slop` applies to quoted phrases, and `prefix` applies to the final loose word or the final word of an unclosed quote. Excluded terms do not apply prefix matching or `typoTolerance`.

If quoted text targets a field configured only for `autocomplete`, `user` mode treats the quoted phrase as individual terms rather than returning an error. A query containing only exclusions evaluates against all documents in the index. A query containing no searchable terms matches no documents.

#### Finding something rather than nothing

When `"match": "all"` produces zero results, the `relax` option controls how the engine relaxes terms:

| `relax` value | Description |
|---|---|
| `unmatched` (default) | Drops words that do not exist in the index. |
| `words` | Drops unmatched words, then drops the most common remaining words one by one until results are found. |
| `off` | Does not relax terms; returns an empty result set. |

Relaxation occurs only when the initial query returns zero matches. Only loose, unquoted words are eligible for relaxation; quoted phrases and negated terms (`-term`) are never dropped. Relaxation stops while at least one word remains. Dropped terms still contribute to scoring, ranking documents containing those terms above documents that do not.

When a query is relaxed, the response includes a `relaxed` object:

```json
{
  "hits": [ ... ],
  "relaxed": {
    "dropped": [
      { "word": "waterproof", "reason": "unmatched" },
      { "word": "mens", "reason": "common" }
    ],
    "text": "running shoes"
  }
}
```

The `relaxed` object contains:

- `dropped`: List of dropped words and the reason each was removed (`"unmatched"` or `"common"`).
- `text`: The effective query string used to execute the search.

Total counts and facet counts reflect the relaxed search. If zero results are caused by query `filters`, text relaxation is not applied.

### `knn`

Matches the `k` nearest documents by vector distance in a specified field:

```json
{ "type": "knn", "field": "embedding", "vector": [0.1, 0.2], "k": 10,
  "filter": [ { "field": "published", "match": { "value": true } } ] }
```

- `field`: The vector field to search.
- `vector`: The query vector array. The array length must match the dimensions declared in the field definition.
- `k`: Number of nearest documents to return.
- `filter`: Array of filter clauses that documents must satisfy before nearest-neighbor evaluation.

### `nested`

Matches documents where a single element of a `nested` [`object` field](field-types.md#object) satisfies all child clauses:

```json
{ "type": "nested", "path": "variants", "clauses": [
  { "field": "variants.color", "match": { "value": "red" } },
  { "field": "variants.price", "match": { "type": "range", "lt": 20 } }
] }
```

- `path`: Name of the nested object field.
- `clauses`: Array of clauses evaluated within a single nested object value. An empty array matches any document where the object field is present.
- `score`: Scoring mode for aggregating matching nested values: `"max"` (default), `"min"`, `"avg"`, or `"total"`.

A `nested` clause must target a `nested` object field path. Using a `nested` clause on a flattened object field returns `index:query:nested:flattened`. Using a `nested` clause on a non-object field returns an error. Child clauses may include `field`, `text`, `and`, `or`, `not`, and `boost`. Including a root-level clause such as another `nested` or `knn` clause returns `index:query:nested:unsupported_clause`.

A `text` clause inside a `nested` clause searches across all fields in the nested path when `fields` is omitted:

```json
{ "type": "nested", "path": "variants", "score": "total", "clauses": [
  { "type": "text", "text": "waterproof leather" }
] }
```

### `and`, `or`, `not`

Logical conjunction clauses that combine child clauses:

- `and`: All child clauses must match.
- `or`: At least one child clause must match.
- `not`: No child clauses may match.

Each logical clause accepts a `clauses` array of child clauses.

### `boost`

Increases the relevance score of documents that satisfy child clauses without excluding non-matching documents:

```json
{ "type": "boost", "weight": 2, "clauses": [ { "field": "featured", "match": { "value": true } } ] }
```

- `weight`: Multiplier applied to matching documents. Values greater than `1` increase score; values between `0` and `1` decrease score.
- `clauses`: Array of clauses required to apply the boost weight.

## Matchers

Matchers define criteria evaluated against field values in a `field` clause. A matcher is structured as a tagged union where `type` selects the matcher type. If `type` is omitted, the matcher defaults to `equals`.

| Matcher | Shape | Description |
|---|---|---|
| `equals` | `{ "value": v }` | Matches field values equal to `value`. |
| `in` | `{ "type": "in", "values": [v, ...] }` | Matches field values equal to any value in `values`. An empty array matches no documents. |
| `any` | `{ "type": "any" }` | Matches any document that contains a value for the field. |
| `prefix` | `{ "type": "prefix", "value": "EX-" }` | Matches string field values starting with `value`, evaluated against the entire field value. |
| `under` | `{ "type": "under", "path": "Men/Shoes" }` | Matches values at or below the specified path in a hierarchical tree. Requires a field configured with [`hierarchy`](field-types.md#string). Path segments must match complete levels. |
| `range` | `{ "type": "range", "gte": 10, "lt": 20 }` | Matches values within bounds. Accepts inclusive (`gte`, `lte`) and exclusive (`gt`, `lt`) bounds. At least one bound is required. |
| `ranges` | `{ "type": "ranges", "values": [ { "gte": 10, "lt": 20 }, { "gte": 50 } ] }` | Matches values falling within any of the specified range objects. Each range requires at least one bound. An empty array matches no documents. |
| `text` | `{ "type": "text", "text": "..." }` | Matches text within a single field using field-level analysis. Supports `match`, `prefix`, `typos`, `slop`, and `relax` options. |
| `distance` | `{ "type": "distance", "lat": 59.3, "lon": 18.1, "radius": 5000 }` | Matches geopoint values within `radius` meters of the specified latitude and longitude coordinates. |

Specifying a matcher unsupported by the target field type returns an error.

## Sorts

Sort objects specify the ordering of returned hits. A sort is structured as a tagged union where `type` defaults to a `field` sort when omitted. If `order` is omitted, score sorts default to descending and field sorts default to ascending (`"asc"`).

| Sort | Shape | Description |
|---|---|---|
| `field` | `{ "field": "name", "order": "asc" }` | Sorts by field value (`"asc"` or `"desc"`). The target field must have sorting enabled. |
| `score` | `{ "type": "score" }` | Sorts by document relevance score in descending order. |
| `distance` | `{ "type": "distance", "field": "location", "lat": 59.3, "lon": 18.1 }` | Sorts by distance from the specified geographic coordinate, nearest first. Does not accept an `order` property. |

Configured index tie-breaker sorts are automatically appended after the requested sorts.

### Ordering by a value inside an object

To sort by a field inside a `nested` [`object`](field-types.md#object), specify the dotted field path:

```json
"query": [
  { "type": "nested", "path": "variants", "clauses": [
    { "field": "variants.color", "match": { "value": "red" } }
  ] }
],
"sort": [ { "field": "variants.price", "order": "asc" } ]
```

Sorting uses only the nested values that matched the query's `nested` clauses. Clauses inside `or`, `not`, or `boost` clauses do not filter values for sorting. If a query contains no `nested` clauses on the path, all nested values are considered. For ascending sorts, the document is sorted by its lowest matching value; for descending sorts, by its highest matching value. Documents with no matching nested values use the `missing` behavior configured in the field definition.

Specifying a `distance` sort on a nested object field returns `index:query:nested:sort_unsupported`.

## Signals

Signals modify relevance scores by evaluating document field values:

```json
"signals": [
  { "field": "purchases", "saturation": { "pivot": 50 } },
  { "field": "published", "decay": { "halfLife": 604800 }, "weight": 0.5 }
]
```

When specified in the search request, `signals` replaces all ranking signals defined on the index. An empty array ranks results solely by text match score.

Signals apply only when results are ordered by relevance. Providing an explicit `sort` overrides signal ordering.

Targeting an unknown field returns `index:query:field_not_found`. Targeting a field without sorting enabled returns `index:query:usage_not_enabled`. Specifying a signal function unsupported by the field type returns `index:invalid-query-type`.

## Facets

Facets compute match counts for distinct values of specified fields. The target field must have `facet` enabled in its field definition; otherwise, the request returns `index:query:usage_not_enabled`.

```json
"filters": [
  { "field": "category", "match": { "type": "in", "values": ["fiction"] } }
],
"facets": [
  { "field": "category", "limit": 20 },
  { "name": "years", "field": "published_year", "order": "value" }
]
```

| Option | Type | Default | Description |
|---|---|---|---|
| `name` | String | Field name | Key used for the facet in the response. Required when faceting on the same field multiple times. Duplicate facet names return `search:facet:duplicate_name`. |
| `field` | String | Required | Target field to aggregate. |
| `limit` | Integer | `10` | Maximum number of facet values to return (1 to 1000). |
| `order` | String | `"count"` | Sort order of facet values: `"count"` (descending by count) or `"value"` (ascending by value). |
| `ranges` | Array | None | Array of range bucket definitions. See [Range buckets](#range-buckets). Cannot be combined with `limit` or `order` (`search:facet:ranges_conflicting`). |
| `path` | String | Root | Starting path level for hierarchical fields. See [Counting down a tree](#counting-down-a-tree). |
| `depth` | Integer | `1` | Number of hierarchical levels below `path` to count (1 to 10). |
| `excludeFilters` | Array | Facet field | List of field paths whose filter entries are excluded from this facet's calculation. Defaults to the facet's own field path. An empty array `[]` disables filter exclusion. A blank path returns `search:facet:exclude_filters_invalid`. |

The response returns facet counts under the `facets` object:

```json
"facets": {
  "category": {
    "values": [
      { "value": "fiction", "count": 87 },
      { "value": "poetry", "count": 21 }
    ],
    "totalValues": 14
  }
}
```

- `values`: Array of facet value objects containing `value` and `count`.
- `totalValues`: Total count of distinct values matching the query.

### Range buckets

Setting `ranges` computes counts across defined ranges on numeric and timestamp fields:

```json
"facets": [
  { "field": "price", "ranges": [
    { "to": 100 },
    { "from": 100, "to": 200 },
    { "from": 200 }
  ] }
]
```

Range rules:

- A range bucket includes values from `from` (inclusive) up to `to` (exclusive).
- Either `from` or `to` may be omitted for open-ended ranges, but not both (`search:facet:range_empty`).
- `to` must be greater than `from` (`index:query:facet_range_empty`).
- Maximum 1000 buckets per facet (`search:facet:ranges_too_many`).
- Using `ranges` on unsupported field types returns `index:invalid-query-type`.

The response returns range counts in the `buckets` array:

```json
"facets": {
  "price": {
    "buckets": [
      { "to": 100, "count": 41 },
      { "from": 100, "to": 200, "count": 17 },
      { "from": 200, "count": 3 }
    ]
  }
}
```

Selected buckets can be filtered in subsequent requests using a [`ranges`](#matchers) matcher:

```json
"filters": [
  { "field": "price", "match": { "type": "ranges", "values": [ { "lt": 100 }, { "gte": 200 } ] } }
]
```

#### Facet counting rules

- **`query` clauses**: Narrow all facet counts and all search hits.
- **`filters` clauses**: Narrow search hits and facet counts for all fields except facets configured to exclude those filter paths (by default, facets on the same field).
- **Filter granularity**: Filter exclusions apply to whole filter entries. Separate conditions into distinct filter entries to allow independent facet exclusion.
- **Locales**: Locale-specific fields are aggregated using the locale variant selected for the search.
- **Totals**: Calculating facets always computes an exact total count. Setting `"limit": 0` with facets returns facet counts without fetching document hits.

### Counting down a tree

For fields configured with [`hierarchy`](field-types.md#string), facets aggregate counts by hierarchy level:

```json
"facets": [ { "field": "category", "path": "Men", "depth": 2 } ]
```

The response returns nested hierarchy levels:

```json
"facets": {
  "category": {
    "values": [
      { "value": "Shoes", "path": "Men/Shoes", "count": 42, "totalValues": 2,
        "values": [
          { "value": "Running", "path": "Men/Shoes/Running", "count": 28 },
          { "value": "Casual", "path": "Men/Shoes/Casual", "count": 14 }
        ] },
      { "value": "Outerwear", "path": "Men/Outerwear", "count": 9 }
    ],
    "totalValues": 2
  }
}
```

- `value`: The label of the current level.
- `path`: The full path to the level, used in `under` filter matchers.
- `limit`, `order`, and `totalValues`: Evaluated independently per hierarchy level.

A document is counted once at each ancestor level in its path. Specifying `path` or `depth` on non-hierarchical fields returns `index:query:usage_not_enabled`. Combining `path` or `depth` with `ranges` returns `search:facet:ranges_on_a_tree`.

### Counting a value inside an object

Faceting on a dotted path inside a `nested` [`object`](field-types.md#object) counts matching parent documents for each value:

```json
"facets": [ { "field": "variants.color" } ]
```

Counts reflect parent documents; a document with multiple matching nested values counts once. Only nested values that satisfy the query's `nested` clauses are included in facet counts.

A filter on a nested field is specified as a `nested` clause in `filters`:

```json
"filters": [
  { "type": "nested", "path": "variants",
    "clauses": [ { "field": "variants.color", "match": { "value": "red" } } ] },
  { "type": "nested", "path": "variants",
    "clauses": [ { "field": "variants.price", "match": { "type": "range", "lte": 20 } } ] }
]
```

Filter exclusions identify entries by the most specific path covering all clauses within the entry. A filter entry covering both `variants.color` and `variants.price` is treated as a filter on `variants`.

## Highlighting

Highlighting returns matched text fragments for specified fields:

```json
"highlight": {
  "fields": {
    "name": {},
    "description": { "fragments": 1, "length": 80, "pre": "<b>", "post": "</b>" }
  }
}
```

Fields must have highlighting enabled in their field definitions (`matching` or `autocomplete`). Requesting highlighting on an unconfigured field returns `index:query:usage_not_enabled`.

| Option | Type | Default | Description |
|---|---|---|---|
| `fragments` | Integer | `3` | Maximum number of fragments to return. |
| `length` | Integer | `150` | Target character length per fragment (1 to 10000). Fragments break on sentence boundaries. |
| `pre` | String | `"<em>"` | Prefix tag inserted before highlighted terms. |
| `post` | String | `"</em>"` | Postfix tag inserted after highlighted terms. |

Highlighting rules:

- Fragments are generated only from scoring clauses. Non-scoring filter clauses do not produce highlights.
- Prefix-matched terms and typo-corrected terms are highlighted as full matched words in the source text.
- Locale-specific fields highlight the variant matched by the search.
- Text beyond the first 10,000 characters of a field value is not evaluated for highlighting.
- Highlighted text is not HTML-escaped.

## Matched values

Returns matched values of a `nested` [`object` field](field-types.md#object) for each hit:

```json
"matched": {
  "fields": {
    "variants": { "limit": 3, "fields": ["variants.color"] }
  }
}
```

Targeting a field that is not a `nested` object returns `index:query:matched:not_object`.

| Option | Type | Default | Description |
|---|---|---|---|
| `limit` | Integer | `3` | Maximum number of matched values to return per hit (1 to 100). |
| `fields` | Array | All object fields | Field paths inside the nested object to include in each returned value. |

Field paths in `fields` must reside under the target object path (`search:matched:field_not_inside`) and exist in the schema (`index:query:field_not_found`). If the index has [document source](field-types.md#document-source) set to `none`, specifying `fields` returns `index:query:source_not_kept`.

Response format under each hit:

```json
"matched": {
  "variants": {
    "values": [ { "color": "red", "size": "M", "price": 19.5 } ],
    "totalValues": 3
  }
}
```

- `values`: Array of matched nested values, up to `limit`. If scoring clauses exist within the `nested` clause, values are ordered by score; otherwise, they appear in document order. If document source is `none`, `values` is omitted.
- `totalValues`: Total count of matched values for that nested field in the document.

## What a hit stands for

Setting `hits` causes each matched value of a `nested` [`object` field](field-types.md#object) to return as an individual hit instead of a document hit:

```json
"hits": { "path": "variants" }
```

When `hits` is configured, totals count matching nested values, facets count value hits, and pagination cursors step through values. Targeting a field that is not a `nested` object returns `index:query:hits:not_object`.

Adding `when` narrows expansion to the documents it matches, leaving the rest as document hits. See [Expanding only some documents](#expanding-only-some-documents).

| Option | Type | Default | Description |
|---|---|---|---|
| `path` | String | Required | Dotted path of the nested object field whose matched values become hits. |
| `fields` | Array | All object fields | Dotted field paths inside the nested object to return in `value`. |
| `when` | Array | All matching documents | Clauses selecting which documents expand into value hits; other matching documents return as document hits. See [Expanding only some documents](#expanding-only-some-documents). |

Field names in `fields` must be prefixed by `path` (`search:hits:field_not_inside`) and exist in the index (`index:query:field_not_found`). If document source is `none`, specifying `fields` returns `index:query:source_not_kept`.

Hit response structure:

```json
{
  "id": "9781234567890",
  "index": 2,
  "score": 8.42,
  "value": { "color": "red", "size": "M", "price": 19.5 },
  "document": { "name": "Trail Tee", "brand": "Ridge" }
}
```

- `id`: Primary key of the parent document. Multiple hits share an `id` when a document contains multiple matching values.
- `index`: Zero-based array index of the value in the parent document. The unique identifier of a value hit is `id` combined with `index`.
- `value`: The matched nested value object. Omitted if document source is `none`.
- `document`: Selected fields of the parent document per the search request's `fields` property.

Value hit scoring combines the parent document score (including signals) with the nested value's clause score. `sort` can order by `score` or by fields within the nested object path. Specifying index root fields in `sort` returns `index:query:hits:sort_unsupported`; specifying distance sort returns `search:hits:distance_sort`. Index tie-breaker sorts are ignored.

Setting `hits` cannot be combined with:

- `matched` (`search:hits:with_matched`)
- `highlight` (`search:hits:with_highlight`)
- `knn` clauses (`search:hits:with_knn`)

### Expanding only some documents

Specifying `when` restricts value expansion to documents that match the `when` clauses. All other matching documents return as document hits, returning both hit types in a single result page:

```json
"hits": {
  "path": "variants",
  "when": [ { "field": "splitVariants", "match": { "value": true } } ]
}
```

The `when` array accepts `field` and `nested` clauses combined with an implicit `AND`. Scoring clauses are not permitted. Unsupported clause types return `search:hits:when_clause_invalid`; clauses that score return `search:hits:when_scores`. Both errors point to `/hits/when/<index>` in the request body. If `when` is omitted, every matching document expands.

When `when` is configured:

- **Sorting**: Mixed result pages can only be sorted by `score`. Field sorts return `search:hits:when_field_sort` (pointing to `/sort/<index>`), or `index:query:hits:when_sort_unsupported` when calling the engine directly. Distance sorts return `search:hits:distance_sort`.
- **Scoring**: Every hit receives its parent document relevance score. Nested value clause scores are not added.
- **Facets**: Facet counts aggregate matching documents rather than hits.
- **Totals**: The `total` property counts hits, counting expanded documents once per matching nested value. The response includes a `documents` object with `count` and `exact` fields reporting the total count of matching documents.
- **Empty nested values**: A document that matches `when` but contains no matching values under `path` returns no hit. It is not returned as a document hit. The document still contributes to `documents` and facet counts.
- **Pagination cursors**: Cursors are keyed by hit type (document hits, value hits, or mixed hits). A cursor generated for a query using `when` is rejected by queries without `when`, and vice versa.

## Response

```json
{
  "hits": [
    {
      "id": "9781234567890",
      "score": 8.42,
      "document": { "name": "Silent Spring", "price": 19.5 },
      "highlights": { "name": ["<em>Silent</em> Spring"] }
    }
  ],
  "total": { "count": 128, "exact": false },
  "page": { "limit": 20, "offset": 0, "next": "AW8..." },
  "tookMs": 7.412
}
```

| Property | Type | Description |
|---|---|---|
| `hits` | Array | Array of hit objects matching the query. Each hit contains `id`, `score` (omitted if the search computed no scores), `document` fields, and optional `highlights`, `matched`, `index`, or `value` properties. |
| `total` | Object | Match count object containing `count` (integer) and `exact` (boolean indicating whether `count` is exact or a lower bound). Counted in whatever the search returns, so a document expanded by `hits.when` counts once per value. |
| `documents` | Object | Total count of matching documents, in the same shape as `total`. Present only when `hits.when` is set; omitted otherwise. |
| `facets` | Object | Map of facet names to facet results. Omitted if `facets` was not requested. |
| `page` | Object | Pagination state containing `limit`, `offset` (omitted when navigating via cursor), and optional `next` and `previous` cursor strings. |
| `relaxed` | Object | Details of dropped terms when query relaxation was applied. Omitted if the query was not relaxed. |
| `tookMs` | Number | Execution time for the search request in milliseconds. |

### Locale specific fields

A [locale specific field](../how-to/localize-fields.md) returns the single variant matched for the query locale:

```json
"document": {
  "id": "1",
  "name": { "sv": "röda löparskor" }
}
```

- The object key is the declared variant that was read (for example, `sv` when queried with `sv-SE`).
- If a document contains no value for that variant, the field is omitted from `document`.
- If the index is configured to backfill unpopulated locales, the backfilled value is returned.

### Numbered pages

Requesting `"pages": {}` adds a `pages` object inside `page`:

```json
"pages": {
  "count": 7,
  "previous": { "number": 1, "cursor": "..." },
  "next": { "number": 3, "cursor": "..." },
  "start": [ { "number": 1, "cursor": "..." }, { "number": 2, "cursor": "...", "current": true } ],
  "end": [ { "number": 7, "cursor": "..." } ]
}
```

Page metadata is divided into `start`, `middle`, and `end` arrays. The `current` boolean marks the current page. `end` is omitted if the final page exceeds maximum page depth. Page numbers are 1-based.

## Explaining a result

Explains how a specific document or value hit scores for a search query.

```
POST /v1alpha1/indexes/{name}/search/actions/explain?key={key}&index={index}
```

The endpoint requires the `search` permission. Any node answers the request using the generation that node last pulled, without forwarding to the indexer.

### Parameters

- `name` (path parameter, required): The index name. Can include a generation suffix, such as `books@2`.
- `key` (query parameter, required): The primary key of the document, formatted according to the type of the key field. For value hits, provide the primary key of the parent document.
- `index` (query parameter, optional): Zero-based index of the value along the `hits.path` to explain. Defaults to `0`. Read only when searching for value hits.

### Request body

The request body accepts the same JSON search request as `POST /v1alpha1/indexes/{name}/search`.

The endpoint compiles the search using the same clauses, locale, and index search settings to produce the exact score reported by a search.

The following request properties are read:

- `query`
- `filters`
- `locale`
- `signals`
- `hits.path`

The following request properties are ignored: `limit`, `offset`, `after`, `before`, `sort`, `facets`, `highlight`, `matched`, `fields`, and `total`. If the search request specifies a field sort, the endpoint still computes and explains the relevance score.

### Response

```json
{
  "matched": true,
  "score": 7.42,
  "detail": {
    "matched": true,
    "score": 7.42,
    "description": "sum of:",
    "children": [
      {
        "matched": true,
        "score": 5.10,
        "description": "weight(title:bok) [BM25Similarity], result of:",
        "clause": "query[0]",
        "clauseType": "text",
        "field": "title",
        "usage": "matching",
        "locale": "sv",
        "children": []
      },
      {
        "matched": true,
        "score": 1.30,
        "description": "signals, product of:",
        "children": [
          {
            "matched": true,
            "score": 1.24,
            "description": "signal popularity (saturation, pivot 10.0, weight 1.0) reads 412.0",
            "children": []
          }
        ]
      }
    ]
  }
}
```

Top-level response properties:

| Property | Type | Description |
|---|---|---|
| `matched` | Boolean | Whether the hit satisfies the search. A hit that does not match appears in no search results. |
| `score` | Number | The relevance score of the hit. Returns `0` if the hit does not match. |
| `detail` | Object | Root score step explaining how the score was calculated. |
| `relaxed` | Object | Relaxation details containing `dropped` words and the effective query `text`. Omitted if query relaxation did not run. |

Properties of a score step (`detail` and each entry in `children`):

| Property | Type | Description |
|---|---|---|
| `matched` | Boolean | Whether this step was satisfied. A non-matching step contributes nothing to the parent score. |
| `score` | Number | Score contributed by this step to its parent step. Returns `0` if the step did not match. |
| `description` | String | Human-readable explanation of the step. |
| `clause` | String | Path to the clause in the request body that produced this step (for example, `query[0]`, `filters[0]`, or `query[0].filter[1]`). Omitted when the step is not an individual clause. |
| `clauseType` | String | Clause type matching request syntax (`field`, `text`, `knn`, `nested`, `and`, `or`, `not`, or `boost`). Omitted when `clause` is omitted. |
| `field` | String | Index definition field name evaluated by the step. Omitted when the step reads no fields or multiple fields. |
| `usage` | String | Field usage mode evaluated by the step (such as `matching`, `filter`, `autocomplete`, `matching_exact`, `hierarchy`, or `vector`). Omitted when `field` is omitted. |
| `locale` | String | BCP-47 locale tag of the field variant evaluated by the step. Omitted for fields that store a single variant across all languages. |
| `children` | Array | Child score steps that compose this step. Empty for leaf steps. |

### Scoring behavior

- **Non-matching hits**: Hits that do not match the query return `matched: false` and `score: 0`. Clause steps that failed return `matched: false`, while clauses that matched return `matched: true`.
- **Field names**: Field names in the explanation tree correspond to schema names in the index definition rather than internal engine names.
- **Query relaxation**: When zero results trigger query relaxation, `relaxed` is included and the explanation tree reflects the relaxed query that executed.
- **Ranking signals**: Signals appear under a dedicated step with one child per signal, specifying the field, function shape, weight, and value read from the document. A missing signal value contributes a factor of `1`.
- **Value hits**: When `hits.path` targets a nested object field, each value is explained individually by specifying its zero-based position in `index`. With `hits.when` set, `index` is read only for documents that `when` matches; a document that returns as itself is explained as a document, whatever `index` says. See [What a hit stands for](#what-a-hit-stands-for).
- **Alternatives that did not match**: Within an `or` clause that matched, only the alternatives that matched appear as steps. An `or` that matched nothing is reported as one non-matching step for the clause itself.

### Errors

| HTTP status | Error code | Description |
|---|---|---|
| `400` | `index:no_primary_key` | The index declares no primary key. |
| `400` | Search error codes | The request body is not a valid search request. Returns the same error codes as `POST .../search`. |
| `401` | Authentication errors | Request lacks valid authentication. |
| `403` | Authorization errors | Missing the `search` permission. |
| `404` | `index:explain:document_not_found` | No document exists with the specified `key`. |
| `404` | `index:explain:value_not_found` | The document contains no value at `index` along the `hits.path`. |
| `409` | `index:no_live_generation` | No live index generation is available. |
| `503` | `index:closed` | The index is closed. |

## Paging rules

- `offset` cannot exceed `EXOFIND_SEARCH_MAX_PAGE_DEPTH`. Requests exceeding this limit return `search:page:too_deep`.
- `next` and `previous` cursors encode result positions rather than count offsets. Cursor navigation is uncapped by depth. Cursors are bound to the sort configuration of the original query; using a cursor with a different sort returns `search:cursor:sort_mismatch`.
- Cursors inside `pages` encode count offsets and remain subject to `EXOFIND_SEARCH_MAX_PAGE_DEPTH`.
- `pages` can be combined with `offset` or page cursors, but cannot be combined with `after` or `before`.
