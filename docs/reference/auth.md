# Authentication

Authentication in Exofind verifies API requests using bearer tokens in the `Authorization` header. Exofind reads credentials only from the `Authorization` header, never from cookies or query parameters. Cross-Origin Resource Sharing (CORS) is a policy setting rather than a security boundary because requests carry no ambient credentials.

A request presents an API key as a bearer token in the `Authorization` header:

```http
POST /v1alpha1/indexes/books/search
Authorization: Bearer exok_4ff6b760264c1918_ePQcdT1O9HSATZoXfDbT8hhHGsP9VpZH
```

## Modes

The `EXOFIND_AUTH_MODE` environment variable controls the authentication mode for a node:

| Mode | Description |
|------|-------------|
| `keys` | Requires every request to present a credential, which is checked against deployment keys and the node's root key. This is the default. |
| `none` | Disables authentication checks and allows all requests. Development mode uses this setting. Other deployments must specify it explicitly. |

A node configured in `keys` mode refuses to start if it cannot read stored keys and has no root key configured.

## Where keys live

Keys are stored alongside indexes: as an object in the storage bucket in `object` mode, or as a file beside the indexes in `local` mode. Every node reads this storage and updates keys conditionally based on the version read. Key synchronization behaves as follows:

- A key created on one node works on all nodes. Revoking a key takes effect across all nodes without redeployment.
- Revocation takes effect within the duration configured by `EXOFIND_AUTH_REFRESH_INTERVAL`. A node accepts a cached key until its next storage read. A node looks up an unseen key immediately, so newly created keys work without delay.
- Key management does not depend on a specific node. Requests to `/v1alpha1/admin/keys` are handled directly by the node that receives them and are not forwarded to the indexer.

In `local` mode, the single node writes the key file with permissions determined by the running process umask.

If a node configured in `object` mode cannot access object storage for keys, it acts as though no keys exist. In this state, only the root key can authenticate requests.

## Permissions

A key contains grants. Each grant combines a set of permissions with a set of index patterns. Every permission in the grant applies to every matching index.

Exofind supports the following permissions:

| Permission | Scope | Description |
|------------|-------|-------------|
| `search` | index | Executes search queries using `POST /v1alpha1/indexes/{name}/search`. |
| `documents.read` | index | Reads documents back out of an index using `GET /v1alpha1/indexes/{name}/documents`. |
| `documents.write` | index | Adds documents to an index. |
| `documents.delete` | index | Deletes documents by key or by query. |
| `indexes.read` | index | Lists indexes, reads index definitions and status, and views writer node assignments. |
| `indexes.write` | index | Creates an index or generation, or replaces an index definition. |
| `indexes.delete` | index | Deletes an index or generation. |
| `indexes.promote` | index | Promotes an index to serve from a generation. |
| `indexes.commit` | index | Commits and pushes pending changes. |
| `indexes.pull` | index | Pulls the latest index state. |
| `keys.read` | deployment | Lists API keys. |
| `keys.write` | deployment | Creates and revokes API keys. |

Grants are evaluated as a union: a request is allowed if any grant permits it. There are no deny rules.

An index pattern is either the exact name of an index or a prefix followed by an asterisk (`*`). A single asterisk (`*`) matches all indexes. Deployment-scoped permissions apply regardless of specified index patterns.

Permission names are stored inside keys and are immutable.

### Patterns and generations

Generations are named in the format `index@generation`. Index patterns match generations as follows:

| Pattern | Description |
|---------|-------------|
| `products` | Matches the `products` index, but no named generation of it. |
| `products@*` | Matches every generation of `products`, but not the `products` index itself. |
| `products*` | Matches the `products` index, its generations, and any other index whose name starts with `products`. |

The `@` character cannot be used in index names, so a pattern matching generations of one index cannot match another index.

For example, granting `products` allows an application to query the index across rollouts without permitting access to specific generations. Granting `products@*` allows a rollout process to manage generations of `products` without granting access to other indexes.

### Roles

Roles provide shorthand sets of permissions when creating keys:

| Role | Permissions |
|------|-------------|
| `reader` | `search`, `indexes.read` |
| `writer` | `search`, `indexes.read`, `documents.read`, `documents.write`, `documents.delete`, `indexes.commit` |
| `admin` | All permissions, including key management (`keys.read`, `keys.write`) |

When a key is created, roles are expanded into their constituent permissions. Only the resulting permissions are stored in the key. Existing keys do not change permissions if role definitions change in later software versions. The `writer` role does not include `indexes.write` and cannot modify index definitions.

## Being refused

When a request fails authorization, the server responds based on key permissions:

- If a key has no grants matching an index, the server returns `404 Not Found` and omits the index from `GET /v1alpha1/admin/indexes`.
- If a key matches an index pattern but lacks the required permission for the operation, the server returns `403 Forbidden` and names the missing permission.

The server returns the following authentication and authorization HTTP status codes:

