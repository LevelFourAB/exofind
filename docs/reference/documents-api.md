# Documents API

The Documents API reads, creates, updates, and deletes documents in an index under `/v1alpha1/indexes/{name}/documents`.

## Routing and commits

Each index is written by one node at a time. A node that receives a write request for an index it does not hold forwards the request to the active indexer node. If no target node is available to handle the forwarded request, the request returns status `409` with error `indexer:unavailable`.

Changes become searchable and replicate to remote storage after the index commits. The writer commits automatically based on indexed document volume or elapsed time. For commit configuration details, see [Committing](configuration.md#committing).

To commit changes immediately, send a request to the admin API:

```
POST /v1alpha1/admin/indexes/{name}/actions/commit
```

For more information, see the [Admin API](admin-api.md).

## How a document is shaped

A document specifies its own primary key. Indexing a document with an existing key replaces the document under that key. If an index definition does not declare a primary key, each request adds a new document.

The following table lists the supported field formats:

| Field type | Format | Example |
| --- | --- | --- |
| Single value | Value literal | `"name": "rågbröd"` |
| Declared `multiple` | Array of values | `"tags": ["sylt", "bär"]` |
| Locale-specific | Object keyed by locale tag | `"name": { "sv": "sylt", "en": "jam" }` |
| Geo point | Object with `lat` and `lon` fields | `"origin": { "lat": 59.33, "lon": 18.07 }` |
| Vector | Array of numbers | `"embedding": [0.12, -0.4]` |
| Object | JSON object of declared fields | `"variants": { "size": "S" }` |
| Timestamp | ISO 8601 string | `"published": "2026-08-16T09:00:00Z"` |

Document fields follow these rules:

- The schema definition determines how a JSON object is parsed (for example, as a locale map or a geo point).
- If you provide a value without a locale tag for a locale-specific field, the value is stored in the field's default locale.
- Search hits return a locale-specific field in the single requested locale. For more information, see [locale-specific fields in search results](search-api.md#locale-specific-fields). Indexing that search hit replaces the field with only that single language variant.
- Object fields must be formatted as nested JSON objects. Specifying dotted paths such as `"dimensions.width"` directly returns the error `index:update:field_inside_object`.
- A field set to `null` is treated as omitted. If the schema marks the field as `required`, validation fails and reports the field as missing.

## Indexing documents

```
POST /v1alpha1/indexes/{name}/documents
```

Indexes one or more documents into the specified index.

### Request headers

The request supports the following headers:

| Header | Description |
| --- | --- |
| `Content-Type` | Set to `application/json` or `application/x-ndjson`. |

### Request body

You can format the request body in two ways:

- `application/json`: A JSON object with a `documents` array containing document objects.
- `application/x-ndjson`: Newline-delimited JSON containing one document object per line without a wrapper.

The following example uses `Content-Type: application/json`:

```json
{
  "documents": [
    {
      "id": "1",
      "name": "blåbärssylt",
      "tags": ["sylt", "bär"],
      "energy": 234
    }
  ]
}
```

The following example uses `Content-Type: application/x-ndjson`:

```
{"id": "1", "name": "blåbärssylt"}
{"id": "2", "name": "rågbröd"}
```

### Response

The endpoint returns status `200 OK` with the count of indexed documents:

```json
{ "indexed": 2 }
```

## Changing some of the fields

```
POST /v1alpha1/indexes/{name}/documents/actions/update
```

Updates specific fields of existing documents in the index.

### Query parameters

The request supports the following query parameters:

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `missing` | string | `fail` | Behavior when a document key does not exist. Allowed values are `fail` (fails the request) and `skip` (skips missing documents and lists them in the response). |

### Request headers

The request supports the following headers:

| Header | Description |
| --- | --- |
| `Content-Type` | Set to `application/json` or `application/x-ndjson`. |

### Request body

The body contains document objects. Each object must include the primary key and the fields to update.

The following example uses `Content-Type: application/json`:

```json
{
  "documents": [
    { "id": "1", "price": 34.50, "inStock": true },
    { "id": "2", "price": 12.00, "discount": null }
  ]
}
```

You can also send updates using `Content-Type: application/x-ndjson` with one change object per line.

### Update behavior

Field modifications apply as follows:

| Field change | Behavior |
| --- | --- |
| Field with a value | Replaces the current value of the field. |
| Field set to `null` | Clears the field value. |
| Omitted field | Leaves the existing field value unchanged. |

Updates follow these rules:

- Locale-specific fields and object fields are replaced entirely rather than merged.
- Multiple updates to the same document in a single batch apply in the order provided.
- The updated document is validated as a whole. If validation fails, the request is rejected and the document remains unchanged.

### Constraints and errors

- If the index definition sets `source` to `none`, or if a document was indexed when source was disabled, the endpoint returns `index:source:not_kept`. For more information, see the [Admin API](admin-api.md).
- If the index definition declares no primary key, the endpoint returns `index:no_primary_key`.
- If `missing` is set to `fail` (default) and a document key is not found, the request fails. If `missing` is set to `skip`, missing keys are skipped and returned in the response.

### Response

The endpoint returns status `200 OK` with the count of updated documents and any missing keys:

```json
{ "updated": 2, "missing": [] }
```

When called with `?missing=skip`:

```json
{ "updated": 1998, "missing": ["sku-9", "sku-40"] }
```

## Reading documents

```
GET /v1alpha1/indexes/{name}/documents
```

Reads documents back out of an index in primary key order, returning them as originally indexed.

### Permissions and routing

Reading documents requires the `documents.read` permission at the index scope. Anonymous requests are refused. The `writer` and `admin` roles include this permission; the `reader` role does not.

Read requests are served directly by whichever node receives them, using data that the node has pulled from storage. Unlike write operations, read requests are never forwarded to the indexer node.

### Query parameters

The request supports the following query parameters:

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `after` | string | None | Primary key to resume reading after. The specified key is omitted from the response. Formatted as text matching the key path parameter in `DELETE /v1alpha1/indexes/{name}/documents/{key}` (for example, numeric keys are written as numbers). If no document exists under this key, reading resumes from where the key would be positioned in the order. |
| `limit` | integer | `100` | Maximum number of documents to return. Must be a whole number from `1` to `10000`. |

### Response formats

Set the `Accept` request header to select the response format. The default format is `application/json`, which also applies to `Accept: */*`.

#### JSON format (`application/json`)

Returns a JSON object with the list of documents and a continuation key:

```json
{
  "documents": [
    { "id": "1", "name": "blåbärssylt", "energy": 234.5 },
    { "id": "2", "name": "rågbröd", "energy": 217.0 }
  ],
  "next": "2"
}
```

The response fields are:

- `documents`: Documents in primary key order, formatted as originally indexed.
- `next`: Primary key to pass as the `after` parameter on the next request. Present only when the response returns as many documents as requested by `limit`. If a batch ends exactly on the last document of the index, `next` is returned and the subsequent request returns an empty `documents` array without a `next` field.

#### Newline-delimited JSON (`application/x-ndjson`)

Returns newline-delimited JSON containing one document object per line with no outer wrapper:

```
{"id": "1", "name": "blåbärssylt", "energy": 234.5}
{"id": "2", "name": "rågbröd", "energy": 217.0}
```

The body contains only documents, matching byte-for-byte the format accepted by `POST /v1alpha1/indexes/{name}/documents` with `Content-Type: application/x-ndjson`.

To determine if more documents are available, check the number of lines returned. If the response contains as many lines as requested by `limit`, resume the next request by passing the primary key of the last document in the `after` parameter. When the response returns fewer lines than `limit`, all documents have been read.

### Ordering

Documents are returned in primary key order:

- Whole-number keys (`int32`, `int64`) return in numeric order, with negative numbers first.
- Text keys return in UTF-8 byte order (for example, `"100"` precedes `"50"`).

Document order depends solely on primary keys and does not reflect the order in which documents were indexed. Merges, removals, replacements, and pulls do not change the position of a document in the key sequence. Repeating the same request returns the same sequence.

### Consistency and visibility

A single request reads from a point-in-time snapshot of the index and sees committed data only. Uncommitted writes are not visible.

Across multiple requests, index changes can occur between calls:

- Documents indexed under keys that the read has already passed are omitted from subsequent responses.
- Documents modified after being read are returned in the state they had when read.
- Documents deleted after being read remain included in earlier responses.

To retrieve a consistent full dataset from an index that is receiving writes, track concurrent modifications and replay them after reading completes.

### Request limits and pagination

Every response is bounded. The `limit` parameter cannot exceed `10000` in either response format. Reading an entire index requires a sequence of requests, each passing the previous response's final key in the `after` parameter. Bounding responses prevents reads from blocking index pulls.

### Refusals and errors

The endpoint rejects requests before generating the response body in the following conditions:

| Condition | Error code | Status |
| --- | --- | --- |
| The index definition declares no primary key | `index:no_primary_key` | 400 |
| The index is defined with `source: none` and does not store document copies | `index:source:not_kept` | 400 |
| The `limit` parameter is not a whole number between `1` and `10000` | `request:scan:limit_invalid` | 400 |

## Removing documents

You can remove a single document by its key in the URL path, or remove multiple documents in a batch by keys or search query.

### Delete a document by key

```
DELETE /v1alpha1/indexes/{name}/documents/{key}
```

Deletes a single document matching the specified key.

#### Path parameters

The request requires the following path parameters:

| Parameter | Type | Description |
| --- | --- | --- |
| `name` | string | Name of the index. |
| `key` | string | Primary key of the document to remove. Parsed according to the key field type. |

#### Errors

- `index:query:invalid_value`: The key value cannot be parsed as the defined key field type.
- `index:no_primary_key`: The index definition declares no primary key.

#### Response

The endpoint returns status `204 No Content` whether or not a document existed under the specified key.

```
DELETE /v1alpha1/indexes/foods/documents/1
```

### Delete documents by keys or query

```
POST /v1alpha1/indexes/{name}/documents/actions/delete
```

Deletes multiple documents matching a list of primary keys or a search query. The request body must include either `keys` or `query`, but not both.

#### Request body

The request body supports the following fields:

| Field | Type | Description |
| --- | --- | --- |
| `keys` | array of strings | List of primary keys to delete. An empty array deletes nothing. |
| `query` | array of objects | Query clauses matching documents to delete. For clause syntax, see the [Search API](search-api.md). An empty array matches and deletes all documents. |
| `locale` | string | Optional. Specifies the locale variant to match for locale-specific fields. Valid only when `query` is provided. |

The following example deletes documents by keys:

```json
{ "keys": ["1", "2", "3"] }
```

The following example deletes documents by query:

```json
{
  "query": [ { "field": "category", "match": { "value": "sylt" } } ],
  "locale": "sv"
}
```

#### Execution behavior

- When deleting by `keys`, all keys are validated before any documents are removed. If any key is invalid, no documents are removed.
- When deleting by `query`, the operation removes matching committed searchable documents and any uncommitted documents indexed since the last commit.

#### Response

The endpoint returns status `200 OK` with the count of deleted documents:

```json
{ "deleted": 3 }
```

For requests using `keys`, `deleted` is the number of keys provided in the request. For requests using `query`, `deleted` is the number of matching committed searchable documents.

## Failures

Documents in a batch are processed in the order sent. The first invalid document halts processing and returns status `400 Bad Request`. Documents processed before the failure remain in the index.

### Error response format

The error response contains the following fields:

| Field | Type | Description |
| --- | --- | --- |
| `code` | string | Top-level error classification code (for example, `"validation"`). |
| `message` | string | Human-readable description of the error. |
| `errors` | array of objects | Detailed list of error objects. Each object contains `code`, `message`, `path` (identifying the document and field location), and optional `arguments`. |

The following example shows an error response:

```json
{
  "code": "validation",
  "message": "Field `nonexistent` does not exist in index",
  "errors": [
    {
      "code": "index:update:field_not_found",
      "message": "Field `nonexistent` does not exist in index",
      "path": "documents[1].nonexistent",
      "arguments": { "name": "nonexistent" }
    }
  ]
}
```

### HTTP status codes

The API uses the following HTTP status codes:

| Status code | Condition |
| --- | --- |
| `200` | The documents were indexed, read, updated, or deleted successfully. |
| `204` | The document was removed by key in the URL path. |
| `400` | A document or key was rejected by validation, the index definition lacks a primary key or stored source, the limit parameter was invalid, or the request body could not be parsed. |
| `404` | No index with the specified name exists on this node. |
| `409` | No node is available to forward the request to (`indexer:unavailable`), or the index is currently synchronizing. |
| `502` | The node holding the index writer did not respond to the forwarded request. |
| `503` | The index was closed to free resources; repeating the request reopens the index. |
