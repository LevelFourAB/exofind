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
| `path` | string | Location of the invalid field in the request, as a JSON Pointer (`/fields/title/sortable`) or a dotted field path (`fields.title`). For which form an error carries, see [API conventions](api-conventions.md#error-body). |
| `arguments` | object | Key-value pairs containing the values used to build the error message. |

For what each status code means, and for the conventions the whole API shares, see [API conventions](api-conventions.md). For the conditions that produce a status on a particular endpoint, see the status tables in the [admin API](admin-api.md#status-codes) reference. The `400 Bad Request` status code covers both invalid request bodies and queries that request data or features an index does not have.

For deciding what a client does with a failure, see [Handle errors in a client](../how-to/handle-api-errors.md).

## Code prefixes

Error codes use colon-separated namespaces. The prefix indicates which part of the request failed, and the table covers every prefix the engine returns:

| Prefix | Scope | Examples |
| --- | --- | --- |
| `request:*` | A request the server could not read or does not serve | `request:missing_body`, `request:malformed`, `request:unknown_property`, `request:value_invalid`, `request:unreadable`, `request:value_required`, `request:too_large`, `request:not_found`, `request:method_not_allowed`, `request:not_acceptable`, `request:unsupported_media_type`, `request:refused`, `request:document:malformed`, `request:delete:target_required` |
| `auth:*` | Caller identity and permissions | `auth:unauthenticated`, `auth:forbidden` |
| `auth:key:*` | Key validation failure or unassigned key ID | `auth:key:unknown_role`, `auth:key:unknown_permission`, `auth:key:not_found` |
| `auth:keys:*` | Key storage failure | `auth:keys:unavailable`, `auth:keys:conflict`, `auth:keys:io_error` |
| `index:field:*` | Field definition validation failure | `index:field:invalid_name`, `index:field:sorting_not_supported`, `index:field:signal_not_supported`, `index:field:signal:usage_conflict`, `index:field:number:invalid_unit`, `index:field:vector:missing_dimensions` |
| `index:field:analyzer:*` | Analysis chain validation failure | `index:field:analyzer:unknown_ref`, `index:field:analyzer:unsupported_locale` |
| `index:schema:*` | Index-wide schema rule failure | `index:schema:multiple_primary_keys`, `index:schema:unsupported_features` |
| `index:ranking:*` | Ranking signal or tie-breaker configuration error | `index:ranking:field_not_sortable`, `index:ranking:signal:shape_not_supported`, `index:ranking:signal:invalid_pivot`, `index:ranking:signal:invalid_ceiling` |
| `index:locales:*` | Index locale declaration error | `index:locales:default_locale_required` |
| `index:locale_fallback:*` | Locale fallback chain configuration error | `index:locale_fallback:locale_not_held`, `index:locale_fallback:unsupported_locale` |
| `index:resources:*` | Shared resource validation failure | `index:resources:synonyms:one_sided` |
| `index:definition:*` | Stored index definition incompatible with this API version | `index:definition:unrepresentable` |
| `index:generation:*` | Generation usage or deletion error | `index:generation:already_exists`, `index:generation:is_live`, `index:generation:live_moved`, `index:generation:name_required`, `index:generation:not_creatable`, `index:generation:storage_held`, `index:generation:unsettled` |
| `index:registry:*` | Index or generation registry storage failure | `index:registry:conflict`, `index:registry:io_error` |
| `index:settings:*` | Search settings lookup or storage failure | `index:settings:not_found`, `index:settings:version_mismatch`, `index:settings:conflict`, `index:settings:io_error`, `index:settings:unavailable`, `index:settings:synonyms:unknown_field`, `index:settings:synonyms:field_not_text`, `index:settings:synonyms:invalid_boost`, `index:settings:synonyms:invalid_rule`, `index:settings:typo_exclusions:unknown_field`, `index:settings:typo_exclusions:field_not_text`, `index:settings:fields:unknown_field`, `index:settings:fields:interpret_unsupported`, `index:settings:fields:values_unsupported`, `index:settings:fields:values_invalid`, `index:settings:fields:suggest_unsupported` |
| `index:update:*` | Document indexing failure | `index:update:required_field_missing`, `index:update:number:out_of_bounds`, `index:update:locale_not_declared`, `index:update:primary_key_required` |
| `index:source:*` | Stored document source copy unavailable | `index:source:not_kept`, `index:source:unreadable` |
| `index:query:*` | Query refers to unavailable index features or fields | `index:query:field_not_found`, `index:query:usage_not_enabled`, `index:query:source_not_kept`, `index:query:facet_prefix_on_a_tree`, `index:query:interpret:no_unit`, `index:query:interpret:fallback_unit` |
| `index:explain:*` | Score explanation target lookup failure | `index:explain:document_not_found`, `index:explain:value_not_found` |
| `index:document:*` | A document named by its key that the index does not hold | `index:document:not_found` |
| `search:clause:*`, `search:matcher:*`, `search:filter:*`, `search:sort:*`, `search:highlight:*`, `search:matched:*`, `search:hits:*`, `search:facet:*`, `search:suggest:*`, `search:signal:*`, `search:rescore:*`, `search:required` | Malformed search request component | `search:clause:field_required`, `search:matcher:range_empty`, `search:filter:clause_invalid`, `search:sort:field_required`, `search:highlight:fields_required`, `search:facet:duplicate_name`, `search:signal:weight_invalid`, `search:rescore:window_invalid` |
| `search:explain:*` | Explanation naming no hit to explain | `search:explain:key_required`, `search:explain:index_invalid` |
| `search:cursor:*`, `search:page:*`, `search:pages:*`, `search:offset:*` | Pagination error | `search:cursor:invalid`, `search:cursor:sort_mismatch`, `search:page:conflicting`, `search:page:too_deep`, `search:pages:without_limit`, `search:pages:without_offset`, `search:pages:invalid_max`, `search:offset:negative` |
| `search:limit:*`, `search:query:*` | Search asking for more than the node allows, or for a negative page size | `search:limit:too_large`, `search:limit:negative`, `search:query:too_many_clauses`, `search:query:too_deep` |
| `search:locale:*` | Search naming a locale the engine has no rules for | `search:locale:unsupported` |
| `search:timeout` | Search abandoned after running longer than the node allows | `search:timeout` |
| `reindex:*` | Reindex job lookup, state, or record storage failure | `reindex:not_found`, `reindex:in_progress`, `reindex:io_error` |
| Other `index:*` | Index-level state and lifecycle errors | `index:not_found`, `index:already_exists`, `index:readonly`, `index:no_primary_key`, `index:closed`, `index:io_error`, `index:unsupported`, `index:no_live_generation` |
| `indexer:*` | The node that writes an index could not be found or reached | `indexer:unavailable`, `indexer:unreachable`, `indexer:leadership_unreadable` |
| `node:*` | The node failed to serve a request that is not itself wrong | `node:error` |
| `validation` | The envelope of a refused request. Branch on the codes in its `errors` array | `validation` |

## Error codes

The following error codes require specific handling in client applications. For the complete list of codes one endpoint answers with, see that endpoint on the [REST API pages](https://exofind.dev/api/).

- `auth:unauthenticated`: Returned with HTTP `401` and the `WWW-Authenticate: Bearer` header when the request contains no accepted credential. Absent, malformed, unknown, or lapsed credentials all return this code to prevent key enumeration.
- `auth:forbidden`: Returned when an authenticated caller lacks the required permission. The `permission` argument identifies the missing permission. When the caller has no permissions on the target index, the server returns `index:not_found` instead.
- `indexer:unavailable`: Returned with HTTP `409` when the request requires the index writer node, but no writer node is available. This occurs when no candidate node is running, no candidate sets `EXOFIND_NODE_ADDRESS`, or the request was forwarded and the index moved. Retry the request once a candidate node is available.
- `indexer:unreachable`: Returned with HTTP `502` when the request was forwarded to the index writer node, but the node did not respond. Retry the write operation.
- `indexer:leadership_unreadable`: Returned with HTTP `503` when index leadership assignments cannot be read from shared state storage. Retry the request once storage responds.
- `index:readonly`: Returned with HTTP `409` when modifying an index on a node that cannot accept writes, such as when a node loses index leadership while processing a request. Retry the request to forward it to the active writer node.
- `search:page:too_deep`: Returned when `offset` plus `limit` reaches past `EXOFIND_SEARCH_MAX_PAGE_DEPTH`. Follow `next` or `previous` cursors instead of offset paging.
- `search:cursor:sort_mismatch`: Returned when a cursor is used with a different sort order than the sort order used to generate it, or with incompatible hit types between object field values and documents.
- `search:cursor:invalid`: Returned when `after` or `before` carries a token the engine did not issue. A cursor is opaque; pass it back unchanged.
- `index:query:invalid_cursor`: Returned when a cursor reads as one the engine issued under this sort, and still does not name a position in it - the values it carries are the wrong number or the wrong kind for the sort as the index defines it now. A `sort` field whose type changed in the index definition leaves earlier cursors like that. Start again from the first page. A cursor the engine cannot read at all returns `search:cursor:invalid` instead, and one taken under another sort returns `search:cursor:sort_mismatch`.
- `search:page:conflicting`: Returned when more than one of `offset`, `after` and `before` is given. Specify only one starting position.
- `search:pages:without_offset`: Returned when `pages` is asked for from a `next` or `previous` cursor. Start numbered paging from `offset` or from a page's own cursor.
- `search:rescore:window_too_small`: Returned when `offset` plus `limit` reaches past the `window` of a `rescore` block. Widen the window, or ask for an earlier page. A rescored search cannot page past its window by counting; follow `next` instead. See [Paging a rescored search](search-api.md#paging-a-rescored-search).
- `search:rescore:window_invalid`: Returned when the `window` of a `rescore` block is below one or above `EXOFIND_SEARCH_MAX_RESCORE_WINDOW`.
- `search:limit:too_large`: Returned when `limit` exceeds `EXOFIND_SEARCH_MAX_LIMIT`. Ask for a smaller page and follow `next` for the rest.
- `search:query:too_many_clauses`: Returned when a request holds more clauses than `EXOFIND_SEARCH_MAX_CLAUSES`, counted across `query`, `filters`, `hits.when`, `rescore.boost`, and the `when` of every interpret target of a `text` clause. The `path` names the clause the count ran past. The rest of the request is not validated, so a client that also has other errors to fix sees them only after this one.
- `search:query:too_deep`: Returned when clauses nest deeper than `EXOFIND_SEARCH_MAX_CLAUSE_DEPTH`. Flatten the query: `and` inside `and` narrows the same way as one `and` holding both clauses. Each `fallback` of an interpret target counts as one level, as does the `when` of a target.
- `search:clause:k_too_large`: Returned when the `k` of a `knn` clause exceeds `EXOFIND_SEARCH_MAX_KNN_K`.
- `search:clause:depth_too_large`: Returned when the `depth` of a `fuse` clause exceeds `EXOFIND_SEARCH_MAX_FUSE_DEPTH`.
- `search:timeout`: Returned with HTTP `503` when a search collects for longer than `EXOFIND_SEARCH_TIMEOUT`, or a suggest request counts for longer than `EXOFIND_SUGGEST_TIMEOUT`. The results collected before the node stopped are dropped. Repeating the same request costs the same again, so narrow the search instead. The `timeout` argument carries the budget the search ran past.
- `search:suggest:limit_invalid`: Returned with HTTP `400` by `POST /v1alpha1/indexes/{name}/suggest` when `limit` is below 1 or above `EXOFIND_SUGGEST_MAX_LIMIT`. The `max` argument carries the largest limit allowed.
- `search:facet:limit_invalid`: Returned when the `limit` of a facet is below 1 or above `EXOFIND_SEARCH_MAX_FACET_VALUES`, whether the facet is counted beside a search or asked for on its own. The `max` argument carries the largest limit allowed.
- `search:rescore:hits_unsupported`: Returned when a search combines `rescore` with `hits`. A second pass scores documents, so it cannot reorder hits that are the values of an object field.
- `index:definition:unrepresentable`: Returned with HTTP `409` when the stored index definition was written by a newer API version with features that this API version cannot represent, and a `PUT` request would discard them. Send the update to a node that supports the definition. If a specific field type is unsupported, the server returns `index:field:unrepresentable_type` and names the field.
- `index:query:usage_not_enabled`: Returned when a query uses an existing field in a manner not enabled in the index definition, or when `fields` specifies a field not defined as `stored` on an index that does not keep document copies.
- `index:query:source_not_kept`: Returned when `fields` specifies something only the document copy can answer on an index where `source` is `none` - an object itself, or a field below a `flattened` list - including in top-level search, within `matched`, or on `hits`. A field below single objects or a `nested` list answers without the copy when it is `stored`, and returns `index:query:usage_not_enabled` when it is not.
- `index:query:facet_prefix_on_a_tree`: Returned by `POST /v1alpha1/indexes/{name}/facets/{field}/values` when the field is configured with `hierarchy`. The values of such a field are paths through a tree, which a prefix cannot pick from. Count the tree a level at a time with a facet instead. See [Counting down a tree](search-api.md#counting-down-a-tree).
- `index:query:interpret:no_unit`: Returned when the `interpret` of a `text` clause names a target field that is not a number field or declares no `unit`. Declare a `unit` on the field, or name another field. See [Choosing the fields a reading may target](search-api.md#choosing-the-fields-a-reading-may-target).
- `index:query:interpret:fallback_unit`: Returned when a `fallback` target declares a different `unit` than the target it stands in for. Every target of a chain must be in one unit, so a number in the text means the same thing on every product.
- `search:clause:interpret_fields_required`: Returned when the `interpret` of a `text` clause is an object whose `fields` is empty or missing. Name at least one target, or use `"auto"` or `"off"`.
- `search:clause:interpret_when_unsupported`: Returned when the `when` of a target holds a `nested`, `knn` or `fuse` clause. `when` accepts what a `nested` clause accepts: `field`, `text`, `and`, `or`, `not` and `boost`.
- `index:source:not_kept`: Returned when attempting a partial document update on an index where `source` is `none`, or on a document indexed when `source` was `none`. Resend the entire document. A change that names only the primary key and [signal fields](field-types.md#signal-fields) does not require the source.
- `request:update:no_match`: Returned when a change names one value by a selector, such as `variants[sku=V-2]` in a document or `ranking.signals[field=sales]` in search settings, and nothing the selector matches is stored. A selector never creates the value it names, so add a value with `variants[]` instead. The remaining `request:update:*` codes report a path the endpoint cannot use and are listed in the [Documents API](documents-api.md#constraints-and-errors) and, for search settings, in the [Admin API](admin-api.md#changing-part-of-the-search-settings).
- `request:update:value_invalid`: Returned when a `PATCH` of search settings names a field that cannot hold the given value, such as a list where the settings hold an object.
- `index:document:not_found`: Returned with HTTP `404` by `PATCH /v1alpha1/indexes/{name}/documents/{key}` when nothing is indexed under the key. A change says what to change about a document, so the document is indexed whole first.
- `request:update:key_conflicting`: Returned when the body of a `PATCH` of one document gives the primary key field a value other than the key in the URL. The URL names the document to change.
- `index:generation:is_live`: Returned when attempting to delete the live generation for an index. Promote another generation before deleting the live generation.
- `index:generation:storage_held`: Returned with HTTP `409` when creating an index or generation on storage that holds a generation the registry does not name and no delete marked. The `generation` argument names it. Run a registry repair to register the storage, or remove its objects from the bucket.
- `index:generation:live_moved`: Returned with HTTP `409` when a reindex job asks to promote the generation it filled and another generation was promoted while the job ran. The `expected` argument names the generation the job read, and `live` the generation the index serves from. The job moves to the `failed` phase; start a new job that reads from the generation named by `live`.
- `index:generation:unsettled`: Returned with HTTP `400` when a document write kept finding that the generation the index serves from had changed while the write was being made. Each attempt needs its own promote, so this does not repeat. Send the request again.
- `index:unsupported`: Returned with HTTP `409` when the index requires engine features that the node does not support. Send the request to a node running a version that supports the required features.
- `index:settings:not_found`: Returned with HTTP `404` when the search settings of an index are read and it has none, and when a `PUT` or `PATCH` of them carries an `If-Match` header and the index has no settings for it to match. `If-Match: *` asks for settings that exist, so it is refused the same way. Store the settings whole, without an `If-Match` header, to give the index its first ones.
- `index:settings:version_mismatch`: Returned with HTTP `412` when a `PUT` or `PATCH` of search settings carries an `If-Match` header that the stored version does not satisfy. Versions are compared exactly, so a weak tag matches none. Read the settings again and rebuild the change on the version that comes back.
- `index:settings:conflict`: Returned with HTTP `409` when the search settings kept being changed by other writers while the change was being stored. The stored settings are unchanged; retry the request.
- `index:settings:unrepresentable`: Returned with HTTP `409` when a `PATCH` of search settings targets settings written by a newer version with capabilities this node does not have. A change built on top of them would discard the parts the node cannot describe. Send the request to a node that supports the settings, or replace the settings with a `PUT`.
- `index:settings:synonyms:unknown_field`: Returned with HTTP `400` when storing search settings with a synonym set applied to a field the index does not have in the generation the index name answers from.
- `index:settings:synonyms:field_not_text`: Returned with HTTP `400` when storing search settings with a synonym set applied to a field that is not searched as text in the generation the index name answers from.
- `index:settings:synonyms:invalid_boost`: Returned with HTTP `400` when storing search settings with a synonym set where the boost is not a positive number.
- `index:settings:synonyms:invalid_rule`: Returned with HTTP `400` when storing search settings with a synonym rule that is not exactly one kind (equivalent words, or a one-way mapping).
- `index:settings:typo_exclusions:unknown_field`: Returned with HTTP `400` when storing search settings with a word list applied to a field the index does not have in the generation the index name answers from.
- `index:settings:typo_exclusions:field_not_text`: Returned with HTTP `400` when storing search settings with a word list applied to a field that is not searched as text in the generation the index name answers from.
- `index:settings:fields:unknown_field`: Returned with HTTP `400` when storing search settings with field settings for a field the index does not have in the generation the index name answers from.
- `index:settings:fields:interpret_unsupported`: Returned with HTTP `400` when storing search settings that read the values of a field that is not a `string` field with `filter` and `facet` and without `hierarchy`, in the generation the index name answers from.
- `index:settings:fields:values_unsupported`: Returned with HTTP `400` when storing search settings that declare values of a field that is not a `string` field with `facet` and without `hierarchy`, in the generation the index name answers from.
- `index:settings:fields:values_invalid`: Returned with HTTP `400` when storing search settings whose declared values hold an entry without a `value`, repeat a `value`, key a label by a tag that is not a canonical BCP-47 tag, hold a blank label, or declare more than 10000 values for one field. The message names the reason.
- `index:settings:fields:suggest_unsupported`: Returned with HTTP `400` when storing search settings that suggest the values of a field that is not a `string` field with `facet` and without `hierarchy`, in the generation the index name answers from. See [Suggesting what to search for](search-api.md#suggesting-what-to-search-for).
- `index:explain:document_not_found`: Returned with HTTP `404` by `POST /v1alpha1/indexes/{name}/search/actions/explain` when no document is indexed under the `key` query parameter.
- `index:explain:value_not_found`: Returned with HTTP `404` by `POST /v1alpha1/indexes/{name}/search/actions/explain` when that document holds no value of the search's `hits` path at the `index` query parameter.
- `search:explain:key_required`: Returned with HTTP `400` by `POST /v1alpha1/indexes/{name}/search/actions/explain` when the request carries no `key`. An explanation is of one hit, so `key` names the document it is of.
- `search:explain:index_invalid`: Returned with HTTP `400` by `POST /v1alpha1/indexes/{name}/search/actions/explain` when `index` is below zero. The values of the `hits` path are counted from zero.
- `request:malformed`: Returned with HTTP `400` when the request body is not valid JSON. The `reason` argument says what the parser could not do, and `line` and `column` say where it stopped.
- `request:unknown_property`: Returned with HTTP `400` when the body holds a property the endpoint does not have. The `path` is a JSON Pointer to the property, such as `/fields/title/sortable`, and the `property` argument names it. The server refuses a misspelled property instead of dropping it, so check the spelling against the request fields the endpoint documents.
- `request:value_invalid`: Returned with HTTP `400` when a value does not fit the property it is written at, such as a string where a number belongs, or a `type` that names no member of a tagged union. The `path` is a JSON Pointer to the value, and the `reason` argument says what did not fit.
- `request:too_large`: Returned with HTTP `413` when the request body is larger than the node accepts, set by `quarkus.http.limits.max-body-size`. The response closes the connection. Send the documents in smaller batches.
- `request:not_found`: Returned with HTTP `404` when no endpoint answers the path. A path that an endpoint answers, naming an index that does not exist, returns `index:not_found` instead.
- `request:method_not_allowed`: Returned with HTTP `405` when the path is not answered for the HTTP method of the request. For the methods a path is answered for, see the [REST API pages](https://exofind.dev/api/).
- `request:not_acceptable`: Returned with HTTP `406` when the `Accept` header names no media type the endpoint answers in.
- `request:unsupported_media_type`: Returned with HTTP `415` when the `Content-Type` header names a media type the endpoint does not read.
- `node:error`: Returned with HTTP `500` when the node failed to serve a request that is not itself wrong. The node logs the cause. Retry the request, and read the node log if it keeps failing.
- `request:unreadable`: Returned with HTTP `400` when the request body stopped arriving before it was read to the end, which a streamed request finds in the middle of its work. The documents read before that are indexed. Send the rest again.
- `reindex:io_error`: Returned with HTTP `409` when the record of a reindex could not be read or written. The reindex is left as it was; retry the request once the storage responds.
