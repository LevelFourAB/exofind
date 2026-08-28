# Rolling out a definition change

This guide shows you how to roll out an index definition change without search downtime by indexing into a new generation and promoting it.

Use this guide when a definition change alters how existing values are indexed:

- A field gaining `matching`, `filter`, `sort`, `facet`, or a vector
- A different analyzer, preset, tokenizer, or token filter
- A changed or newly referenced stopword list or synonym set
- A new locale on a field, or a changed locale fallback chain
- Different vector dimensions

You do not need this procedure if the index is empty, if the change only affects documents indexed after the change, or if you are about to reindex all documents. For conceptual background, see [Generations](../explanation/generations.md).

## Prerequisites

Before you begin, verify that your API key has permissions for `products@*`. An API key granted permissions only for `products` follows the active generation across rollouts, but cannot search or list specific generations by name. For more information, see [Patterns and generations](../reference/auth.md#patterns-and-generations).

## Rolling out the new generation

To roll out the definition change, complete the following steps:

1. Create the new generation by sending the complete index definition to the target generation:

   ```http
   PUT /v1alpha1/admin/indexes/products@2
   Content-Type: application/json

   {
     "fields": {
       "id":    { "type": "string", "primaryKey": true, "required": true },
       "title": { "type": "string", "matching": { "typoTolerance": {} } },
       "brand": { "type": "string", "filter": {}, "facet": {} }
     }
   }
   ```

   The index continues to answer searches from the active generation.

2. Index your documents into the new generation and commit the changes:

   ```http
   POST /v1alpha1/indexes/products@2/documents
   POST /v1alpha1/admin/indexes/products@2/actions/commit
   ```

3. Search both generations to compare results before making the new generation live:

   ```http
   POST /v1alpha1/indexes/products@2/search
   POST /v1alpha1/indexes/products/search
   ```

4. Promote the new generation:

   ```http
   POST /v1alpha1/admin/indexes/products@2/actions/promote
   ```

## Confirming the rollout

To confirm that the rollout succeeded, search the index by name:

```http
POST /v1alpha1/indexes/products/search
```

The node that served the promotion answers searches for `products` from generation `2` immediately. Every other node answers from generation `2` within `INDEXES_REFRESH_INTERVAL`.

## Rolling back a change

If you need to revert the rollout, promote the previous generation:

```http
POST /v1alpha1/admin/indexes/products@1/actions/promote
```

Callers do not need configuration changes to use the rolled-back generation.

## Deleting the previous generation

After you confirm that the new generation works as expected, delete the previous generation:

```http
DELETE /v1alpha1/admin/indexes/products@1
```

**Note:** You cannot delete the generation that an index currently answers from. Promote another generation before deleting it. Unremoved generations continue to consume storage and local disk on nodes that pulled them.
