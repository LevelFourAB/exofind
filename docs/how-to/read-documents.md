# Reading documents back

This guide shows you how to read documents back out of an index to export the whole index, copy documents to another location, or inspect what a document holds. Reindexing into a new generation is the way to refill an index from another generation, so an export is not needed for that.

## Prerequisites

Before you read documents, ensure you have:

- An index defined with a primary key and with document copies kept. An index
  with no primary key is refused with `index:no_primary_key`, and one defined
  with `source: none` with `index:source:not_kept`.
- An authentication token or role with the `documents.read` permission at the index scope. The `writer` and `admin` roles include this permission; the `reader` role does not. Anonymous requests are refused.

## Steps

1. Select the response format:

   Set the `Accept` request header to select the output format:

   - `application/json`: Returns a JSON object with a `documents` array and a `next` continuation key. This is the default format when `Accept` is omitted or set to `*/*`.
   - `application/x-ndjson`: Returns newline-delimited JSON containing one document object per line with no outer wrapper.

   The NDJSON response matches byte-for-byte the format accepted by `POST /v1alpha1/indexes/{name}/documents` with `Content-Type: application/x-ndjson`. You can pipe or load this export straight into another index.

2. Read the first batch of documents:

   Send a `GET` request to `/v1alpha1/indexes/{name}/documents`:

   ```http
   GET /v1alpha1/indexes/products/documents?limit=1000
   Accept: application/json
   ```

   The node that receives the request answers it directly using data that the node has pulled from storage. Read requests are never forwarded to the active indexer node.

   The `limit` parameter sets the maximum number of documents to return in a single response. It must be a whole number between `1` and `10000`. If omitted, `limit` defaults to `100`.

   Documents return in primary key order as originally indexed:

   - Whole-number keys (`int32`, `int64`) return in numeric order, with negative numbers first.
   - Text keys return in UTF-8 byte order (for example, `"100"` precedes `"50"`).

   A single request reads a point-in-time snapshot and sees committed data only. Uncommitted writes are not visible.

3. Page through the remaining documents:

   To fetch subsequent batches, pass the primary key of the last document received into the `after` query parameter:

   ```http
   GET /v1alpha1/indexes/products/documents?limit=1000&after=sku-500
   Accept: application/json
   ```

   The specified `after` key is omitted from the response. If no document exists under that key, reading resumes from where that key would be positioned in the order.

   Determine whether more documents remain based on the response format:

   - **JSON**: When the response contains as many documents as requested by `limit`, the response includes a `next` field with the continuation key. Pass this value as the `after` parameter in the next request. When the response omits the `next` field, all documents have been read.
   - **NDJSON**: Count the lines returned. If the response contains as many lines as requested by `limit`, pass the primary key of the last document into `after` on the next request. If the response returns fewer lines than `limit`, all documents have been read.

   If writes occur while you page through an index across multiple requests:

   - Documents indexed under keys that the read has already passed are omitted from subsequent responses.
   - Documents modified after being read are returned in the state they had when read.
   - Documents deleted after being read remain included in earlier responses.

## Confirming the result

Verify that the export completed and retrieved the expected dataset:

- In JSON format, the final request returns an empty `documents` array or a batch smaller than `limit` without a `next` property:

  ```json
  {
    "documents": []
  }
  ```

- In NDJSON format, the final batch contains fewer lines than the requested `limit`.

## Related

- [Documents API](../reference/documents-api.md) - Endpoint reference, query parameters, permissions, and error codes.
- [Indexing documents](index-documents.md) - Adding, updating, and removing documents in an index.
- [Make a write visible to search](make-writes-visible.md) - Commit intervals and replication delays between writes and reads.
- [Reindex into a new generation](reindex-into-a-new-generation.md) - Refill an index from another generation without exporting documents.