| Status | Code | Description |
|--------|------|-------------|
| `401 Unauthorized` | `auth:unauthenticated` | The request contains no credential, or the credential is malformed, unknown, or expired. The response includes a `WWW-Authenticate: Bearer` header. |
| `403 Forbidden` | `auth:forbidden` | The authenticated caller lacks the required permission for the requested action. |
| `404 Not Found` | `index:not-found` | The requested index does not match any index pattern in the key. |

## The root key

The `EXOFIND_AUTH_ROOT_KEY` environment variable defines a per-node credential with full administrative permissions. The root key is not stored in key storage and cannot be listed or revoked through the API.

The value can be the plain text key string or its SHA-256 hash formatted as `sha256:<hex>`.

To generate a root key value, run:

```bash
openssl rand -base64 32
```

## Answering requests that carry no credential

The `EXOFIND_AUTH_ANONYMOUS_KEY` environment variable specifies a key ID for unauthenticated requests. When configured on a node, requests without an `Authorization` header execute with the permissions of the specified key. When unset, unauthenticated requests are rejected.

Anonymous keys have the following restrictions:

- A node refuses to start if the referenced key contains any permission other than `search`.
- Permissions other than `search` are omitted at request evaluation time if the referenced key is modified after node startup.

For more information on configuring demo environments, see [Run a public demo node](../how-to/run-a-demo-node.md).

## Keys API

The keys API manages deployment API keys under `/v1alpha1/admin/keys`. Keys are immutable: they can be created and revoked, but not modified.

The API provides the following endpoints:

```http
GET    /v1alpha1/admin/keys        # List all keys and node configuration
POST   /v1alpha1/admin/keys        # Create a key
DELETE /v1alpha1/admin/keys/{id}   # Revoke a key
```

### Creating a key

To create a key, send a `POST` request to `/v1alpha1/admin/keys`:

```http
POST /v1alpha1/admin/keys
Content-Type: application/json

{
  "description": "the search backend",
  "grants": [
    { "role": "reader", "indexes": ["books", "movies-*"] },
    { "permissions": ["documents.write"], "indexes": ["events"] }
  ],
  "expiresAt": "2027-01-01T00:00:00Z"
}
```

The request body supports the following fields:

- `description` (optional): A string describing the key.
- `grants` (required): An array of grant objects. Each grant specifies `role`, `permissions`, or both (evaluated as a union). The `indexes` array is required for grants containing index-scoped permissions.
- `expiresAt` (optional): An ISO 8601 timestamp string defining when the key expires. If omitted, the key does not expire.

A successful request returns `201 Created` with the generated credential string and key metadata. The full secret credential is returned only once in this response:

```json
{
  "credential": "exok_4ff6b760264c1918_ePQcdT1O9HSATZoXfDbT8hhHGsP9VpZH",
  "key": {
    "id": "4ff6b760264c1918",
    "description": "the search backend",
    "grants": [
      { "permissions": ["indexes.read", "search"], "indexes": ["books", "movies-*"] },
      { "permissions": ["documents.write"], "indexes": ["events"] }
    ],
    "createdAt": "2026-08-16T12:09:33.198275Z",
    "expiresAt": "2027-01-01T00:00:00Z"
  }
}
```

Key secrets are stored only as hashes. A lost credential cannot be recovered and must be replaced. Server logs record the key `id`, never the `credential` value.

### Listing keys

To list keys, send a `GET` request to `/v1alpha1/admin/keys`:

```json
{
  "keys": [ { "id": "...", "grants": [], "createdAt": "..." } ],
  "rootKeyConfigured": true,
  "anonymousKey": "fe3747c2761ef89d"
}
```

The `keys` array contains deployment keys shared across all nodes. The `rootKeyConfigured` and `anonymousKey` fields reflect the local configuration of the node answering the request.

### Status codes

The keys API returns the following error status codes:

| Status | Code | Description |
|--------|------|-------------|
| `400 Bad Request` | `auth:key:*` | The request specifies an unknown role, permission, or index pattern, or an invalid timestamp in `expiresAt`. All validation errors are reported. |
| `404 Not Found` | `auth:key:not_found` | The specified key ID does not exist for revocation. |
| `409 Conflict` | `auth:keys:unavailable` | Key storage is unavailable because object storage cannot be used for keys on this node. |
| `409 Conflict` | `auth:keys:io_error` | Key storage could not be reached. Stored keys are unchanged. |
| `409 Conflict` | `auth:keys:conflict` | Concurrent updates from other nodes conflicted with this request. Stored keys are unchanged. |

## Compatibility

A stored key can include a `required_features` list. If a node does not recognize a feature named in `required_features`, it rejects the key completely.

The `required_features` field is used for features that narrow key permissions. Because grants are additive, unrecognized permission names are ignored without granting additional access. Dropping an unrecognized restriction would permit unauthorized actions. Current versions do not write any feature names to `required_features`.
