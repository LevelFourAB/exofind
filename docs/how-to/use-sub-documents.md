# Using sub-documents

This guide shows you how to define, index, query, and update sub-documents in an index. Use sub-documents when multiple fields inside an object must match together within a single value, such as product variants, order lines, or opening hours.

If you only need structural grouping without matching multiple fields in the same item, use `"mode": "flattened"`. Flattened fields fold into the document under dotted paths and do not require a `nested` clause or join. For details on modes, see [`object` fields](../reference/field-types.md#object).

## Prerequisites

Before you use sub-documents, ensure you have the following:

- A node with the `type.object` feature enabled. If you use usages beyond `filter` inside the object, the node also requires the `type.object.usages` feature. If you use a wildcard in a field name, it also requires `type.object.wildcard`.
- An index where you can define fields, or permissions to create a new index.

## Define the object field

To store sub-documents, define an object field with `"type": "object"`, `"multiple": true`, and `"mode": "nested"`:

```json
"variants": {
  "type": "object",
  "multiple": true,
  "mode": "nested",
  "fields": {
    "color": { "type": "string", "filter": {}, "facet": {}, "required": true },
    "size": { "type": "string", "filter": {}, "multiple": true },
    "price": { "type": "double", "filter": {}, "sort": {}, "facet": {} },
    "material": { "type": "string", "matching": {} }
  }
}
```

Keep the following rules in mind when you configure the field:

- Setting `multiple: true` makes the field a list. A list field must specify its `mode`. In flattened mode, conditions such as `color = red` and `price < 20` can match two different variants. Therefore, list fields do not have a default mode.
- A field without `multiple: true` holds a single value, always flattens, and does not accept a `mode`. If a document provides multiple values for a non-multiple field, the engine rejects the document with `index:update:not_multiple`. Use non-multiple objects for grouped fields that represent a single unit, such as a `dimensions` object with `width` and `height`.
- Define fields inside the object using the same options as top-level index fields. Inner fields support `filter`, `matching`, `autocomplete`, `sort`, `facet`, `stored`, `highlight`, `locales`, `validation`, `required`, and `multiple`. Setting `required: true` on an inner field makes that field required in every sub-document value.
- Inner fields do not support `primaryKey`, which names the document itself. Highlighted fragments of inner fields return only on value hits. See [Highlight matches inside sub-documents](#highlight-matches-inside-sub-documents).
- An inner field can be an object itself: a single object or a `flattened` list. A `nested` list inside a `nested` list is rejected with `index:field:object:nested_in_nested`. For the full position rules, see [Constraints and restrictions](../reference/field-types.md#constraints-and-restrictions).
- An inner field name can contain `*`, which accepts attributes you do not define in advance. See [Accept attributes you did not define](#accept-attributes-you-did-not-define).
- The parent `object` field holds no direct value. Setting `filter`, `sort`, `facet`, `locales`, or `stored` on the parent object is rejected.

**Note:** Adding a usage to an inner field on an index that already contains documents applies only to values indexed after the change. To apply the usage to existing documents, roll out the change to [a new generation](roll-out-a-definition-change.md).

## Index documents with sub-documents

Write each sub-document as a JSON object, and write a list of sub-documents as a JSON array:

```http
POST /v1alpha1/indexes/products/documents
Content-Type: application/x-ndjson

{"id": "1", "name": "Rain jacket", "variants": [{"color": "red", "size": ["S", "M"], "price": 15.0}, {"color": "black", "price": 25.0}]}
```

The field definition determines how the engine interprets a JSON object. For details, see [How a document is shaped](../reference/documents-api.md#how-a-document-is-shaped).

The engine validates sub-document values using these rules:

| Condition | Error code |
| --- | --- |
| A value that is not an object | `index:update:not_a_document` |
| An object provided for a field that is not an object | `index:update:unexpected_document` |
| A field that the object does not declare | `index:update:field_not_found` |
| A value missing an inner `required` field | `index:update:required_field_missing` |

Documents are written whole. Indexing a document with an existing key replaces all previous values in the object field. Deleting a parent document deletes all of its sub-documents.

Search results return sub-documents from the document copy stored in the index. An object field cannot be set to `stored` on its own. If you define an index with `"source": "none"`, sub-document values contain only the inner fields set to `stored: true`. If no inner fields are stored, search results omit sub-document values.

## Query sub-documents

To require multiple conditions to match within the same sub-document, add a `nested` clause to `query`. Set `path` to the name of the object field:

```json
{
  "query": [
    { "type": "nested", "path": "variants", "clauses": [
      { "field": "variants.color", "match": { "value": "red" } },
      { "field": "variants.price", "match": { "type": "range", "lt": 20 } }
    ] }
  ]
}
```

When building queries with `nested` clauses:

- Reference inner fields by their dotted path (such as `variants.color`). Inner paths resolve only inside a `nested` clause that matches the path. Referencing `variants.color` directly in `query` fails with `index:query:nested:outside`. Referencing it under another object path fails with `index:query:nested:not_in_path`. Top-level index fields cannot appear inside a `nested` clause.
- A `nested` clause supports clauses that run against a single value: `field`, `text`, `knn`, `and`, `or`, `not`, and `boost`. A `nested` clause inside another `nested` clause, or a `fuse` clause inside a `nested` clause, fails with `index:query:nested:unsupported_clause`. An empty `clauses` array matches any document that contains at least one sub-document value.
- A `knn` clause inside a `nested` clause searches a `vector` inner field, which is how a document held as a list of chunks is searched for its nearest chunks. See [Search chunks inside a document](search-by-vector.md#search-chunks-inside-a-document).
- Place `nested` clauses in `query` or `filters` based on how facet counts should behave:
  - Page-level conditions (such as the main search box or "only in stock") belong in `query`. This narrows both the hits and the facet counts.
  - User refinements belong in `filters`. A facet on a filtered field excludes that filter from its counts, keeping other filter values selectable. Place each facet field in a separate `filters` entry. For details, see [Facets](../reference/search-api.md#facets).

By default, search results return entire documents with all sub-documents. To return only the sub-documents that matched the query, add the `matched` field to the request (see [Matched values](../reference/search-api.md#matched-values)):

```json
{
  "query": [
    { "type": "nested", "path": "variants", "clauses": [
      { "field": "variants.color", "match": { "value": "red" } }
    ] }
  ],
  "matched": { "fields": { "variants": {} } }
}
```

The `matched` parameter returns each matching sub-document in full. To return only specific fields from each matched sub-document, specify the field paths: `"variants": { "fields": ["variants.color"] }`.

To return each matching sub-document as an individual search hit rather than returning one hit per document, set `"hits": { "path": "variants" }`. Totals, facets, and cursors then apply to individual sub-documents. You can also specify `fields` to limit which inner fields appear in each hit. For details, see [What a hit stands for](../reference/search-api.md#what-a-hit-stands-for).

To let each document decide whether it comes back as itself or as its sub-documents:

1. Index a filterable boolean field on the document, such as `splitVariants`. Switching a document between the two modes is a field update and needs no reindex.
2. Name that field in a `when` clause inside the `hits` block:

   ```json
   "hits": {
     "path": "variants",
     "when": [ { "field": "splitVariants", "match": { "value": true } } ]
   }
   ```

3. Note the following changes to search results:
   - Documents that match `when` return one hit per matching sub-document. Other matching documents return as a single document hit, so one result page can contain both kinds.
   - Results can be sorted only by `score`.
   - Facets count matching documents rather than sub-documents.
   - The response includes a `documents` object that counts matching documents alongside `total` hits.

For details, see [Expanding only some documents](../reference/search-api.md#expanding-only-some-documents).

## Search text inside sub-documents

To search text across inner fields, place a `text` clause inside a `nested` clause. If you do not specify a field, the clause searches all text fields in that object path. All words must match within the same sub-document value.

A top-level `text` clause searches only top-level document fields. To search both top-level fields and sub-documents in the same query, combine them in an `or` clause:

```json
"query": [
  { "type": "or", "clauses": [
    { "type": "text", "text": "waterproof leather" },
    { "type": "nested", "path": "variants", "score": "total", "clauses": [
      { "type": "text", "text": "waterproof leather" }
    ] }
  ] }
]
```

Use `score` to define how matching sub-documents determine the parent document score: `max` (default), `min`, `avg`, or `total`. The `score` setting applies only when an inner clause scores results.

## Highlight matches inside sub-documents

To return highlighted fragments of inner text fields, configure `highlight` on the inner field and search with value hits (`"hits": { "path": "variants" }`, see [What a hit stands for](../reference/search-api.md#what-a-hit-stands-for)). Each hit represents one sub-document value, and its fragments come from that value alone:

```json
{
  "query": [
    { "type": "nested", "path": "variants", "clauses": [
      { "type": "text", "text": "waterproof" }
    ] }
  ],
  "hits": { "path": "variants" },
  "highlight": { "fields": { "variants.material": {} } }
}
```

When highlighting inner fields:

- Highlighted fields must sit inside the `hits` path. Naming a top-level field fails with `search:hits:with_highlight`.
- A search whose hits are documents cannot highlight an inner field of a `nested` list. Naming an inner field in `highlight` fails with `index:query:nested:outside`. Fragments belong to a value, and only a value hit represents one.
- With [`when`](../reference/search-api.md#expanding-only-some-documents) set, documents that return as whole documents return no fragments.
- Fragments show what the `nested` clauses matched in the value. The options and markers work as described in [Highlighting](../reference/search-api.md#highlighting).

## Sort and facet by sub-document fields

To sort or calculate facets by sub-document fields, specify the dotted field path directly in `sort` or `facets`:

```json
{
  "query": [
    { "type": "nested", "path": "variants", "clauses": [
      { "field": "variants.color", "match": { "value": "red" } }
    ] }
  ],
  "sort": [ { "field": "variants.price", "order": "asc" } ],
  "facets": [ { "field": "variants.color" } ]
}
```

Sorting and faceting evaluate only the sub-document values that match the `nested` query conditions. Clauses inside an `or`, `not`, or `boost` clause do not restrict which values participate. If the query does not filter sub-documents, all sub-document values participate.

An ascending sort orders parent documents by their lowest matching value (for example, the cheapest red variant). A descending sort orders documents by their highest matching value (for example, the most expensive red variant).

Facets count parent documents. For example, a document with three red variants counts as one red document. When a query filters sub-documents by price, facet counts reflect only the colors of matching variants rather than all colors in those documents.

Distance sorting (`distance`) is not supported inside an object and fails with `index:query:nested:sort_unsupported`.

## Update sub-document values

To update sub-documents, send an update request using the `actions/update` endpoint:

```http
POST /v1alpha1/indexes/products/documents/actions/update
Content-Type: application/json

{"documents": [{"id": "1", "variants": [{"color": "red", "price": 12.0}, {"color": "black", "price": 25.0}]}]}
```

Naming the field alone still replaces every value in the object field. To update sub-documents individually, use selector paths:

- A selector path such as `variants[sku=V-2].price` changes one field inside one value.
- A path such as `variants[sku=V-2]` replaces a value whole. Mapping it to `null` removes that value from the list.
- If the object field definition declares a `key`, the path takes the key on its own as `variants[V-2]`.

A bare dotted inner path without a selector (such as `variants.color`) is refused with `request:update:value_required` on a list of objects. For details on updating sub-documents with selector paths, see [Update parts of documents](update-parts-of-documents.md).

When replacing the whole list, if you do not have the existing sub-documents, retrieve them before updating:

```json
{
  "filters": [ { "field": "id", "match": { "value": "1" } } ],
  "fields": ["variants"]
}
```

Set `fields` to `variants` to return all inner fields, or specify a dotted path such as `variants.price` to return a specific inner field. Retrieving a document by key with this method requires the key field to be configured with `filter`.

If the index uses [`source`](../reference/field-types.md#document-source) set to `"none"`, the engine does not store document copies. Sub-document values then return only their inner fields set to `stored: true`. Requesting the whole object field fails with `index:query:source_not_kept`, and requesting an inner field without `stored` fails with `index:query:usage_not_enabled`. Retain source data on indexes where you need to update sub-documents, because a replacement built from partial values drops the fields it never saw.

## Accept attributes you did not define

To accept variant attributes that your catalogue adds over time without changing your index definition, use wildcard inner field names.

1. Define wildcard patterns inside the `fields` object. Because each pattern assigns one data type and one set of usages, group attributes into namespaces by type:

   ```json
   "variants": {
     "type": "object",
     "multiple": true,
     "mode": "nested",
     "key": "sku",
     "fields": {
       "sku": { "type": "string", "filter": {}, "required": true },
       "attr.*": { "type": "string", "filter": {}, "facet": {} },
       "num.*": { "type": "double", "filter": {}, "sort": {} }
     }
   }
   ```

2. Index documents with dynamic attributes inside the variant under names that match your patterns:

   ```http
   POST /v1alpha1/indexes/products/documents
   Content-Type: application/x-ndjson

   {"id": "1", "variants": [{"sku": "V-1", "attr.color": "red", "attr.size": "L", "num.weight": 180}]}
   ```

3. Query the dynamic attributes by their dotted paths inside a `nested` clause:

   ```json
   {
     "query": [
       { "type": "nested", "path": "variants", "clauses": [
         { "field": "variants.attr.color", "match": { "value": "red" } },
         { "field": "variants.attr.size", "match": { "value": "L" } }
       ] }
     ]
   }
   ```

   Both conditions must match within the same variant.

Keep the following rules in mind when using wildcard inner fields:

- An explicit inner field name takes precedence over a pattern. Among patterns, the longest literal prefix wins. If prefixes are equal, the shorter pattern wins.
- A wildcard `*` matches exactly one dot-separated segment. `attr.*` matches `attr.color` but not `attr.a.b`.
- A pattern cannot be configured as `required`, `primaryKey`, or as the `key` of an object list.
- An inner name that matches no declared field and no pattern fails indexing with `index:update:field_not_found`.
- Adding a pattern to an existing index does not require reindexing.
- A `text` clause that specifies no fields skips patterns. To search dynamic attributes from a single search box, copy their values into a declared field configured with `matching`.

To accept dynamic attribute group names rather than individual attributes, define a wildcard on the object field name itself. See [Wildcard names on object fields](../reference/field-types.md#wildcard-names-on-object-fields).

## Confirm the results

To verify that the sub-documents are indexed and queryable, run a search request with a `nested` clause and the `matched` field:

```http
POST /v1alpha1/indexes/products/search
Content-Type: application/json

{
  "query": [
    { "type": "nested", "path": "variants", "clauses": [
      { "field": "variants.color", "match": { "value": "red" } },
      { "field": "variants.price", "match": { "type": "range", "lt": 20 } }
    ] }
  ],
  "matched": { "fields": { "variants": {} } }
}
```

Verify that the response returns the matching parent document and includes only the matching sub-document values under `matched`.

## What the values cost

Each value is a Lucene document of its own, written in the same block as the document holding it. Changing one value rewrites the whole document, and a search asking something of the values joins them back to their documents. Before holding a large list this way, read [How sub-documents are stored](../explanation/document-blocks.md), and measure the layouts against your own data with [`GroupingBenchmark`](benchmark-the-engine.md#comparing-variant-layouts).

## Related

- [`object` fields](../reference/field-types.md#object) - Property reference and supported inner field options.
- [Wildcard names on object fields](../reference/field-types.md#wildcard-names-on-object-fields) - Pattern rules for an inner name and for the object's own name.
- [Modeling dynamic attributes](model-dynamic-attributes.md) - Wildcard fields at the root of a document.
- [The `nested` clause](../reference/search-api.md#nested) - Reference documentation for nested queries, [ordering by](../reference/search-api.md#ordering-by-a-value-inside-an-object), and [counting](../reference/search-api.md#counting-a-value-inside-an-object) sub-document values.
- [Documents API](../reference/documents-api.md#how-a-document-is-shaped) - Document payload format and updating documents.
- [Updating parts of documents](update-parts-of-documents.md) - Change one sub-document without resending the others.
- [Searching by vector](search-by-vector.md) - Vector fields, and searching a document held as a list of chunks.
- [Defining an index](define-an-index.md) - Complete index definition reference.
- [Rolling out a definition change](roll-out-a-definition-change.md) - Reindexing existing documents after schema changes.
