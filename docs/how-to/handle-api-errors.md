# Handle errors in a client

This guide shows you how to parse error responses from the Exofind API, route failures by status code, inspect refused input fields, and safely retry or recover failed requests. Use this guide when building an API client or integration.

For the complete list of error codes and prefixes, see [Errors](../reference/errors.md). For details on status code semantics across endpoints, see [API conventions](../reference/api-conventions.md).

## Prerequisites

Before handling errors, ensure your client can:

- Make HTTP requests to an Exofind endpoint under `/v1alpha1`.
- Parse JSON response bodies.

## Read the error body and match on the code

Every failed request returns an `application/json` payload with a top-level `code`, a human-readable `message`, and an `errors` array.

1. Parse the JSON response body when the HTTP status is `4xx` or `5xx`.
2. Inspect the machine-readable `code` field instead of the `message` string. The `message` string is intended for logs and can change, whereas `code` values remain stable across versions.
3. If your client encounters an unknown code under a known namespace prefix (such as an unfamiliar `index:field:*` code), handle it using the fallback behavior for that prefix.

An error response uses the following structure:

```json
{
  "code": "validation",
  "message": "Request contains 2 errors",
  "errors": [
    {
      "code": "index:field:invalid_primary_key_multiple",
      "message": "Field `id` is marked as a primary key and multiple, primary keys can not have multiple values",
      "path": "id",
      "arguments": {
        "name": "id"
      }
    }
  ]
}
```

## Locate refused input using path and arguments

When a request fails validation or contains bad field definitions, use the `errors` array to show your caller exactly what was refused.

1. Iterate over the `errors` list in the response body.
2. Read the `path` property to locate the specific field or position in your payload that caused the failure. If `path` is absent or `null`, the error applies to the request as a whole.
3. Read the `arguments` map to retrieve the specific values associated with the error code. Arguments are formatted as strings so you can construct custom, localized error messages without parsing English text.

## Decide how to handle each HTTP status code

Categorize the failure by HTTP status code to determine whether to fix the request, retry, or reload state:

- **Fix the request without retrying (`400`, `401`, `403`, `404`)**:
  - `400 Bad Request`: The payload or query is invalid. Fix the payload or query parameters before sending again.
  - `401 Unauthorized`: The credential is missing, malformed, or invalid. Present a valid `Authorization: Bearer <key>` credential.
  - `403 Forbidden`: The credential lacks permission for the requested action.
  - `404 Not Found`: The resource does not exist, or your credential has no access to the index. Do not retry without changing the target resource path.
- **Wait and retry the same request (`409`, `502`, `503`)**:
  - `409 Conflict`: The request is well-formed, but the deployment state currently prevents execution (for example, another reindex is running, or an indexer node is synchronizing). Wait and retry the request once state changes.
  - `502 Bad Gateway`: The request was forwarded to the index writer node, but the writer did not respond. Retry the request.
  - `503 Service Unavailable`: The node temporarily cannot serve the request (such as during index reopening or leadership lookups). Retry the request.
- **Re-read and rebuild (`412`)**:
  - `412 Precondition Failed`: The version in your `If-Match` header no longer matches the stored `ETag`. Fetch the latest version of the resource (`GET`), apply your intended changes onto the new version, and send the update with the new `ETag`.

## Retry safely based on request idempotency

When a network timeout occurs or the server returns `502` or `503`, determine whether the request expresses desired state before repeating it:

- **Full index definitions and document writes**: `PUT /v1alpha1/admin/indexes/{name}` and document indexing endpoints declare desired state. Documents carry their own primary keys and overwrite earlier versions. If a timeout occurs, you can resend the request without checking server state first.
- **Document removals**: Removing a document by primary key states desired state. Resending the removal after a timeout is safe.
- **Partial document updates**: `POST /v1alpha1/indexes/{name}/documents/actions/update` describes modifications to an existing document rather than full state. Do not blindly repeat partial updates if a request times out; verify the document's state first.

## Recover from refused document batches

Documents are taken in the order you sent them, and the first document the index refuses fails the request. The documents before it are already in the index and are committed with everything else.

1. Inspect `errors[0].path` in the `400` response to determine the index of the refused document in the batch. Documents preceding the refused document in the payload were already processed into the index.
2. Correct the invalid document in your batch.
3. Resend the entire batch. Because document writes represent desired state, reprocessing the previously accepted documents in the batch produces the intended state without duplicating records.

## Confirm the error handling

Verify that your client handles failure conditions as expected:

1. Send a request with an invalid field name to confirm your client parses the `errors` array, extracts `path` and `arguments`, and surfaces a `400` validation error without retrying.
2. Send a `PUT` request with an outdated `If-Match` header to confirm your client detects `412 Precondition Failed`, fetches the fresh `ETag`, and reapplies the update.
3. Simulate a `503 Service Unavailable` response to verify that your client waits and retries idempotent operations.
