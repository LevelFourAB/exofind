# Defining an index

This guide shows you how to define an index schema and apply it to your cluster. Use this guide when creating a new index or updating an existing index definition.

## Prerequisites

Before you define an index, ensure you have:

- Access to the cluster admin API.

## Defining the index

1. Prepare your index definition in JSON. Keep the definition in version control so you can apply it whenever it changes.

2. Send the definition to the admin API with a `PUT` request.

   Start by defining fields using roles. A role names what a field is for and expands into the usages that serve it. For more information, see [Field roles](../reference/field-types.md#field-roles).

   ```http
   PUT /v1alpha1/admin/indexes/products
   Content-Type: application/json

   {
     "metadata": {
       "owner": "search-team"
     },
     "fields": {
       "id": {
         "type": "string",
         "role": "id"
       },
       "name": {
         "type": "string",
         "role": "title"
       },
       "description": {
         "type": "string",
         "role": "description"
       },
       "sku": {
         "type": "string",
         "role": "code"
       },
       "category": {
         "type": "string",
         "role": "path"
       },
       "publishedAt": {
         "type": "timestamp",
         "role": "timestamp"
       },
       "published": {
         "type": "boolean",
         "filter": {}
       },
       "metadata.*": {
         "type": "string",
         "filter": {}
       }
     }
   }
   ```

   You can send the request to any node in the cluster. Definitions are applied on the node that holds the index, and any other node forwards the request automatically. For more information, see the [admin API reference](../reference/admin-api.md).

   The definition represents the desired state. Send the definition in full, because any omitted fields are removed.

   If the definition is invalid, the server rejects the request and reports all errors found. For more information, see [Errors](../reference/errors.md).

   A `PUT` request applies only to documents indexed after the request. If the index already contains documents and a change alters how values are indexed (such as adding `matching`, changing an analyzer, or editing a synonym set), perform [a rollout through a new generation](roll-out-a-definition-change.md) instead.

3. If you update an existing index concurrently, include the `If-Match` header.

   To avoid overwriting changes made by another client, include the `ETag` value from a previous `GET` or `PUT` response:

   ```http
   PUT /v1alpha1/admin/indexes/products
   If-Match: "9f2c1a0b3d4e5f60"
   ```

   If another client updated the index in the meantime, the server returns a `412 Precondition Failed` error.

## Confirming the result

To verify that the index definition was applied:

- Check the response of the `PUT` request. The response returns the definition and includes an `ETag` header with the current version.
- Alternatively, send a `GET` request to the index endpoint to inspect the active definition and its `ETag` header.

## Configuration reference

### Pick what each field is for

Each field has a `type` that defines what data it can hold. Field capabilities are opt-in: including a configuration object enables the capability, and an empty object uses engine defaults.

The following capabilities behave consistently across field types:

- `filter`: Narrows results to documents matching a value. Filtering compares exact values. For string fields, case folding is applied by default, so filtering on `Fiction` also matches `fiction` (set `keyword.caseFolding` to turn this off). For number and timestamp fields, filtering also supports ranges. For geo points, filtering supports distance.
- `sort`: Orders results by the field value. Strings are compared according to locale rules rather than byte order, so `Äpple` sorts before `Zebra`. To compare bytes directly, set `sort.collation` to `binary`.
- `facet`: Counts how many documents share each value, providing values for filter lists requested through the search API [`facets`](../reference/search-api.md#facets) parameter. For number and timestamp fields, counts can be grouped into [range buckets](../reference/search-api.md#range-buckets) for price or date facets.

String fields also support capabilities that depend on text analysis: `matching` searches with a query, `autocomplete` matches prefix text as a user types, and `hierarchy` interprets values as tree paths (such as `Men/Shoes/Running`) so facets count [one level at a time](../reference/search-api.md#counting-down-a-tree). For a complete list of options for every type, see [Field types](../reference/field-types.md).

#### Field roles

A role is expanded before storage, so reading the definition back shows the usages rather than the role. Anything set beside a role is kept, taking the specified property from the definition and the remaining properties from the role:

```json
"name": {
  "type": "string",
  "role": "title",
  "matching": { "weight": 8 }
}
```

### Enable more than one value

A field holds a single value unless you set `"multiple": true`. If a document provides multiple values for a single-valued field, the server rejects the document. A locale-specific field holds one value per locale by default; setting `multiple` allows multiple values within the same locale.

### Group fields that belong together

An `object` field holds nested fields, defined using the same schema structure as the index. A structural group—such as `dimensions` containing `width` and `height`—is a single object. Its fields are accessible using dotted paths such as `dimensions.width` without extra configuration:

```json
"dimensions": {
  "type": "object",
  "fields": {
    "width": { "type": "double", "filter": {} },
    "height": { "type": "double", "filter": {} }
  }
}
```

A list of objects must define how its values are processed. If fields match independently in a flattened list, a query for "red and under 20" matches a product that has a red variant and a separate cheap variant. To match fields within the same object, set `"mode": "nested"`. A search then evaluates one variant at a time through the `nested` clause of the [search API](../reference/search-api.md#nested):

```json
"variants": {
  "type": "object",
  "multiple": true,
  "mode": "nested",
  "fields": {
    "color": { "type": "string", "filter": {} },
    "price": { "type": "double", "filter": {} }
  }
}
```

When you configure `sort`, `facet`, or `matching` on nested fields, the matching variant can sort, count, or rank the parent document. For example, finding the cheapest red variant sorted by price requires a `nested` clause and a sort on `variants.price`. If the values in a list are independent, use `"mode": "flattened"` instead. For details on each mode, see [Field types](../reference/field-types.md#object). To learn how to index, search, and update nested values, see [Use sub-documents](use-sub-documents.md).

### Cover many names with one field

A field name can contain a wildcard `*` to define multiple fields at once, such as `metadata.*`. The `*` matches exactly one name segment. When patterns overlap, the pattern with the longer literal prefix takes precedence. For full matching rules, see [Field types](../reference/field-types.md#wildcard-fields). Every distinct name a pattern accepts becomes a field of its own in the index, and `facet` and `sort` keep per-field structures on top of that, so the set of names must stay bounded and owned by the catalogue rather than by whatever document arrives. To hold attributes that are not named in advance, see [Model dynamic attributes](model-dynamic-attributes.md).

A wildcard field is also a starting point while the shape of a catalogue is still settling. A single `*` field of type `string` with `matching` set makes every string a document carries searchable, so the index answers queries before its fields are named:

```json
{
  "fields": {
    "id": { "type": "string", "role": "id" },
    "*":  { "type": "string", "matching": {} }
  }
}
```

Treat this as a starting point rather than a destination. Every distinct name still becomes a field of its own, so keep it only while the names come from a catalogue you control, and replace it with named fields as the shape settles. To keep attributes that are never named in advance, use the typed namespaces in [Model dynamic attributes](model-dynamic-attributes.md) instead.

### Decide how much of a document is kept

By default, the index stores a full copy of every indexed document. This allows search results to return the original values and lets the system reindex documents directly when definitions change. If documents are large and search results only need to identify them, disable full source storage and specify the fields to store:

```json
{
  "source": "none",
  "fields": {
    "id": { "type": "string", "primaryKey": true, "required": true },
    "name": { "type": "string", "stored": true }
  }
}
```

Changing this setting does not rewrite existing documents. It applies only to documents written after the change.

### Put the thing named what was typed above the things that mention it

To boost documents where a field value matches the search query exactly over documents that merely mention the terms, configure `exact` and `lengthNormalization` on the field:

```json
"name": {
  "type": "string",
  "matching": {
    "exact": {},
    "lengthNormalization": "strong"
  }
}
```

`exact`: Indexes the full value as a single term and boosts documents where the entire field matches the query. This setting reorders results without changing hit counts or facets. It takes effect when the user types the full field value.

`lengthNormalization`: Penalizes extra terms in a field value beyond the searched terms. Use `"strong"` for title or name fields, where additional words indicate a related item rather than an exact match. Use `"none"` for body or description fields, where longer text does not imply a worse match. This setting does not affect stored data and can be changed without reindexing.

Configure `exact` on fields that represent the primary identity of the document. Do not configure `exact` on description fields or tag lists, where it can boost unintended short values.

### Break ties in the order of results

To establish a consistent order for results with equal relevance scores, define tie breakers in the `ranking` configuration:

```json
"ranking": {
  "tieBreakers": [
    { "field": "name", "direction": "ascending" }
  ]
}
```

The engine evaluates tie breakers after applying the sort order requested by a search query. Each field used as a tie breaker must have sorting enabled.

### Rank what sells, or what is new, above the rest

To adjust document relevance scores using document attributes, define ranking signals:

```json
"ranking": {
  "signals": [
    { "field": "purchases", "saturation": { "pivot": 50 } },
    { "field": "published", "decay": { "halfLife": 604800 }, "weight": 0.5 }
  ]
}
```

The score is multiplied by `1 + weight * shape`, where `shape` is a value between 0 and 1:

- `saturation`: Scales a numeric value based on its distance from the `pivot`. A document with a value equal to the pivot receives half of the maximum boost.
- `decay`: Evaluates a timestamp by how much time has elapsed, halving the score every `halfLife` seconds.

Documents with no value in the signal field retain their original match score.

Choose a `pivot` value that represents a typical popular item in your catalog rather than the highest value. Fields used in signals must have sorting enabled. Signals do not rewrite stored documents, so you can add, modify, or remove them without reindexing.

## Related

- [Field types](../reference/field-types.md) - The types a field can have and the ways each can be used.
- [Admin API](../reference/admin-api.md) - The definition endpoints, conditional requests, and index states.
- [Index documents](index-documents.md) - Sending documents once the index exists.
- [Customize text analysis](customize-analysis.md) - Presets, custom chains, and shared resources.
- [Localize fields](localize-fields.md) - Holding values in several languages.
- [Use sub-documents](use-sub-documents.md) - Fields whose values are documents of their own.
- [Roll out a definition change](roll-out-a-definition-change.md) - Changing a definition for documents already indexed.
- [Relevance](../explanation/relevance.md) - How boosts, tie breakers, and signals order results.
