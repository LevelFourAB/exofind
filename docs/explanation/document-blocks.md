# How sub-documents are stored

This document explains how Exofind stores nested sub-documents in Lucene, how queries resolve conditions across them, and what this design costs in index size, update throughput, and query performance.

For practical instructions on working with sub-documents, see [Using sub-documents](../how-to/use-sub-documents.md).

## Lucene document blocks

When you configure an [`object` field](../reference/field-types.md#object) with `"mode": "nested"`, the engine does not store the object's values as properties within the parent document. Instead, each value in the object field is indexed as a separate Lucene document.

These sub-documents are written in a contiguous block alongside the parent document that holds them. In an index with nested fields, the total number of Lucene documents multiplies by the number of sub-document values:

- A catalogue of 100,000 product documents with 5 variants each produces 600,000 Lucene documents (500,000 variant documents plus 100,000 parent documents).
- The Lucene index tracks each variant document individually, complete with its own terms, doc values, and internal document identifier.

Because the parent and its sub-documents form a single block, the entire group shares a physical lifecycle in the index.

## Empty text fields in every document

Sub-documents carry the fields of their object type, while parent documents carry top-level fields. As a result, no single Lucene document in a block contains every field in the index. However, the engine writes every analyzed field into every Lucene document it indexes, leaving the entry empty when a document has no text for that field. The engine handles omitted optional fields the same way.

The engine does this because of how Lucene stores norms. A norm is a single byte per document that records the length of a field value for scoring. When every document in a segment contains a field, Lucene stores its norms as an array indexed by document number. When documents omit the field, Lucene uses a sparse structure. Reading a sparse norm requires seeking to the document rather than reading an array index directly. Because most Lucene documents in a nested block contain only a few fields, text fields would otherwise default to sparse norm storage and reduce scoring performance.

An empty entry produces no terms and never matches a search query. It does not affect the document count for the field or the average field length used in scoring. Search results and scores remain identical to omitting the field. Writing empty entries costs one byte per analyzed field for each Lucene document in a segment, including sub-documents.

Fields with names that contain wildcards are exempt. Because dynamic field names exist only when a document provides them, their norms remain sparse.

## Updates and deletions

Lucene writes and deletes document blocks as indivisible units:

- **Full-document rewrites:** You cannot update or patch a single nested sub-document in place. Changing one variant rewrites the entire parent document along with all of its other sub-documents.
- **Atomic deletion:** Deleting a parent document removes the entire block, deleting all associated sub-document values in the same operation.

When sub-document values change frequently, the write cost scales with the size of the whole document block rather than the size of the single changed value.

## Query execution and joins

Searching fields that live on the parent document requires no coordination across documents. A condition on a top-level field matches the parent document directly.

When a query asks something of sub-document values, it must evaluate those conditions on the child documents and map the matches back to their parent:

- A [`nested` clause](../reference/search-api.md#nested) runs its child clauses against the individual sub-document records that belong to the target object path.
- The engine then performs a join operation in Lucene, resolving the matched sub-documents back to their containing parent document block.
- Sorting and calculating facets on nested fields also evaluate matching sub-documents before rolling their values up to the parent document level.

This join adds search overhead that top-level field queries avoid.

## Choosing a variant layout

Whether variants belong inside the parent document as nested sub-documents, in separate documents of their own, or rolled up onto the parent document depends on your query patterns and update frequencies.

Rather than assuming one structure fits all workloads, measure the trade-offs directly against your data. The `GroupingBenchmark` suite in the engine evaluates four catalogue layouts under identical queries:

1. **Nested variants:** Sub-documents stored in a single document block.
2. **One document per variant:** Individual variant documents resolved through grouping strategies.
3. **Rolled-up fields:** Variant values aggregated into flat multi-value fields on the parent document.
4. **Separate indexes:** Independent indexes for parent products and child variants.

To run these benchmarks and compare storage size, update throughput, and query accuracy across layouts, see [Benchmarking the engine](../how-to/benchmark-the-engine.md#comparing-variant-layouts).

## Related

- [`object` field type reference](../reference/field-types.md#object)
- [The `nested` search clause](../reference/search-api.md#nested)
- [Using sub-documents](../how-to/use-sub-documents.md)
- [Benchmarking the engine](../how-to/benchmark-the-engine.md)
