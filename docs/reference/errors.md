# Errors

API error responses share a standard JSON format.

## Error response format

When an API request fails, the server returns an error response body:

```json
{
  "code": "validation",
  "message": "Request contains 2 errors",
  "errors": [
    {
      "code": "index:field:invalid_primary_key_multiple",
      "message": "Field `id` is marked as a primary key and multiple, primary keys can not have multiple values",
      "path": "id",
      "arguments": { "name": "id" }
    }
  ]
}
```

The top-level error response contains the following fields:

| Field | Type | Description |
| --- | --- | --- |
| `code` | string | Identifies the failure type. Error codes are stable across versions. |
| `message` | string | Human-readable message for log output. Match on `code` rather than `message`. |
| `errors` | array | Optional. Contains all validation errors detected in the request. Present on validation failures. |

Each object in the `errors` array contains the following fields:

| Field | Type | Description |
| --- | --- | --- |
| `code` | string | The error code identifying the specific validation failure. |
| `message` | string | Human-readable description of the validation failure. |
| `path` | string | Location of the invalid field in the request. |
| `arguments` | object | Key-value pairs containing the values used to build the error message. |

For what each status code means, and for the conventions the whole API shares, see [API conventions](api-conventions.md). For the conditions that produce a status on a particular endpoint, see the status tables in the [admin API](admin-api.md#status-codes) reference. The `400 Bad Request` status code covers both invalid request bodies and queries that request data or features an index does not have.

For deciding what a client does with a failure, see [Handle errors in a client](../how-to/handle-api-errors.md).

## Code prefixes

Error codes use colon-separated namespaces. The prefix indicates which part of the request caused the error:

| Prefix | Scope | Examples |
| --- | --- | --- |
| `request:*` | Unreadable or malformed request body | `request:missing_body`, `request:document:malformed`, `request:delete:target_required` |
| `auth:*` | Caller identity and permissions | `auth:unauthenticated`, `auth:forbidden` |
| `auth:key:*` | Key validation failure or unassigned key ID | `auth:key:unknown_role`, `auth:key:unknown_permission`, `auth:key:not_found` |
| `auth:keys:*` | Key storage failure | `auth:keys:unavailable`, `auth:keys:conflict`, `auth:keys:io_error` |
| `index:field:*` | Field definition validation failure | `index:field:invalid_name`, `index:field:sorting_not_supported`, `index:field:vector:missing_dimensions` |
| `index:field:analyzer:*` | Analysis chain validation failure | `index:field:analyzer:unknown_ref`, `index:field:analyzer:unsupported_locale` |
| `index:schema:*` | Index-wide schema rule failure | `index:schema:multiple_primary_keys`, `index:schema:unsupported_features` |
| `index:ranking:*` | Ranking signal or tie-breaker configuration error | `index:ranking:field_not_sortable`, `index:ranking:signal:shape_not_supported`, `index:ranking:signal:invalid_pivot` |
| `index:locale_fallback:*` | Locale fallback chain configuration error | `index:locale_fallback:locale_not_held`, `index:locale_fallback:unsupported_locale` |
| `index:resources:*` | Shared resource validation failure | `index:resources:synonyms:one_sided` |
| `index:definition:*` | Stored index definition incompatible with this API version | `index:definition:unrepresentable` |
| `index:generation:*` | Generation usage or deletion error | `index:generation:already_exists`, `index:generation:is_live`, `index:generation:name_required`, `index:generation:not_creatable` |
| `index:registry:*` | Index or generation registry storage failure | `index:registry:conflict`, `index:registry:io_error` |
| `index:settings:*` | Search settings lookup or storage failure | `index:settings:not_found`, `index:settings:version_mismatch`, `index:settings:conflict`, `index:settings:io_error`, `index:settings:unavailable`, `index:settings:synonyms:unknown_field`, `index:settings:synonyms:field_not_text`, `index:settings:synonyms:invalid_boost`, `index:settings:synonyms:invalid_rule`, `index:settings:typo_exclusions:unknown_field`, `index:settings:typo_exclusions:field_not_text` |
| `index:update:*` | Document indexing failure | `index:update:required_field_missing`, `index:update:number:out_of_bounds`, `index:update:locale_not_declared`, `index:update:primary_key_required` |
| `index:source:*` | Stored document source copy unavailable | `index:source:not_kept`, `index:source:unreadable` |
| `index:query:*` | Query refers to unavailable index features or fields | `index:query:field_not_found`, `index:query:usage_not_enabled`, `index:query:source_not_kept` |
| `index:explain:*` | Score explanation target lookup failure | `index:explain:document_not_found`, `index:explain:value_not_found` |
| `search:clause:*`, `search:matcher:*`, `search:sort:*`, `search:highlight:*`, `search:matched:*`, `search:hits:*`, `search:facet:*`, `search:signal:*` | Malformed search request component | `search:clause:field_required`, `search:matcher:range_empty`, `search:sort:origin_required`, `search:highlight:fields_required`, `search:matched:limit_invalid`, `search:hits:path_required`, `search:facet:duplicate_name`, `search:signal:shape_invalid` |
| `search:cursor:*`, `search:page:*` | Pagination error | `search:cursor:sort_mismatch`, `search:page:too_deep` |
| Other `index:*` | Index-level state and lifecycle errors | `index:already_exists`, `index:readonly`, `index:no_primary_key`, `index:closed`, `index:io_error`, `index:unsupported`, `index:no_live_generation` |

## Error codes

The following error codes require specific handling in client applications:

- `auth:unauthenticated`: Returned with HTTP `401` and the `WWW-Authenticate: Bearer` header when the request contains no accepted credential. Absent, malformed, unknown, or lapsed credentials all return this code to prevent key enumeration.
- `auth:forbidden`: Returned when an authenticated caller lacks the required permission. The `permission` argument identifies the missing permission. When the caller has no permissions on the target index, the server returns `index:not-found` instead.
- `indexer:unavailable`: Returned with HTTP `409` when the request requires the index writer node, but no writer node is available. This occurs when no candidate node is running, no candidate sets `EXOFIND_NODE_ADDRESS`, or the request was forwarded and the index moved. Retry the request once a candidate node is available.
- `indexer:unreachable`: Returned with HTTP `502` when the request was forwarded to the index writer node, but the node did not respond. Retry the write operation.
- `indexer:leadership_unreadable`: Returned with HTTP `503` when index leadership assignments cannot be read from shared state storage. Retry the request once storage responds.
- `index:readonly`: Returned with HTTP `409` when modifying an index on a node that cannot accept writes, such as when a node loses index leadership while processing a request. Retry the request to forward it to the active writer node.
- `search:page:too_deep`: Returned when the requested offset exceeds `EXOFIND_SEARCH_MAX_PAGE_DEPTH`. Follow `next` or `previous` cursors instead of offset paging.
- `search:cursor:sort_mismatch`: Returned when a cursor is used with a different sort order than the sort order used to generate it, or with incompatible hit types between object field values and documents.
- `index:definition:unrepresentable`: Returned with HTTP `409` when the stored index definition was written by a newer API version with features that this API version cannot represent, and a `PUT` request would discard them. Send the update to a node that supports the definition. If a specific field type is unsupported, the server returns `index:field:unrepresentable_type` and names the field.
- `index:query:usage_not_enabled`: Returned when a query uses an existing field in a manner not enabled in the index definition, or when `fields` specifies a field not defined as `stored` on an index that does not keep document copies.
- `index:query:source_not_kept`: Returned when `fields` specifies an object or object property on an index where `source` is `none`, including in top-level search, within `matched`, or on `hits`.
- `index:source:not_kept`: Returned when attempting a partial document update on an index where `source` is `none`, or on a document indexed when `source` was `none`. Resend the entire document.
- `request:update:no_match`: Returned when a change names one value by a selector, such as `variants[sku=V-2]` in a document or `ranking.signals[field=sales]` in search settings, and nothing the selector matches is stored. A selector never creates the value it names, so add a value with `variants[]` instead. The remaining `request:update:*` codes report a path the endpoint cannot use and are listed in the [Documents API](documents-api.md#constraints-and-errors) and, for search settings, in the [Admin API](admin-api.md#changing-part-of-the-search-settings).
- `request:update:value_invalid`: Returned when a `PATCH` of search settings names a field that cannot hold the given value, such as a list where the settings hold an object.
- `index:document:not_found`: Returned with HTTP `404` by `PATCH /v1alpha1/indexes/{name}/documents/{key}` when nothing is indexed under the key. A change says what to change about a document, so the document is indexed whole first.
- `request:update:key_conflicting`: Returned when the body of a `PATCH` of one document gives the primary key field a value other than the key in the URL. The URL names the document to change.
- `index:generation:is_live`: Returned when attempting to delete the live generation for an index. Promote another generation before deleting the live generation.
- `index:unsupported`: Returned with HTTP `409` when the index requires engine features that the node does not support. Send the request to a node running a version that supports the required features.
- `index:settings:version_mismatch`: Returned with HTTP `412` when a `PUT` or `PATCH` of search settings carries an `If-Match` version the stored settings are no longer at. Read the settings again and rebuild the change on the version that comes back.
- `index:settings:conflict`: Returned with HTTP `409` when the search settings kept being changed by other writers while the change was being stored. The stored settings are unchanged; retry the request.
- `index:settings:unrepresentable`: Returned with HTTP `409` when a `PATCH` of search settings targets settings written by a newer version with capabilities this node does not have. A change built on top of them would discard the parts the node cannot describe. Send the request to a node that supports the settings, or replace the settings with a `PUT`.
- `index:settings:synonyms:unknown_field`: Returned with HTTP `400` when storing search settings with a synonym set applied to a field the index does not have in the generation the index name answers from.
- `index:settings:synonyms:field_not_text`: Returned with HTTP `400` when storing search settings with a synonym set applied to a field that is not searched as text in the generation the index name answers from.
- `index:settings:synonyms:invalid_boost`: Returned with HTTP `400` when storing search settings with a synonym set where the boost is not a positive number.
- `index:settings:synonyms:invalid_rule`: Returned with HTTP `400` when storing search settings with a synonym rule that is not exactly one kind (equivalent words, or a one-way mapping).
- `index:settings:typo_exclusions:unknown_field`: Returned with HTTP `400` when storing search settings with a word list applied to a field the index does not have in the generation the index name answers from.
- `index:settings:typo_exclusions:field_not_text`: Returned with HTTP `400` when storing search settings with a word list applied to a field that is not searched as text in the generation the index name answers from.
- `index:explain:document_not_found`: Returned with HTTP `404` by `POST /v1alpha1/indexes/{name}/search/actions/explain` when no document is indexed under the `key` query parameter.
- `index:explain:value_not_found`: Returned with HTTP `404` by `POST /v1alpha1/indexes/{name}/search/actions/explain` when that document holds no value of the search's `hits` path at the `index` query parameter.
