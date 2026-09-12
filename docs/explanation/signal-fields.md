# Signal fields

This document explains why signal fields exist, how they store and refresh numeric values without reindexing documents, and how to evaluate the operational cost of refreshing them.

## The problem with fast-moving ranking values

A ranking value such as a sales count, a popularity score, or a score computed outside the engine changes far more often than the document it belongs to. Indexing an entire document again to update a single number rewrites the segment. On object storage, rewriting a segment creates new segment files that every searching node must pull.

## Storage design

To avoid rewriting complete segments, a numeric field (`int32`, `int64`, `float`, or `double`) can be configured as a signal field with `"signal": {}`. The engine writes the value of a signal field only as sort doc values.

The engine omits signal fields from the stored copy of the document when encoding document source. Every read operation fills the value back in directly from doc values. This read-time restoration applies to search result pages, documents retrieved by key, index scans, and the document copy used when merging partial changes.

## Why doc values are the only storage location

Lucene can replace the doc values of an individual document without rewriting the rest of the segment. No other structure in a Lucene segment supports in-place replacement. If any other structure also stored the value—such as a stored field or the stored document copy—that structure would continue returning the original value from when the document was first indexed.

This single mechanism is behind every restriction on a signal field. A signal field cannot combine `signal` with `filter`, `facet`, `stored`, `multiple`, `locales`, or `primaryKey`. The field name cannot contain a wildcard, and a signal field cannot sit inside an `object` field.

## In-place refreshes versus full reindexing

An update qualifies as an in-place refresh when it names only the primary key and signal fields, and each change replaces one signal field whole with at most one value. For qualifying updates, the engine replaces doc values directly without reading or rewriting the document. An in-place refresh succeeds even on an index where document storage is disabled with `"source": "none"`.

An update does not qualify as an in-place refresh when it names any other field, appends a value with `field[]`, or reaches inside a field with a selector or a dotted path. In those cases, the engine reads the stored document copy, merges the change, and reindexes the document. Signal fields named in such an update receive the supplied values, while omitted signal fields retain their existing values.

What an update names decides which path it takes, and what a searching node pulls afterwards:

```d2 title="The path an update takes, from the fields it names to the doc values it replaces or the document it reindexes, and what each path sends to a searching node"
direction: down

update: An update reaches the index

fields: "Does the update name only the primary\nkey and signal fields?" {
  shape: diamond
}

whole: "Does each change replace one signal\nfield whole, with at most one value?" {
  shape: diamond
}

refresh: "In-place refresh: the engine replaces\nthe doc values at the next commit"
rewrite: "The engine reads the stored document,\nmerges the change, and reindexes it"

small: "Searching nodes pull a few small files\nfor each segment the update touched"
segments: "Searching nodes pull\nnew segment files"

update -> fields
fields -> whole: Yes
fields -> rewrite: "No: another field, a selector,\nor a dotted path"
whole -> refresh: Yes
whole -> rewrite: "No: a value appended\nwith field[]"

refresh -> small
rewrite -> segments
```

## Value ownership and carry-over

A signal field value belongs to whatever process refreshes it. When you index a complete document that carries no value for a signal field, the index retains the value it already holds. This carry-over rule ensures that a catalogue reload that omits signal fields does not wipe out values written by recent refreshes.

Setting a signal field to `null` in an update explicitly clears the value. A cleared signal field contributes 0 to a ranking signal, identical to a document that never held a value.

## Refresh costs and batching

A refresh rewrites the doc values of the signal field once per touched segment at the next commit, without rewriting any documents. On object storage, the refresh reaches searching nodes as a few small files per segment rather than as newly created segments.

Send refreshes as a few large batches rather than many small requests. Because each batch rewrites doc values for every segment it touches, sending many small batches pays that segment rewrite cost repeatedly.

## Limits

A signal field is sortable and can be read by a ranking signal, a tie breaker, or a `sort` clause without explicitly enabling `sort` in the field definition.

When a search specifies `fields`, the response includes a signal field only when the `fields` list explicitly names it. When a search specifies no `fields` list, the response returns all signal fields alongside the other fields of the document.

Adding or removing `signal` on an existing field requires a new index generation. Adding `sort` to an existing signal field is compatible. An index definition that declares a signal field records the `field.signal` feature name.

## Related

- [Field types](../reference/field-types.md#signal-fields) - Reference for signal field rules and errors.
- [Field types](../reference/field-types.md#signals) - Reference for ranking signals and transformation shapes.
- [Relevance](relevance.md) - Explanation of how ranking signals score documents.
- [Updating parts of documents](../how-to/update-parts-of-documents.md) - How-to guide for sending document updates.
- [Documents API](../reference/documents-api.md#change-documents-in-a-batch) - Reference for batch update endpoints.
- [Rolling out a definition change](../how-to/roll-out-a-definition-change.md) - How-to guide for adding a signal field to an existing index.
