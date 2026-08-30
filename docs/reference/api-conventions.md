# API conventions

Every endpoint in the Exofind HTTP API follows a shared set of conventions for routing, media types, authentication, state handling, and error reporting.

The reference pages that follow describe the API by subject. For one page per endpoint, listing every field of its request and every response it returns, see the [REST API pages](https://exofind.dev/api/). Those pages are generated from the [OpenAPI document](https://exofind.dev/openapi.yaml), which the engine build writes.

## Shape of the API

Every endpoint is served under the path prefix `/v1alpha1`. The API version is `v1alpha1`. Exofind is experimental and the API changes without maintaining backward compatibility.

The API contains the following endpoint groups:

- `/v1alpha1/admin/indexes`: define, read, and delete indexes, and trigger the actions `promote`, `commit`, `pull`, and `reindex`.
- `/v1alpha1/admin/indexes/{name}/settings`: search settings.
- `/v1alpha1/admin/keys`: API keys.
- `/v1alpha1/admin/indexers`: indexer assignments across nodes.
- `/v1alpha1/admin/registry`: audit and repair of the index registry.
- `/v1alpha1/admin/reindexes`: reindex jobs across the deployment.
- `/v1alpha1/indexes/{name}/documents`: index, read, and remove documents.
- `/v1alpha1/indexes/{name}/search`: search an index.

The endpoint `GET /q/health/ready` reports whether a node is ready. It is outside the versioned API and requires no credentials.

An index name in a path parameter represents either the index itself (such as `books`), which references its active generation, or a specific generation by name (such as `books@2`).

## Media types

Requests and responses use `application/json`.

The documents endpoints also accept and return `application/x-ndjson` (one document per line) for datasets too large to hold in memory. The NDJSON representation for read requests is registered with quality value `qs=0.9`, so a client that accepts both receives `application/json`.

## Authentication

Authentication credentials are sent in the `Authorization` header:

```http
Authorization: Bearer <key>
```

A `401 Unauthorized` response includes the `WWW-Authenticate: Bearer` header.

Authentication and authorization responses hide the deployment contents from unprivileged callers:

- Absent, malformed, unknown, and lapsed credentials all return `401 Unauthorized`.
- An index on which a key has no permissions returns `404 Not Found` (code `index:not-found`) rather than `403 Forbidden`.
- Index listings omit indexes on which the key has no permissions rather than refusing the listing.

## Requests as desired state

Most write endpoints operate as assertions of desired state:

- `PUT /v1alpha1/admin/indexes/{name}` sends an index definition in full, replacing any previous definition. Repeating the request produces the same outcome.
- Indexing a document provides the complete document with its primary key. Repeating the request replaces any existing document under that key.
- Removing a document is a statement of desired state. Requesting the deletion of an unindexed key produces a success response.
- Requests that describe modifications rather than desired state require existing resources. For example, `POST /v1alpha1/indexes/{name}/documents/actions/update` describes changes to an existing document and is refused if the document does not exist.

Because desired-state writes are idempotent, a request that times out can be sent again without inspecting the target state first.

## Conditional requests

`GET` requests for an index definition or search settings return the current version in an `ETag` header.

Clients can supply this version in the `If-Match` header on subsequent `PUT` requests. If the resource changes on the server before the `PUT` executes, the update is refused instead of overwriting the intermediate change.

Conditional requests follow these rules:

- `If-Match: *` matches any existing version of the resource.
- An `If-Match` header whose value does not match the stored version returns `412 Precondition Failed`.
- An `If-Match` header sent for a resource that does not exist returns `404 Not Found`.
- Search settings that have never been configured return `404 Not Found` rather than an empty object, ensuring the `ETag` always represents an explicit stored version.

## Serving nodes and forwarding

Clients can send any request to any node in a deployment without tracking index writer assignments.

Read requests are served locally by the node that receives them, using the data that the node has pulled.

Write requests that must run on an index writer are forwarded automatically when received by another node:

- The request is forwarded with its original HTTP method, headers, and body, including the caller credential. The destination node evaluates permissions directly; forwarding grants no additional privileges.
- A write naming an index that currently has no assigned writer causes the receiving node to claim writer leadership if it participates in indexing.
- Forwarded requests carry the header `X-Exofind-Forwarded: true`. A request that arrives with this header at a node that cannot serve it is rejected rather than forwarded a second time.
- Connecting to the index writer has a timeout of 10 seconds. Streaming request bodies are not restricted by this connection timeout.

## Status codes

The API returns the following HTTP status codes:

| Status | Meaning |
| --- | --- |
| `200 OK` | The request was served and the response carries a body. |
| `201 Created` | A key was created. Only `POST /v1alpha1/admin/keys` returns this status. |
| `202 Accepted` | A reindex job was started and runs asynchronously. Only `POST /v1alpha1/admin/indexes/{name}/actions/reindex` returns this status. |
| `204 No Content` | A resource was removed: an index, its search settings, a key, or a document named by key in the path. |
| `400 Bad Request` | The request is invalid and must change before it can be served. |
| `401 Unauthorized` | The request carries no credential accepted by this node. |
| `403 Forbidden` | The credential is known, but the key lacks permission for the action. |
| `404 Not Found` | The index, generation, key, reindex job, or search settings do not exist, or the caller key lacks permissions on the index. |
| `409 Conflict` | The request is well formed, but the current state of the deployment prevents execution. |
| `412 Precondition Failed` | The version specified in `If-Match` does not match the current stored version. |
| `500 Internal Server Error` | An unmapped engine failure occurred. The node logs the error code and root cause. |
| `502 Bad Gateway` | The request was forwarded to the index writer and the writer did not respond. |
| `503 Service Unavailable` | The node cannot serve the request at this time; retrying the same request is expected to work. |

The status codes follow a consistent operational division:

- `400` means the request itself must be modified. Changing deployment state will not make the request succeed.
- `409` means the request is valid, but the deployment state must change or an active task must complete.
- `412` means the client worked from an outdated version. The client must re-read the resource, rebuild the change against the new version, and resend.
- `502` and `503` mean the client can send the exact same request again.

### What 400 covers

Status `400 Bad Request` covers:

- Request body schema and validation failures.
- Queries requesting missing or invalid index features, such as unknown fields, fields used in ways not configured in the definition, document lookups by key on indexes without a primary key, cursors used with a different sort order than the query that created them, or stored field requests on indexes that do not retain document source copies.
- Unreadable or malformed request payloads.

### What 409 covers

Status `409 Conflict` covers:

- No node is available to write the index (`indexer:unavailable`).
- The index cannot be modified on the target node because the node lost the writer role during execution (`index:readonly`) or is currently synchronizing (`index:out-of-date`).
- An index definition conflicts with documents already stored in the generation (`index:definition:incompatible`).
- An index or definition requires an engine version newer than the node (`index:definition:unrepresentable`, `index:unsupported`).
- The index currently has no live generation (`index:no_live_generation`).
- A conflicting reindex job is running or the target generation is locked by an existing reindex (`reindex:in_progress`, `reindex:target_busy`).
- A valid change to the index registry, API keys, or search settings failed to persist. The stored state remains unchanged.
- A registry endpoint was called on a node configured with local storage rather than shared storage (`index:registry:audit_unavailable`).

### What 503 covers

Status `503 Service Unavailable` covers:

- Indexer leadership assignments could not be read from shared storage (`indexer:leadership_unreadable`).
- The request raced an index being closed to free local resources on the node (`index:closed`). Retrying the request reopens the index.

For per-endpoint status code tables, see [Admin API](admin-api.md), [Documents API](documents-api.md), and [Search API](search-api.md). For error code prefixes and vocabulary, see [Errors](errors.md).

## Error body

Every failed request returns a JSON response matching the following structure:

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

The error response fields are:

- `code`: Machine-readable code describing the overall failure.
- `message`: Human-readable summary for logging. Clients match on `code`, not `message`.
- `errors`: List of specific issues encountered. For validation failures on multiple fields, all errors are included in this array.
- `path`: JSON path or field name in the request where the issue occurred (such as `fields.title`). Omitted when the error applies to the entire request.
- `arguments`: Key-value map of string arguments used to render the message, allowing clients to format localized messages.

For validation failures, the top-level `code` is `validation`. When only one validation error occurs, its message is used as the top-level `message`; when multiple errors occur, the top-level message is `Request contains N errors`.

For errors other than validation failures, the `errors` array contains a single entry whose `code` matches the top-level `code`.

Error codes use colon-separated namespaces (such as `index:field:invalid_name`). Error codes are stable across API versions and are never renamed or reused. For the complete error code list, see [Errors](errors.md).
