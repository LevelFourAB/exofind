# Errors

Failures share one body:

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

- `code` identifies the kind of failure. Codes are written by callers, so
  they are stable: never renamed and never reused for something else.
- `message` is for a human reading a log; its wording may change, so match
  on `code`, not on it.
- `errors` is present on a validation failure and carries every problem
  found rather than the first one, so they can be fixed in one go. Each
  entry has its own `code`, a `path` locating it in the request, and
  `arguments` holding the values the message was built from.

The HTTP status says whose problem it is - see the status tables in the
[admin API](admin-api.md#status-codes); `400 Bad Request` covers both a
rejected body and a query asking an index for something it does not have.

## Code vocabulary

Codes are namespaced by colon. The prefix says which part of a request the
problem is in:

| Prefix | Covers | Examples |
|--------|--------|----------|
| `request:*` | The body of a request being unreadable before anything looked at what it says | `request:missing_body`, `request:document:malformed`, `request:delete:target_required` |
| `auth:*` | Who the caller is and what they may do | `auth:unauthenticated`, `auth:forbidden` |
| `auth:key:*` | A key being rejected at validation, or named by an id nothing is stored under | `auth:key:unknown_role`, `auth:key:unknown_permission`, `auth:key:not_found` |
| `auth:keys:*` | A change to the keys that could not be stored | `auth:keys:unavailable`, `auth:keys:conflict`, `auth:keys:io_error` |
| `index:field:*` | A field in a definition being rejected at validation | `index:field:invalid_name`, `index:field:sorting_not_supported`, `index:field:vector:missing_dimensions` |
| `index:field:analyzer:*` | An analysis chain being rejected | `index:field:analyzer:unknown_ref`, `index:field:analyzer:unsupported_locale` |
| `index:schema:*` | Rules spanning the whole definition | `index:schema:multiple_primary_keys`, `index:schema:unsupported_features` |
| `index:ranking:*` | Tie breakers and ranking signals referring to fields wrongly, or a signal shaped in a way its field cannot answer for | `index:ranking:field_not_sortable`, `index:ranking:signal:shape_not_supported`, `index:ranking:signal:invalid_pivot` |
| `index:locale_fallback:*` | A locale fallback chain naming locales wrongly | `index:locale_fallback:locale_not_held`, `index:locale_fallback:unsupported_locale` |
| `index:resources:*` | Shared resources being rejected | `index:resources:synonyms:one_sided` |
| `index:definition:*` | The stored definition being beyond what this version of the API can describe | `index:definition:unrepresentable` |
| `index:generation:*` | A generation being named where it cannot be used, or one that cannot be removed | `index:generation:already_exists`, `index:generation:is_live`, `index:generation:name_required`, `index:generation:not_creatable` |
| `index:registry:*` | A change to which indexes and generations exist that could not be stored | `index:registry:conflict`, `index:registry:io_error` |
| `index:update:*` | A document being refused while indexing | `index:update:required_field_missing`, `index:update:number:out_of_bounds`, `index:update:locale_not_declared`, `index:update:primary_key_required` |
| `index:source:*` | The copy of a document as it was given being needed and not there | `index:source:not_kept`, `index:source:unreadable` |
| `index:query:*` | A query asking an index for something it does not have | `index:query:field_not_found`, `index:query:usage_not_enabled` |
| `search:clause:*`, `search:matcher:*`, `search:sort:*`, `search:highlight:*`, `search:facet:*`, `search:signal:*` | A malformed part of a search request | `search:clause:field_required`, `search:matcher:range_empty`, `search:sort:origin_required`, `search:highlight:fields_required`, `search:facet:duplicate_name`, `search:signal:shape_invalid` |
| `search:cursor:*`, `search:page*` | Paging | `search:cursor:sort_mismatch`, `search:page:too_deep` |
| Other `index:*` | The index itself | `index:already_exists`, `index:readonly`, `index:no_primary_key`, `index:closed`, `index:io_error`, `index:unsupported`, `index:no_live_generation` |

The codes worth handling specially in a client:

- `auth:unauthenticated` - no credential this node accepts. A credential
  that is absent, malformed, unknown or lapsed all answer this, so that the
  answers cannot be compared to find out which keys exist. Answered as
  `401` with `WWW-Authenticate: Bearer`.
- `auth:forbidden` - a known caller reaching something they were not
  granted; the `permission` argument names what was missing. An index the
  caller was granted nothing at all on answers `index:not-found` instead,
  so a refusal never confirms that an index exists.
- `index:readonly` - the request modifies an index on a node that is not
  the indexer. Comes with a `307` redirect when the indexer is known and a
  `409` when it is not.
- `search:page:too_deep` - the offset asked for is past
  `SEARCH_MAX_PAGE_DEPTH`. Follow `next`/`previous` cursors instead.
- `search:cursor:sort_mismatch` - a cursor was used under a different sort
  than it was handed out under.
- `index:definition:unrepresentable` - the definition the index holds was
  written by a version of the API that can describe more than this one, and
  a `PUT` here would drop what it cannot see. Answered as `409`; send the
  update to a node that knows the definition rather than changing the body.
  A field type this version has no model for is reported as
  `index:field:unrepresentable_type`, naming the field.
- `index:query:usage_not_enabled` - the field exists but the definition
  never asked for it to be used this way. Refused rather than answered with
  no results, because the two look the same to a caller and only one of
  them can be fixed.
- `index:source:not_kept` - changing part of a document on an index whose
  `source` is `none`, or on a document indexed while it was. There is
  nothing to merge the change into, so the document has to be sent whole.
- `index:generation:is_live` - the generation named is the one its index
  answers from, and removing it would leave the index answering for nothing.
  Promote another generation first.
- `index:unsupported` - the index says it needs engine features this node
  does not have, so the node refuses to resolve its name rather than answer
  from a generation the deployment did not name. Answered as `409`; use a
  node running a version that knows them.
