# Indexing documents

This guide shows you how to add documents to an index, update existing documents, remove documents, and verify that changes are searchable. For complete API details, see the [Documents API](../reference/documents-api.md).

## Prerequisites

Before you index documents, ensure you have:

- A defined index. The index definition must include the fields and primary key used in your documents. If a document includes a field that is not defined in the index, the request is rejected. For more information, see [Define an index](define-an-index.md).
- A running cluster with an available writer node. For more information, see [Run more than one node](run-multiple-nodes.md).

## Steps

1. Send documents to the index:

   To send a batch of JSON documents, send a `POST` request to `/v1alpha1/indexes/{index_name}/documents`:

   ```http
   POST /v1alpha1/indexes/products/documents
   Content-Type: application/json

   {
     "documents": [
       { "id": "1", "name": "Rain jacket", "category": "Outerwear", "price": 129 },
       { "id": "2", "name": "Wool hat", "category": "Hats", "price": 39 }
     ]
   }
   ```

   Each document must contain the field defined as the primary key. Sending a document with an existing key replaces the previous document. Indexing reflects desired state, so there is no separate create or update step.

   If you are loading a large dataset, send newline-delimited JSON (`application/x-ndjson`):

   ```http
   POST /v1alpha1/indexes/products/documents
   Content-Type: application/x-ndjson

   {"id": "1", "name": "Rain jacket", "category": "Outerwear", "price": 129}
   {"id": "2", "name": "Wool hat", "category": "Hats", "price": 39}
   ```

   Documents are indexed as the request body is read. Stream the entire file in one request if the request can be reissued, or split it into requests of a few thousand documents if retries should not start from the beginning.

2. Commit changes (optional for bulk loads):

   The index writer commits automatically based on indexed volume or elapsed time (see [Committing](../reference/configuration.md#committing)). If you stream a bulk dataset, send an explicit commit request after all data is loaded:

   ```http
   POST /v1alpha1/admin/indexes/products/actions/commit
   ```

   Send one commit request at the end of the load. Do not commit after every batch, because each commit writes a Lucene commit and pushes it to the remote.

3. Update specific fields (optional):

   To update specific fields in existing documents without sending the entire document, send a `POST` request to `/v1alpha1/indexes/{index_name}/documents/actions/update`:

   ```http
   POST /v1alpha1/indexes/products/documents/actions/update

   {
     "documents": [
       { "id": "1", "price": 99, "inStock": true },
       { "id": "2", "discount": null }
     ]
   }
   ```

   - A field with a value replaces the current value.
   - A field set to `null` clears the current value.
   - An omitted field remains unchanged.
   - Locale-specific fields and object fields are replaced whole. To modify part of an object or locale-specific field, send the complete document.
   - If the index definition sets `"source": "none"`, partial updates fail with `index:source:not_kept`.
   - If a primary key does not exist in the index, the request fails by default. To skip missing keys and receive a list of missing keys in the response, add `?missing=skip` to the request URL.

4. Delete documents (optional):

   To delete a single document by its ID, send a `DELETE` request:

   ```http
   DELETE /v1alpha1/indexes/products/documents/1
   ```

   The server returns `204` whether or not a document exists under that key.

   To delete multiple documents by query, send a `POST` request:

   ```http
   POST /v1alpha1/indexes/products/documents/actions/delete

   { "query": [ { "field": "category", "match": { "value": "Hats" } } ] }
   ```

   Query clauses use the same syntax as the [Search API](../reference/search-api.md). To remove all documents and empty the index, send an empty `query` array.

## Confirming the result

To verify how many documents are searchable in the index, send a search request with `limit` set to `0`:

```http
POST /v1alpha1/indexes/products/search

{ "limit": 0 }
```

With an empty query, the response returns the total count of searchable documents. Documents indexed since the last commit are not counted until they are committed. If the returned count is lower than expected, commit the index and repeat the search.

The count is what the node answering the search can find. In a deployment with more than one node, a committed change reaches the other nodes on a refresh interval, so a count taken through a load balancer can lag the writer. See [Make a write visible to search](make-writes-visible.md).

## Handling errors

If a request fails, use the following guidelines:

- **`400 Bad Request`**: Documents are processed in the order sent. The first invalid document causes the request to fail, but documents sent before it remain indexed. The `path` field in the response identifies the failed document:

  ```json
  {
    "code": "validation",
    "errors": [
      {
        "code": "index:update:required_field_missing",
        "path": "documents[41].name"
      }
    ]
  }
  ```

  Fix the invalid document and reissue the request. Because indexing replaces documents by primary key, previously indexed documents are overwritten safely.

- **`409 Conflict`**: The index has no active writer or is synchronizing. Retry the request.

- **`503 Service Unavailable`**: The index was closed to free disk space. Reissuing the request reopens the index.

## Related

- [Documents API](../reference/documents-api.md) - Request schemas, response formats, and status codes.
- [Updating parts of documents](update-parts-of-documents.md) - Change one field, one sub-document, or one locale without resending the rest.
- [Defining an index](define-an-index.md) - Define schemas and field validation rules.
- [Rolling out a definition change](roll-out-a-definition-change.md) - Reindex documents when an index definition changes.
- [Searching an index](search-an-index.md) - Query and retrieve indexed documents.
- [Make a write visible to search](make-writes-visible.md) - The commit and refresh delays between a write and a search that can see it.
- [Running multiple nodes](run-multiple-nodes.md) - Keeping candidates that can take the writes.
- [Architecture](../explanation/architecture.md) - Why a write reaches the one node that holds the index, and what happens when no node does.
