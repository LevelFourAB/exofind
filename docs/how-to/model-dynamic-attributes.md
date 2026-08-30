# Modeling dynamic attributes

This guide shows you how to model, index, query, and update dynamic attributes in an index. Use this guide when your catalogue contains items with attributes that the index definition does not name in advance, such as e-commerce product specifications where new attributes appear over time.

To match attributes together within a single variant value, use sub-documents instead. For details, see [Using sub-documents](use-sub-documents.md).

## Prerequisites

Before you model dynamic attributes, ensure you have the following:

- An index where you can configure fields, or permissions to create a new index.
- A catalogue system that defines and manages attribute names for your items.

## Define typed wildcard namespaces

To accept attribute names that you do not define in advance, add field names that contain `*` to your index definition. Because a pattern carries one type and set of usages, group dynamic attributes into namespaces by data type:

```json
{
  "fields": {
    "sku":  { "type": "string", "primaryKey": true, "required": true },
    "name": {
      "type": "string",
      "matching": { "typoTolerance": {}, "exact": {} },
      "sort": {}
    },

    "attr.*": { "type": "string", "filter": {}, "facet": {}, "multiple": true },
    "num.*":  { "type": "double", "filter": {}, "facet": {}, "sort": {} },
    "flag.*": { "type": "boolean", "filter": {} },
    "date.*": { "type": "timestamp", "filter": {}, "facet": {}, "sort": {} },

    "attrText": { "type": "string", "multiple": true, "matching": {} }
  }
}
```

The engine resolves wildcard field names using these rules:

- The `*` character matches exactly one field name segment. For example, `attr.*` matches `attr.color`, but does not match `attr.a.b`.
- An explicit field definition takes precedence over any matching pattern.
- When multiple patterns match a field name, the pattern with the longest literal prefix wins. If literal prefixes have the same length, the shorter pattern wins.
- A field name in a document that matches no explicit field and no pattern is rejected.
- Wildcard fields cannot serve as the primary key (`index:field:invalid_name:primary_key_wildcard`).
- Wildcard fields cannot be set to `required`.

## Index documents with dynamic attributes

Write dynamic attributes under their matching namespace prefix:

```http
POST /v1alpha1/indexes/products/documents
Content-Type: application/x-ndjson

{"sku": "TT-1", "name": "Trail Tee", "attr.color": "red", "attr.material": "merino", "num.weight": 180, "flag.waterproof": false, "attrText": ["red", "merino"]}
```

New attribute names require no definition updates or reindexing. When a document introduces a new attribute name that matches a namespace pattern (such as `attr.material`), the engine accepts and indexes the field immediately.

When a pattern sets `"multiple": true`, you can provide a single value or an array of values for that attribute.

## Filter, facet, and sort by concrete attribute names

To filter, facet, or sort by a dynamic attribute, specify its full name in the search request:

```json
{
  "filters": [
    { "field": "attr.color", "match": { "value": "red" } },
    { "field": "num.weight", "match": { "type": "range", "lte": 200 } }
  ],
  "facets": [
    { "field": "attr.color" },
    { "field": "attr.material" }
  ],
  "sort": [
    { "field": "num.weight", "order": "asc" }
  ]
}
```

Queries on dynamic attributes support the following operations:

- **Filtering:** The engine resolves field names by checking explicit definitions first, then pattern definitions. Numeric and timestamp namespaces support range matchers.
- **Faceting:** Faceting by an attribute name calculates counts for that specific field. Because each attribute has a distinct field path, selecting a filter such as `attr.color = red` leaves facet counts for other colors intact while narrowing other facets.
- **Sorting:** Sorting works on any namespace pattern configured with `sort`.

## Make attributes searchable in the search box

A general `text` search clause that specifies no `fields` searches all searchable fields in the index, but skips wildcard patterns.

To make dynamic attributes searchable in the main search box:

1. Add a dedicated string field with `multiple: true` and `matching: {}` to your index definition (such as `attrText`).
2. When building documents, copy the text values of your dynamic attributes into this field.
3. Run general text searches without specifying fields, or search specific attributes by name in a `text` clause:

```json
{
  "query": [
    { "type": "text", "text": "merino", "fields": { "attr.material": 1 } }
  ]
}
```

## Update attributes with partial updates

To update or remove individual attributes without rewriting the entire document, use the `actions/update` endpoint:

```http
POST /v1alpha1/indexes/products/documents/actions/update
Content-Type: application/json

{
  "documents": [
    {
      "sku": "TT-1",
      "attr.color": "blue",
      "flag.waterproof": null
    }
  ]
}
```

The update endpoint modifies fields using these rules:

- Fields specified in the request replace their previous values.
- Setting a field value to `null` removes that attribute from the document.
- Unspecified fields retain their existing values.
- The engine validates the updated document as a whole against the index definition.
- The index must retain source data. If the index uses `"source": "none"`, the request fails with `index:source:not_kept`.

