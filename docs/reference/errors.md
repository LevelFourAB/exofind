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
      "code": "index:field:primary_key:multiple_unsupported",
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
| `path` | string | Location of the invalid field in the request, as a field path (`fields.title.sortable`). For the form a path takes, see [API conventions](api-conventions.md#error-body). |
| `arguments` | object | Key-value pairs containing the values used to build the error message. |

For what each status code means, and for the conventions the whole API shares, see [API conventions](api-conventions.md). For the conditions that produce a status on a particular endpoint, see the status tables in the [admin API](admin-api.md#status-codes) reference. The `400 Bad Request` status code covers both invalid request bodies and queries that request data or features an index does not have.

For deciding what a client does with a failure, see [Handle errors in a client](../how-to/handle-api-errors.md).

## How a code is built

A code is a colon-separated path of lowercase words: a family, the parts of the request the mistake sits in, and last the field and one defect word, as `search:facet:limit_out_of_range`. A client that reads the code from left to right learns which endpoint group refused the request, which part of the body holds the mistake, and what is wrong with it. The `path` of the error names the exact place.

The code says what the caller did wrong, not which part of the engine found it. A search that names a field the index does not have reports `search:field_unknown` whether the mapper or the index answered, and a change to some of a document reports `document:patch:*` where the same mistake in a change to some of the search settings reports `settings:patch:*`.

### Families

The family is the resource the URL names, so the first segment matches the endpoint group the request went to. The table covers every family the engine returns.

| Family | What it covers | Examples |
| --- | --- | --- |
| `request:*` | The request as the node reads it, before any endpoint sees its content: the body, the media types, the path, the method, and a value of the wrong kind at a place the API fixes | `request:body_required`, `request:body_malformed`, `request:property_unknown`, `request:value_invalid`, `request:value_required`, `request:limit_out_of_range`, `request:body_too_large`, `request:not_found`, `request:method_not_allowed`, `request:not_acceptable`, `request:unsupported_media_type`, `request:body_unreadable`, `request:refused` |
| `auth:*` | Caller identity and permissions, and the definition of a key under `auth:key:*` | `auth:unauthenticated`, `auth:forbidden`, `auth:key:not_found`, `auth:key:role_unknown`, `auth:key:expiry_invalid` |
| `index:*` | The definition of an index and the state of the index: `index:field:*`, `index:field:analyzer:*`, `index:schema:*`, `index:ranking:*`, `index:locales:*`, `index:locale_fallback:*`, `index:resources:*`, `index:definition:*`, `index:generation:*`, and the lifecycle codes at the top level | `index:field:name_invalid`, `index:field:sort:type_unsupported`, `index:ranking:signal:pivot_out_of_range`, `index:definition:incompatible`, `index:generation:is_live`, `index:not_found`, `index:readonly`, `index:no_live_generation` |
| `settings:*` | The search settings of an index, and a change to some of them under `settings:patch:*` | `settings:not_found`, `settings:version_mismatch`, `settings:synonyms:field_unknown`, `settings:fields:values_invalid`, `settings:patch:path_invalid`, `settings:patch:no_match` |
| `document:*` | A document as sent, a change to some of one under `document:patch:*`, a removal under `document:delete:*`, and what the index keeps of one | `document:field_required`, `document:field_unknown`, `document:vector:dimensions_mismatch`, `document:not_found`, `document:patch:selector_required`, `document:delete:target_required`, `document:source_not_kept` |
| `search:*` | A search, a suggest request, a facet values request, and an explanation: the parts are `clause`, `matcher`, `filter`, `sort`, `highlight`, `matched`, `hits`, `nested`, `facet`, `signal`, `rescore`, `interpret`, `cursor`, `pages`, `suggest` and `explain` | `search:limit_out_of_range`, `search:field_unknown`, `search:usage_unsupported`, `search:clause:field_required`, `search:facet:limit_out_of_range`, `search:cursor:sort_mismatch`, `search:explain:document_not_found`, `search:timeout` |
| `reindex:*` | A reindex job: what it is asked to do and the state it is in | `reindex:not_found`, `reindex:in_progress`, `reindex:target_busy`, `reindex:target_not_empty` |
| `indexer:*` | The node that writes an index could not be found or reached | `indexer:unavailable`, `indexer:unreachable`, `indexer:leadership_unreadable` |
| `storage:*` | The shared storage the keys, the registry, the search settings and the reindex records live in could not be read or written. The `resource` argument names which. The stored state is unchanged | `storage:io_error`, `storage:conflict`, `storage:unavailable` |
| `node:*` | The node failed to serve a request that is not itself wrong | `node:error` |
| `validation` | The envelope of a refused request. Branch on the codes in its `errors` array | `validation` |

### Defect words

The last segment of a code that answers `400 Bad Request` is the field followed by one of the following words. A relation between two fields reads `<field>_with_<other>` or `<field>_without_<other>`, as `search:pages:without_offset`.

| Word | Meaning |
| --- | --- |
| `required` | The field is missing or empty where a value is needed. |
| `invalid` | The value is of the wrong kind or shape for the field. The `reason` argument says what did not fit, where the message has one. |
| `out_of_range` | The number is outside the bounds the field accepts. Where the top of the range is a cap, the error carries `max`. Read it instead of knowing the setting that sets the cap. |
| `unknown` | The value names a thing that does not exist: a field the index does not have, a role the engine does not define. |
| `unsupported` | The value names a thing that exists but is not allowed here: a usage the field is not defined for, a clause a `nested` clause cannot hold, a locale the engine has no rules for. |
| `conflicting` | Two fields of the request exclude each other, or the value contradicts another value. |
| `duplicate` | The same name or value is given twice where each has to be distinct. |
| `empty` | A list or a range needs at least one entry or bound. |
| `too_many`, `too_few`, `too_deep` | The count or the nesting of something is outside what the node allows, where no single field holds the number. |
| `mismatch` | Two things that have to agree do not, such as the dimensions of a vector and its field, or a cursor and the sort it was taken under. |

A code that answers another status names the state instead of a defect: `not_found` for a resource the URL names, `version_mismatch` for a conditional request the stored version does not satisfy, `readonly`, `is_live`, `in_progress`, `io_error`, and the like. The [error codes](#error-codes) below say what each such state means for a client.

### Codes for a value outside its range

A request field that holds a number has one error code for a value outside the accepted range: `<field>_out_of_range`, under the part of the request that holds the field. One code covers both ends of the range.

When the top of the range is a cap, the error carries a `max` argument. When the cap is a node setting, `max` holds that setting's value. Read `max` instead of knowing the setting name.

The following table lists the fields and their error codes:

| Field | Code | Lowest accepted | Highest accepted |
| --- | --- | --- | --- |
| `limit` of a search | `search:limit_out_of_range` | 0 | `EXOFIND_SEARCH_MAX_LIMIT` (default 1000) |
| `offset` | `search:offset_out_of_range` | 0 | no cap |
| `pages.max` | `search:pages:max_out_of_range` | 1 | no cap |
| `limit` of a facet | `search:facet:limit_out_of_range` | 1 | `EXOFIND_SEARCH_MAX_FACET_VALUES` (default 1000) |
| `depth` of a facet | `search:facet:depth_out_of_range` | 1 | 10, fixed |
| `limit` of a suggest request | `search:suggest:limit_out_of_range` | 1 | `EXOFIND_SUGGEST_MAX_LIMIT` (default 100) |
| `limit` of a `matched` field | `search:matched:limit_out_of_range` | 1 | 100, fixed |
| `k` of a `knn` clause | `search:clause:k_out_of_range` | 1 | `EXOFIND_SEARCH_MAX_KNN_K` (default 1000) |
| `depth` of a `fuse` clause | `search:clause:depth_out_of_range` | 1 | `EXOFIND_SEARCH_MAX_FUSE_DEPTH` (default 1000) |
| `slop` of a text clause | `search:clause:slop_out_of_range` | 0 | no cap |
| `window` of a `rescore` block | `search:rescore:window_out_of_range` | 1 | `EXOFIND_SEARCH_MAX_RESCORE_WINDOW` (default 1000) |
| `fragments` of a highlight | `search:highlight:fragments_out_of_range` | 1 | no cap |
| `length` of a highlight | `search:highlight:length_out_of_range` | 1 | 10000, fixed |
| `index` of an explanation | `search:explain:index_out_of_range` | 0 | no cap |
| `limit` of a document scan | `request:limit_out_of_range` | 1 | 10000, fixed |
| `limit` of an admin listing | `request:limit_out_of_range` | 1 | 1000, fixed |

A cap on the request as a whole, rather than on one field, keeps a code of its own, because there is no single field to point a caller at:

- `search:paging_too_deep`: how far `offset` plus `limit` reaches.
- `search:clauses_too_many`: clauses in the request, counted together.
- `search:clauses_too_deep`: how deeply clauses nest.
- `search:facet:ranges_too_many`: how many buckets one facet counts into.

## Error codes

The following error codes require specific handling in client applications. For the complete list of codes one endpoint answers with, see that endpoint on the [REST API pages](https://exofind.dev/api/).

- `auth:unauthenticated`: Returned with HTTP `401` and the `WWW-Authenticate: Bearer` header when the request contains no accepted credential. Absent, malformed, unknown, or lapsed credentials all return this code to prevent key enumeration.
- `auth:forbidden`: Returned when an authenticated caller lacks the required permission. The `permission` argument identifies the missing permission. When the caller has no permissions on the target index, the server returns `index:not_found` instead.
- `indexer:unavailable`: Returned with HTTP `409` when the request requires the index writer node, but no writer node is available. This occurs when no candidate node is running, no candidate sets `EXOFIND_NODE_ADDRESS`, or the request was forwarded and the index moved. Retry the request once a candidate node is available.
- `indexer:unreachable`: Returned with HTTP `502` when the request was forwarded to the index writer node, but the node did not respond. Retry the write operation.
- `indexer:leadership_unreadable`: Returned with HTTP `503` when index leadership assignments cannot be read from shared state storage. Retry the request once storage responds.
- `index:readonly`: Returned with HTTP `409` when modifying an index on a node that cannot accept writes, such as when a node loses index leadership while processing a request. Retry the request to forward it to the active writer node.
- `search:paging_too_deep`: Returned when `offset` plus `limit` reaches past `EXOFIND_SEARCH_MAX_PAGE_DEPTH`. Follow `next` or `previous` cursors instead of offset paging.
- `search:cursor:sort_mismatch`: Returned when a cursor is used with a different sort order than the sort order used to generate it, or with incompatible hit types between object field values and documents.
- `search:cursor:invalid`: Returned when `after` or `before` carries a token the engine did not issue. A cursor is opaque; pass it back unchanged.
- `search:cursor:stale`: Returned when a cursor reads as one the engine issued under this sort, and still does not name a position in it - the values it carries are the wrong number or the wrong kind for the sort as the index defines it now. A `sort` field whose type changed in the index definition leaves earlier cursors like that. Start again from the first page. A cursor the engine cannot read at all returns `search:cursor:invalid` instead, and one taken under another sort returns `search:cursor:sort_mismatch`.
- `search:paging_conflicting`: Returned when more than one of `offset`, `after` and `before` is given. Specify only one starting position.
- `search:freshness:invalid`: Returned when `freshness.atLeast` or the `X-Exofind-Freshness` header carries a token the engine did not issue. A freshness token is opaque; pass it back unchanged.
- `search:freshness:version_unsupported`: Returned when the freshness token was issued in a format version this node does not read, for example by a node of a later release during a rolling upgrade. The `version` argument carries the format version. Send the request to a node of the release that issued the token.
- `search:freshness:index_mismatch`: Returned when the freshness token is for a different index than the request path identifies. The `index` argument carries the token's index, and `expected` carries the path's index.
- `search:freshness:unavailable`: Returned with HTTP `503` when the node did not reach the state the freshness token carries within `EXOFIND_SEARCH_FRESHNESS_WAIT`. The state is late, not lost: the writer has not committed it, the push has not landed, or the node has not pulled it. The `Retry-After` header specifies when to send the same request again, and the `wait` argument carries how long the node waited. See [Freshness](search-api.md#freshness).
- `search:pages:without_offset`: Returned when `pages` is asked for from a `next` or `previous` cursor. Start numbered paging from `offset` or from a page's own cursor.
- `search:rescore:window_too_small`: Returned when `offset` plus `limit` reaches past the `window` of a `rescore` block. Widen the window, or ask for an earlier page. A rescored search cannot page past its window by counting; follow `next` instead. See [Paging a rescored search](search-api.md#paging-a-rescored-search).
- `search:rescore:window_out_of_range`: Returned when the `window` of a `rescore` block is below 1 or above `EXOFIND_SEARCH_MAX_RESCORE_WINDOW`. The `max` argument carries the cap.
- `search:limit_out_of_range`: Returned when `limit` is below 0 or above `EXOFIND_SEARCH_MAX_LIMIT`. The `max` argument carries the cap. Ask for a smaller page and follow `next` for the rest.
- `search:clauses_too_many`: Returned when a request holds more clauses than `EXOFIND_SEARCH_MAX_CLAUSES`, counted across `query`, `filters`, `hits.when`, `rescore.boost`, and the `when` of every interpret target of a `text` clause. The `path` names the clause the count ran past. The rest of the request is not validated, so a client that also has other errors to fix sees them only after this one.
- `search:clauses_too_deep`: Returned when clauses nest deeper than `EXOFIND_SEARCH_MAX_CLAUSE_DEPTH`. Flatten the query: `and` inside `and` narrows the same way as one `and` holding both clauses. Each `fallback` of an interpret target counts as one level, as does the `when` of a target.
- `search:clause:k_required`: Returned when a `knn` clause carries no `k`. A `knn` clause brings back a fixed number of neighbours, so `k` says how many.
- `search:clause:k_out_of_range`: Returned when the `k` of a `knn` clause is below 1 or above `EXOFIND_SEARCH_MAX_KNN_K`. A search measures the value against the node cap and carries `max`. A query that names documents to delete is not measured against the cap, so it returns this code only for a value below 1, and carries no `max`.
- `search:clause:depth_out_of_range`: Returned when the `depth` of a `fuse` clause is below 1 or above `EXOFIND_SEARCH_MAX_FUSE_DEPTH`. Leaving `depth` out is not a failure; each ranking is then read to the default depth. A search measures the value against the node cap and carries `max`. A query that names documents to delete is not measured against the cap, so it returns this code only for a `depth` below 1, and carries no `max`.
- `search:timeout`: Returned with HTTP `503` when a search collects for longer than `EXOFIND_SEARCH_TIMEOUT`, or a suggest request counts for longer than `EXOFIND_SUGGEST_TIMEOUT`. The results collected before the node stopped are dropped. Repeating the same request costs the same again, so narrow the search instead. The `timeout` argument carries the budget the search ran past.
- `search:suggest:limit_out_of_range`: Returned with HTTP `400` by `POST /v1alpha1/indexes/{name}/suggest` when `limit` is below 1 or above `EXOFIND_SUGGEST_MAX_LIMIT`. The `max` argument carries the largest limit allowed.
- `search:facet:limit_out_of_range`: Returned when the `limit` of a facet is below 1 or above `EXOFIND_SEARCH_MAX_FACET_VALUES`, whether the facet is counted beside a search or asked for on its own. The `max` argument carries the largest limit allowed.
- `search:rescore:hits_unsupported`: Returned when a search combines `rescore` with `hits`. A second pass scores documents, so it cannot reorder hits that are the values of an object field.
- `index:definition:unrepresentable`: Returned with HTTP `409` when the stored index definition was written by a newer API version with features that this API version cannot represent, and a `PUT` request would discard them. Send the update to a node that supports the definition. If a specific field type is unsupported, the server returns `index:field:type_unrepresentable` and names the field.
- `search:usage_unsupported`: Returned when a query uses an existing field in a manner not enabled in the index definition, or when `fields` specifies a field not defined as `stored` on an index that does not keep document copies.
- `search:source_not_kept`: Returned when `fields` specifies something only the document copy can answer on an index where `source` is `none` - an object itself, or a field below a `flattened` list - including in top-level search, within `matched`, or on `hits`. A field below single objects or a `nested` list answers without the copy when it is `stored`, and returns `search:usage_unsupported` when it is not.
- `search:facet:prefix_unsupported`: Returned by `POST /v1alpha1/indexes/{name}/facets/{field}/values` when the field is configured with `hierarchy`. The values of such a field are paths through a tree, which a prefix cannot pick from. Count the tree a level at a time with a facet instead. See [Counting down a tree](search-api.md#counting-down-a-tree).
- `search:interpret:unit_required`: Returned when the `interpret` of a `text` clause names a target field that is not a number field or declares no `unit`. Declare a `unit` on the field, or name another field. See [Choosing the fields a reading may target](search-api.md#choosing-the-fields-a-reading-may-target).
- `search:interpret:fallback_unit_mismatch`: Returned when a `fallback` target declares a different `unit` than the target it stands in for. Every target of a chain must be in one unit, so a number in the text means the same thing on every product.
- `search:clause:interpret_fields_required`: Returned when the `interpret` of a `text` clause is an object whose `fields` is empty or missing. Name at least one target, or use `"auto"` or `"off"`.
- `search:clause:interpret_when_unsupported`: Returned when the `when` of a target holds a `nested`, `knn` or `fuse` clause. `when` accepts what a `nested` clause accepts: `field`, `text`, `and`, `or`, `not` and `boost`.
- `document:source_not_kept`: Returned when attempting a partial document update on an index where `source` is `none`, or on a document indexed when `source` was `none`. Resend the entire document. A change that names only the primary key and [signal fields](field-types.md#signal-fields) does not require the source.
- `document:patch:no_match`: Returned when a change to some of a document names one value by a selector, such as `variants[sku=V-2]`, and the document holds nothing the selector matches. A selector never creates the value it names, so add a value with `variants[]` instead.
- `settings:patch:no_match`: Returned when a `PATCH` of search settings names one entry by a selector, such as `ranking.signals[field=sales]`, and nothing the selector matches is stored. A selector never creates the entry it names, so add one with `ranking.signals[]` instead.
- `settings:patch:value_invalid`: Returned when a `PATCH` of search settings names a field that cannot hold the given value, such as a list where the settings hold an object. The codes for a path a change cannot use are listed in the [Documents API](documents-api.md#constraints-and-errors) and, for search settings, in the [Admin API](admin-api.md#changing-part-of-the-search-settings).
- `document:not_found`: Returned when a request names a document that is not indexed. `GET /v1alpha1/indexes/{name}/documents/{key}` returns it with HTTP `404` when nothing is indexed under the key in the path, as of the last commit. `PATCH /v1alpha1/indexes/{name}/documents/{key}` returns it with HTTP `404`, because the URL names the document. `POST /v1alpha1/indexes/{name}/documents/update` returns it with HTTP `400` in the `errors` array, because one entry of the batch names the document. The batch returns it only when `missing` is `fail`. A change says what to change about a document, so index the document whole first.
- `document:key_conflicting`: Returned when the body of a `PATCH` of one document gives the primary key field a value other than the key in the URL. The index definition says which field is the primary key, and the URL names the document to change.
- `index:generation:is_live`: Returned when attempting to delete the live generation for an index. Promote another generation before deleting the live generation.
- `index:generation:storage_held`: Returned with HTTP `409` when creating an index or generation on storage that holds a generation the registry does not name and no delete marked. The `generation` argument names it. Run a registry repair to register the storage, or remove its objects from the bucket.
- `index:generation:live_moved`: Returned with HTTP `409` when a reindex job asks to promote the generation it filled and another generation was promoted while the job ran. The `expected` argument names the generation the job read, and `live` the generation the index serves from. The job moves to the `failed` phase; start a new job that reads from the generation named by `live`.
- `index:generation:unsettled`: Returned with HTTP `400` when a document write kept finding that the generation the index serves from had changed while the write was being made. Each attempt needs its own promote, so this does not repeat. Send the request again.
- `index:unsupported`: Returned with HTTP `409` when the index requires engine features that the node does not support. Send the request to a node running a version that supports the required features.
- `settings:not_found`: Returned with HTTP `404` when the search settings of an index are read and it has none, and when a `PUT` or `PATCH` of them carries an `If-Match` header and the index has no settings for it to match. `If-Match: *` asks for settings that exist, so it is refused the same way. Store the settings whole, without an `If-Match` header, to give the index its first ones.
- `settings:version_mismatch`: Returned with HTTP `412` when a `PUT` or `PATCH` of search settings carries an `If-Match` header that the stored version does not satisfy. Versions are compared exactly, so a weak tag matches none. Read the settings again and rebuild the change on the version that comes back.
- `storage:conflict`: Returned with HTTP `409` when the search settings kept being changed by other writers while the change was being stored. The stored settings are unchanged; retry the request.
- `settings:unrepresentable`: Returned with HTTP `409` when a `PATCH` of search settings targets settings written by a newer version with capabilities this node does not have. A change built on top of them would discard the parts the node cannot describe. Send the request to a node that supports the settings, or replace the settings with a `PUT`.
- `settings:synonyms:field_unknown`: Returned with HTTP `400` when storing search settings with a synonym set applied to a field the index does not have in the generation the index name answers from.
- `settings:synonyms:field_unsupported`: Returned with HTTP `400` when storing search settings with a synonym set applied to a field that is not searched as text in the generation the index name answers from.
- `settings:synonyms:boost_out_of_range`: Returned with HTTP `400` when storing search settings with a synonym set where the boost is not a positive number.
- `settings:synonyms:rule_invalid`: Returned with HTTP `400` when storing search settings with a synonym rule that is not exactly one kind (equivalent words, or a one-way mapping).
- `settings:typo_exclusions:field_unknown`: Returned with HTTP `400` when storing search settings with a word list applied to a field the index does not have in the generation the index name answers from.
- `settings:typo_exclusions:field_unsupported`: Returned with HTTP `400` when storing search settings with a word list applied to a field that is not searched as text in the generation the index name answers from.
- `settings:fields:field_unknown`: Returned with HTTP `400` when storing search settings with field settings for a field the index does not have in the generation the index name answers from.
- `settings:fields:interpret_unsupported`: Returned with HTTP `400` when storing search settings that read the values of a field that is not a `string` field with `filter` and `facet` and without `hierarchy`, in the generation the index name answers from.
- `settings:fields:values_unsupported`: Returned with HTTP `400` when storing search settings that declare values of a field that is not a `string` field with `facet` and without `hierarchy`, in the generation the index name answers from.
- `settings:fields:values_invalid`: Returned with HTTP `400` when storing search settings whose declared values hold an entry without a `value`, repeat a `value`, key a label by a tag that is not a canonical BCP-47 tag, hold a blank label, or declare more than 10000 values for one field. The message names the reason.
- `settings:fields:suggest_unsupported`: Returned with HTTP `400` when storing search settings that suggest the values of a field that is not a `string` field with `facet` and without `hierarchy`, in the generation the index name answers from. See [Suggesting what to search for](search-api.md#suggesting-what-to-search-for).
- `search:explain:document_not_found`: Returned with HTTP `404` by `POST /v1alpha1/indexes/{name}/search/actions/explain` when no document is indexed under the `key` query parameter.
- `search:explain:value_not_found`: Returned with HTTP `404` by `POST /v1alpha1/indexes/{name}/search/actions/explain` when that document holds no value of the search's `hits` path at the `index` query parameter.
- `search:explain:key_required`: Returned with HTTP `400` by `POST /v1alpha1/indexes/{name}/search/actions/explain` when the request carries no `key`. An explanation is of one hit, so `key` names the document it is of.
- `search:explain:index_out_of_range`: Returned with HTTP `400` by `POST /v1alpha1/indexes/{name}/search/actions/explain` when `index` is below zero. The values of the `hits` path are counted from zero.
- `request:body_malformed`: Returned with HTTP `400` when the request body is not valid JSON. The `reason` argument says what the parser could not do, and `line` and `column` say where it stopped.
- `request:property_unknown`: Returned with HTTP `400` when the body holds a property the endpoint does not have. The `path` is the path to the property, such as `fields.title.sortable`, and the `property` argument names it. The server refuses a misspelled property instead of dropping it, so check the spelling against the request fields the endpoint documents.
- `request:value_invalid`: Returned with HTTP `400` when a value does not fit the property it is written at, such as a string where a number belongs, or a `type` that names no member of a tagged union. The `path` is the path to the value, and the `reason` argument says what did not fit.
- `request:body_too_large`: Returned with HTTP `413` when the request body is larger than the node accepts, set by `quarkus.http.limits.max-body-size`. The response closes the connection. Send the documents in smaller batches.
- `request:not_found`: Returned with HTTP `404` when no endpoint answers the path. A path that an endpoint answers, naming an index that does not exist, returns `index:not_found` instead.
- `request:method_not_allowed`: Returned with HTTP `405` when the path is not answered for the HTTP method of the request. For the methods a path is answered for, see the [REST API pages](https://exofind.dev/api/).
- `request:not_acceptable`: Returned with HTTP `406` when the `Accept` header names no media type the endpoint answers in.
- `request:unsupported_media_type`: Returned with HTTP `415` when the `Content-Type` header names a media type the endpoint does not read.
- `node:error`: Returned with HTTP `500` when the node failed to serve a request that is not itself wrong. The node logs the cause. Retry the request, and read the node log if it keeps failing.
- `request:body_unreadable`: Returned with HTTP `400` when the request body stopped arriving before it was read to the end, which a streamed request finds in the middle of its work. The documents read before that are indexed. Send the rest again.
- `storage:io_error`: Returned with HTTP `409` when the record of a reindex could not be read or written. The reindex is left as it was; retry the request once the storage responds.
