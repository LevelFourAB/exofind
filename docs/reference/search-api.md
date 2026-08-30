# Search API

Executes a search query against an index on the node that receives the request.

```
POST /v1alpha1/indexes/{name}/search
```

The endpoint also has a generated page stating every field it accepts and returns. See [Search an index](https://exofind.dev/api/operations/search/).

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
| `signals` | Array | Index ranking signals | Document ranking signals used to adjust relevance scoring. Added to the signals configured on the index unless `signalsMode` says otherwise. See [Signals](#signals). If omitted, uses the ranking signals configured on the index. |
| `signalsMode` | String | `add` | How `signals` meets the ranking configured on the index: `add` ranks by both, `replace` ranks by `signals` alone. Supplying this without `signals` returns `search:signal:mode_without_signals`. See [Signals](#signals). |
| `rescore` | Object | None | Reorders the best results of a search in a second pass without changing which documents matched. See [Rescoring](#rescoring). |
| `locale` | String | Field defaults | BCP-47 locale tag used to read and return locale-specific fields. Matches the closest declared locale on each field (for example, `sv-SE` falls back to `sv`). If no matching variant exists, uses the field default. |
| `fields` | Array | All stored fields | Document fields to return with each result. Fields inside an [`object`](field-types.md#object) are specified by dotted path and returned nested inside the object. Requesting unretrievable fields returns an error (see [Document source](field-types.md#document-source)). The primary key is always included. |
| `highlight` | Object | None | Fields to return highlighted snippets for. See [Highlighting](#highlighting). |
| `matched` | Object | None | Nested object fields for which to return matched values with each hit. See [Matched values](#matched-values). |
| `hits` | Object | None | Specifies an object field whose matched values return as individual hits instead of full documents. See [What a hit stands for](#what-a-hit-stands-for). |
| `limit` | Integer | `10` | Maximum number of results to return, at most `EXOFIND_SEARCH_MAX_LIMIT` (default `1000`). Setting `limit` to `0` returns the total match count without hits. |
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
- `typos`: Typo tolerance handling: `"auto"` (default) follows each field's `typoTolerance` configuration and the [typo exclusions](admin-api.md#typo-exclusions) in the search settings of the index; `"off"` disables typo tolerance for the clause.
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
- `k`: Number of nearest documents to return, at most `EXOFIND_SEARCH_MAX_KNN_K` (default `1000`).
- `filter`: Array of filter clauses that documents must satisfy before nearest-neighbor evaluation.

To search a vector field inside a nested object, place the `knn` clause inside a [`nested`](#nested) clause for that path. See [Searching vectors inside a nested path](#searching-vectors-inside-a-nested-path).

### `fuse`

Matches documents across several rankings, scored and merged by rank:

```json
{ "type": "fuse", "depth": 200, "rankConstant": 60,
  "rankings": [
    { "clauses": [ { "type": "text", "text": "waterproof jacket", "fields": { "name": null } } ] },
    { "clauses": [ { "type": "knn", "field": "embedding", "vector": [0.1, 0.2], "k": 200 } ], "weight": 0.5 }
  ],
  "filter": [ { "field": "inStock", "match": { "value": true } } ] }
```

- `rankings`: Array of rankings to run and merge. Each entry contains a `clauses` array combined with an implicit `AND`, and an optional `weight` (default `1`).
- `depth`: Number of results read from each ranking. Defaults to `100`, and is at most `EXOFIND_SEARCH_MAX_FUSE_DEPTH` (default `1000`).
- `rankConstant`: Constant added to each rank before it is inverted. Defaults to `60`.
- `filter`: Array of clauses that narrow every ranking before it is cut to `depth`.

Each ranking runs independently. Documents are scored by the sum of `weight / (rankConstant + rank)` across the rankings that reached them, where rank 1 is the top result of a ranking. Because the clause reads only result positions, scores from different scales (such as BM25 text relevance and vector similarity) combine without normalization.

Reciprocal rank fusion provides the following behaviors:

- Documents that rank well across multiple rankings outrank documents placed first in only one ranking.
- Poorly performing rankings contribute noise rather than dominating results. This makes it safe to fuse rankings from unverified sources, such as vectors generated from user profiles.
- The fused score is close to `1 / rankConstant`. A scoring clause placed beside the `fuse` clause adds its own score scale on top. Rank with the `fuse` clause and use clauses beside it for filtering.
- Lower `rankConstant` values increase the weight of the highest-ranked results in each ranking. Higher values flatten the difference across ranks, giving more weight to documents found by multiple rankings.
- The `weight` property scales a ranking's contribution relative to other rankings. It cannot reorder results within that ranking, because child clauses determine ranking order.
- Conditions that every result must satisfy belong in `filter` rather than beside the `fuse` clause. A `knn` clause inside a ranking applies `filter` entries as a pre-filter, ensuring the vector ranking returns `k` results. Clauses placed beside the `fuse` clause filter the merged list after each ranking is cut to `depth`, which can produce fewer results.

The `fuse` clause is a top-N clause that matches at most `depth` results per ranking:

- Total match counts reflect the merged list, not all matching documents in the index.
- Facet counts aggregate only documents in the merged list.
- Pagination cannot exceed the merged list. Set `depth` high enough to cover all requested result pages.
- Providing an explicit `sort` orders the merged list by that sort instead of the fused score.
- The [`explain` endpoint](#explaining-a-result) reports the rank assigned by each ranking and its contributed score.
- Each pass over a fused query runs its child rankings again. Calculating facet counts on a fused search executes child rankings multiple times.

Fusing rules and error conditions:

- Specifying fewer than two rankings returns `search:clause:rankings_invalid`.
- Specifying a ranking with no clauses returns `search:clause:ranking_empty`.
- Setting a ranking `weight` below `0` or to a non-finite number returns `search:clause:weight_invalid`.
- Setting `depth` below `1` returns `search:clause:depth_invalid`.
- Setting `rankConstant` to `0`, below `0`, or to a non-finite number returns `search:clause:rank_constant_invalid`.
- Specifying a `knn` clause inside a ranking of a search with [`hits`](#what-a-hit-stands-for) returns `search:hits:with_knn`.

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

A `nested` clause must target a `nested` object field path. Using a `nested` clause on a flattened object field returns `index:query:nested:flattened`. Using a `nested` clause on a non-object field returns an error. Child clauses may include `field`, `text`, `knn`, `and`, `or`, `not`, and `boost`. Including a root-level clause such as another `nested` or a `fuse` clause returns `index:query:nested:unsupported_clause`.

A `text` clause inside a `nested` clause searches across all fields in the nested path when `fields` is omitted:

```json
{ "type": "nested", "path": "variants", "score": "total", "clauses": [
  { "type": "text", "text": "waterproof leather" }
] }
```

#### Searching vectors inside a nested path

A `knn` clause inside a `nested` clause searches a vector field defined within that nested path:

```json
{ "type": "nested", "path": "chunks", "clauses": [
  { "type": "knn", "field": "chunks.embedding", "vector": [0.1, 0.2], "k": 20,
    "filter": [ { "field": "chunks.lang", "match": { "value": "en" } } ] }
] }
```

- `k` counts nested values rather than documents. A document that contains multiple nearest values occupies multiple positions in `k`.
- The `knn` `filter` names fields inside the path and narrows candidate values before the nearest are picked.
- Clauses placed beside the `nested` clause narrow afterwards and can return fewer than `k` results.
- Naming a field of the index inside the `nested` clause returns `index:query:nested:not_in_path`.
- Naming a nested vector field outside a `nested` clause returns `index:query:nested:outside`.

To return matched nested values as individual hits, set [`hits`](#what-a-hit-stands-for) to the nested path.

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

A search request adds its signals to the ranking configured on the index. The `signalsMode` property controls this:

| Value | Behavior |
|---|---|
| `add` (default) | Ranks by the index's signals and the request's signals together. A request signal that names the same field as an index signal replaces that index signal instead of compounding with it. |
| `replace` | Ranks by the request's signals alone. An empty array then ranks results solely by text match score. |

Use `add` to layer a per-request signal, such as user affinity, on top of ranking the index owns. A later change to the index's ranking then still reaches these searches. Use `replace` to try out a complete ranking before adopting it:

```json
"signals": [ { "field": "brandAffinity", "saturation": { "pivot": 5 } } ],
"signalsMode": "add"
```

Omitting `signals` leaves the search to the index's ranking, whatever `signalsMode` says. Supplying `signalsMode` without `signals` returns `search:signal:mode_without_signals`.

Signals apply only when results are ordered by relevance. Providing an explicit `sort` overrides signal ordering.

Targeting an unknown field returns `index:query:field_not_found`. Targeting a field without sorting enabled returns `index:query:usage_not_enabled`. Specifying a signal function unsupported by the field type returns `index:invalid-query-type`.

## Rescoring

Rescoring reorders the best results of a search in a second pass without changing which documents matched.

The first pass ranks every match by relevance. The best `window` results of that pass are scored again by the boosts and signals in the `rescore` block. The final score is `first + weight * second`. Results below `window` keep their first-pass relevance score.

```json
"rescore": {
  "window": 200,
  "boost": [ { "field": "brand", "match": { "value": "aurora" } } ],
  "signals": [ { "field": "purchases", "saturation": { "pivot": 50 } } ],
  "weight": 0.5
}
```

The `rescore` block is configured in the search request only. It is not stored on the index or in search settings. Facets and total counts are computed during the first pass; rescoring does not change facet counts or totals.

| Option | Type | Default | Description |
|---|---|---|---|
| `window` | Integer | Required | Number of best results to score a second time. Must be at least `offset` plus `limit`, and at most `EXOFIND_SEARCH_MAX_RESCORE_WINDOW` (default `1000`). |
| `boost` | Array | `[]` | Clauses that lift results satisfying them. Clauses do not filter or narrow search hits. Wrap a clause in [`boost`](#boost) to adjust its weight. |
| `signals` | Array | `[]` | Document values taken into the second score, using the same syntax as top-level [`signals`](#signals). Applied to every result in the window. |
| `weight` | Number | `1` | Multiplier applied to the second-pass score before adding it to the first-pass score. Must be a finite number greater than or equal to `0`. |

Rescoring rules and error conditions:

- The `rescore` block must contain at least one `boost` or `signals` entry. An empty block returns `search:rescore:empty`.
- Omitting `window` returns `search:rescore:window_required`.
- Setting `window` below `1` or above `EXOFIND_SEARCH_MAX_RESCORE_WINDOW` returns `search:rescore:window_invalid`.
- Setting `weight` below `0` or to a non-finite number returns `search:rescore:weight_invalid`.
- Specifying `rescore` on a search with [`hits`](#what-a-hit-stands-for) returns `search:rescore:hits_unsupported`.
- Rescoring applies only when results are ordered by relevance. Providing an explicit `sort` overrides rescoring.
- The [`explain` endpoint](#explaining-a-result) ignores `rescore` and explains only the first-pass score.

### Paging a rescored search

The window is ranked from the first result on every request. Paging works differently inside and below the window:

- Inside the window, the `next` and `previous` cursors count results rather than encoding positions. The response reports an `offset` for these pages.
- The `next` cursor from the last page in the window continues below the window. Results there keep the order relevance gave them and receive no second-pass scoring.
- Send the same `rescore` block with each pagination request. Cursors carry positions, not the search that produced them.
- Numbered `pages` stop at the window.
- A request whose `offset` plus `limit` reaches past the window returns `search:rescore:window_too_small`.

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
- A field below a `nested` list highlights only on value hits for that list. Naming a nested field on a search that returns document hits returns `index:query:nested:outside`. See [What a hit stands for](#what-a-hit-stands-for).

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

Field paths in `fields` must reside under the target object path (`search:matched:field_not_inside`) and exist in the schema (`index:query:field_not_found`). If the index has [document source](field-types.md#document-source) set to `none`, a named field must be `stored` (`index:query:usage_not_enabled`). Naming a child object returns `index:query:source_not_kept`.

Response format under each hit:

```json
"matched": {
  "variants": {
    "values": [ { "color": "red", "size": "M", "price": 19.5 } ],
    "totalValues": 3
  }
}
```

- `values`: Array of matched nested values, up to `limit`. If scoring clauses exist within the `nested` clause, values are ordered by score; otherwise, they appear in document order. If document source is `none`, each value contains its `stored` fields, and `values` is omitted when none of the value's fields are stored.
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

Field names in `fields` must be prefixed by `path` (`search:hits:field_not_inside`) and exist in the index (`index:query:field_not_found`). If document source is `none`, a named field must be `stored` (`index:query:usage_not_enabled`). Naming a child object returns `index:query:source_not_kept`.

Hit response structure:

```json
{
  "id": "9781234567890",
  "index": 2,
  "key": "V-2",
  "score": 8.42,
  "value": { "sku": "V-2", "color": "red", "size": "M", "price": 19.5 },
  "document": { "name": "Trail Tee", "brand": "Ridge" }
}
```

- `id`: Primary key of the parent document. Multiple hits share an `id` when a document contains multiple matching values.
- `index`: Zero-based array index of the value in the parent document. Present on every value hit.
- `key`: Value of the key field when the object field declares a `key`. Present even when `hits.fields` does not request the key child field. Omitted for document hits, for fields with no key, and on an index whose source is `none` when the key child field is not `stored`. See the [`object`](field-types.md#object) section of the field types reference for how to declare a key.
- `value`: The matched nested value object. If document source is `none`, it contains the value's `stored` fields, and is omitted when none of the value's fields are stored.
- `document`: Selected fields of the parent document per the search request's `fields` property.

The identity of a value hit is `id` combined with `key` where a key is declared, and `id` combined with `index` otherwise. The `key` survives a reindex, while `index` does not because reindexing can reorder values. Cursors over value hits still step by position.

Value hit scoring combines the parent document score (including signals) with the nested value's clause score. `sort` can order by `score` or by fields within the nested object path. Specifying index root fields in `sort` returns `index:query:hits:sort_unsupported`; specifying distance sort returns `search:hits:distance_sort`. Index tie-breaker sorts are ignored.

Setting `hits` cannot be combined with:

- `matched` (`search:hits:with_matched`)
- `knn` clauses on a field of the index (`search:hits:with_knn`)

A `knn` clause inside a `nested` clause for the same path is allowed, and makes the nearest values the hits. See [Searching vectors inside a nested path](#searching-vectors-inside-a-nested-path).

`highlight` can name fields inside `path`, and each value hit returns fragments cut from its own value. Naming any other field returns `search:hits:with_highlight`. A document returning as a document hit under `when` carries no fragments. See [Highlight matches inside sub-documents](../how-to/use-sub-documents.md#highlight-matches-inside-sub-documents).

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
| `hits` | Array | Array of hit objects matching the query. Each hit contains `id`, `score` (omitted if the search computed no scores), `document` fields, and optional `highlights`, `matched`, `index`, `key`, or `value` properties. |
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

The following request properties are ignored: `limit`, `offset`, `after`, `before`, `sort`, `facets`, `highlight`, `matched`, `fields`, `rescore`, and `total`. If the search request specifies a field sort, the endpoint still computes and explains the relevance score. Because `rescore` is ignored, the explained score is the one the first pass gave, without the second pass.

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
| `503` | `search:timeout` | The search behind the explanation ran longer than `EXOFIND_SEARCH_TIMEOUT`. |

## Paging rules

- `offset` cannot exceed `EXOFIND_SEARCH_MAX_PAGE_DEPTH`. Requests exceeding this limit return `search:page:too_deep`.
- `next` and `previous` cursors encode result positions rather than count offsets. Cursor navigation is uncapped by depth. Cursors are bound to the sort configuration of the original query; using a cursor with a different sort returns `search:cursor:sort_mismatch`.
- Cursors inside `pages` encode count offsets and remain subject to `EXOFIND_SEARCH_MAX_PAGE_DEPTH`.
- `pages` can be combined with `offset` or page cursors, but cannot be combined with `after` or `before`.
- A search carrying a `rescore` block pages by counting inside the window and by cursor below it. See [Paging a rescored search](#paging-a-rescored-search).

## Request limits

A node caps what one request may ask it to do. Each cap is a configuration variable, so a deployment can set it higher or lower. For the settings and their defaults, see [Search configuration](configuration.md#search).

| Setting | Applies to | Error code |
|---|---|---|
| `EXOFIND_SEARCH_MAX_LIMIT` | `limit` | `search:limit:too_large` |
| `EXOFIND_SEARCH_MAX_PAGE_DEPTH` | `offset` plus `limit` | `search:page:too_deep` |
| `EXOFIND_SEARCH_MAX_RESCORE_WINDOW` | `rescore.window` | `search:rescore:window_invalid` |
| `EXOFIND_SEARCH_MAX_KNN_K` | `k` of a `knn` clause | `search:clause:k_too_large` |
| `EXOFIND_SEARCH_MAX_FUSE_DEPTH` | `depth` of a `fuse` clause | `search:clause:depth_too_large` |
| `EXOFIND_SEARCH_MAX_CLAUSES` | Clauses in `query`, `filters`, `hits.when`, and `rescore.boost`, counted together | `search:query:too_many_clauses` |
| `EXOFIND_SEARCH_MAX_CLAUSE_DEPTH` | Nesting of clauses inside clauses | `search:query:too_deep` |

Each of these returns `400`, and the `path` of the error names where in the body the request went over. A request over `EXOFIND_SEARCH_MAX_CLAUSES` or `EXOFIND_SEARCH_MAX_CLAUSE_DEPTH` is answered with that error alone; the rest of the body is not read.

A search that passes these caps and then runs longer than `EXOFIND_SEARCH_TIMEOUT` returns `503` with `search:timeout`. The results it collected are dropped, so narrow the search instead of repeating it.