## Confirming the result

To verify that dynamic attributes are indexed and queryable, run a search request that filters and facets by your concrete attribute names:

```http
POST /v1alpha1/indexes/products/search
Content-Type: application/json

{
  "filters": [
    { "field": "attr.color", "match": { "value": "red" } }
  ],
  "facets": [
    { "field": "attr.color" },
    { "field": "attr.material" }
  ]
}
```

Verify that the response returns the matching documents and includes facet counts for `attr.color` and `attr.material`.

## Limitations of wildcard fields

Wildcard fields have three limitations:

- **Skipped by general text search:** A `text` clause without explicit `fields` does not search wildcard namespaces. To search dynamic attributes in a single search box, copy their values to an explicit `multiple: true` text field.
- **No field name discovery:** The engine does not provide an endpoint or facet to list all attribute names in use. Your catalogue system must track which attributes exist and request the relevant facets for a given category.
- **No index-level ranking on wildcard names:** You cannot reference wildcard patterns or dynamic attribute names in `ranking.tieBreakers` or `ranking.signals` within the index definition or search settings. Defining a pattern fails with `index:ranking:wildcard_field` or `index:ranking:signal:wildcard_field`, and defining a concrete dynamic name fails as an unknown field. To rank results by a dynamic attribute, specify the concrete field in the `signals` parameter of the search request.

## Choosing where the pattern goes

Choose the placement of a pattern based on where the attributes sit:

- **Attributes on the document:** A root pattern (such as `attr.*`) indexes dynamic attributes directly on the document when attributes do not need to match together within a variant.
- **Attributes on a variant:** A pattern inside a `nested` `object` field indexes dynamic attributes on individual variants so two or more attributes can match together within the same variant. For details, see [Accept attributes you did not define](use-sub-documents.md#accept-attributes-you-did-not-define).
- **Dynamic attribute groups:** A pattern on the `object` field name itself (such as `spec.*`) indexes dynamic attribute groups whose inner fields (such as `value` and `unit`) must match together. For details, see [Wildcard names on object fields](../reference/field-types.md#wildcard-names-on-object-fields).

## Why name and value pairs in nested objects fail for faceting

An alternative model is storing attributes as `{ "name": "...", "value": "..." }` pairs inside a nested `object` field. While a `nested` filter query correctly prevents `color=red` and `material=leather` from cross-matching, this model breaks faceted navigation:

- A facet omits a filter from its counts when the filter path equals or falls under one of the facet's `excludeFilters` paths.
- A `nested` filter clause reading `attributes.name` and `attributes.value` receives the parent path `attributes`.
- A facet on `attributes.value` excludes `attributes.value` by default. Because `attributes` does not equal or fall under `attributes.value`, the filter is not excluded. Selecting a color narrows the counts for other colors.
- Setting `"excludeFilters": ["attributes"]` on the facet excludes the entire nested filter, which also removes filters for other attributes (such as material) from the count.

Because nested objects cannot isolate facet exclusions per attribute, use wildcard field namespaces instead.

## What a definition change costs

- **Adding a new pattern is free:** Adding a new wildcard pattern to an index definition requires no reindexing. Existing documents were not indexed with that pattern, so no stale data exists.
- **Adding usages to an existing pattern requires a rollout:** Enabling a new usage on an existing pattern (such as adding `facet` to `attr.*`) when documents already exist is rejected with `index:definition:usage_added`. To apply the new usage to existing documents, [roll out a new generation](roll-out-a-definition-change.md).

To avoid generation rollouts, define all required usages when creating a namespace. If an existing namespace needs a new usage, add a new namespace prefix instead.

## Keeping the number of attribute names bounded

Each distinct attribute name creates a separate field in the Lucene index. Fields configured with `facet` and `sort` allocate additional per-field structures.

A catalogue with tens of distinct attribute names is unremarkable. Accepting arbitrary user input as attribute names can generate thousands of fields, increasing index size and node memory consumption. Ensure that attribute names are bounded and controlled by your catalogue schema.

## Related

- [Wildcard fields](../reference/field-types.md#wildcard-fields) - Field name pattern matching rules and precedence.
- [Field types](../reference/field-types.md) - Supported types and field usages.
- [Facets](../reference/search-api.md#facets) - Facet configuration and `excludeFilters` behavior.
- [Changing some of the fields](../reference/documents-api.md#changing-some-of-the-fields) - The partial update endpoint reference.
- [Defining an index](define-an-index.md) - Creating and updating index definitions.
- [Rolling out a definition change](roll-out-a-definition-change.md) - Creating and promoting new index generations.
- [Using sub-documents](use-sub-documents.md) - Nested objects for attributes that must match together, including dynamic ones.
- [Wildcard names on object fields](../reference/field-types.md#wildcard-names-on-object-fields) - Patterns inside an object field and on its own name.
