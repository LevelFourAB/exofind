# Authentication

A request presents a key as a bearer token:

```http
POST /v1alpha1/indexes/books/search
Authorization: Bearer exok_4ff6b760264c1918_ePQcdT1O9HSATZoXfDbT8hhHGsP9VpZH
```

Credentials are only ever read from the `Authorization` header - never from a
cookie or a query parameter. There is therefore no ambient credential another
origin could ride, which is why CORS is a policy setting here rather than a
security boundary.

## Modes

`EXOFIND_AUTH_MODE` decides what a node checks.

| Mode | What happens |
|------|--------------|
| `keys` | Every request presents a credential, checked against the keys of the deployment and this node's root key. The default |
| `none` | Nothing is checked and every request is allowed everything. Dev mode runs this way; any other deployment has to ask for it by name |

A node in `keys` mode that can neither read the stored keys nor find a root key
of its own refuses to start, because a node nobody can administer is worse than
one that does not come up.

## Where keys live

Keys are kept wherever the indexes are - one object in the bucket in `object`
mode, one file beside the indexes in `local` mode - read by every node and
replaced conditionally on the version it was read at. That has three
consequences worth knowing:

- A key created on one node works on every node, and revoking one takes effect
  everywhere without redeploying anything.
- Revocation is not instant. A node that already holds a key keeps accepting it
  until its next read, at most `EXOFIND_AUTH_REFRESH_INTERVAL`. A key the node
  has never seen is looked up right away, so a newly created key works without
  waiting.
- Managing keys does not need any particular node, so a leaked key can be
  revoked while no candidate is up. Requests to `/v1alpha1/admin/keys` are served by
  whichever node receives them and are never passed to the indexer.

In `local` mode there is one node, so the first two say nothing new; the key
file is written readable by the user running the node alone, since a file gets
whatever the umask gave it while a bucket has access rules of its own.

A node that named `object` mode but cannot use the storage for keys has nowhere
to keep them. It answers as though none had ever been created, which leaves its
root key as the only way in.

## Permissions

A key holds **grants**. Each is a set of permissions crossed with a set of index
patterns: every permission applies to every index matched.

| Permission | Scope | Covers |
|------------|-------|--------|
| `search` | index | `POST /v1alpha1/indexes/{name}/search` |
| `documents.write` | index | Putting documents into an index |
| `documents.delete` | index | Taking documents out, by key or by query |
| `indexes.read` | index | Listing indexes, reading a definition and status |
| `indexes.write` | index | Creating an index or a generation, or replacing a definition |
| `indexes.delete` | index | Removing an index or a generation |
| `indexes.promote` | index | Making an index answer from a generation |
| `indexes.commit` | index | Committing and pushing pending changes |
| `indexes.pull` | index | Pulling the latest state now |
| `keys.read` | deployment | Listing keys |
| `keys.write` | deployment | Creating and revoking keys |

Grants are evaluated as a union - a request is allowed when any one grant allows
it. There are no deny rules.

An index pattern is the name of an index, or a prefix followed by `*`; `*` alone
is every index. Nothing richer is accepted, so what a key reaches can be read off
the pattern. Permissions of deployment scope are granted whatever the patterns
say.

Permission names are stored inside keys, so they are never renamed or reused.

### Patterns and generations

A generation is named `index@generation`, so the same patterns match it and
nothing else is needed to scope one:

| Pattern | Reaches |
|---------|---------|
| `products` | the index, and no generation of it by name |
| `products@*` | every generation of `products`, and not the index |
| `products*` | both, and every other index whose name starts that way |

The `@` appears in no name of its own, so a pattern reaching the generations of
one index can never run past it into another.

This is what lets a rollout leave keys alone. Grant the key an application holds
`products` exactly: it searches the index, follows it across a rollout without
being told one happened, and can neither address nor list the generations it
moves between. Grant the key that performs the rollout `products@*` as well, and
it reaches that index's generations and no other index's.

### Roles

`reader`, `writer` and `admin` are shorthand for a set of permissions, and they
are the three things that hold keys.

| Role | Permissions |
|------|-------------|
| `reader` | `search`, `indexes.read` |
| `writer` | `search`, `indexes.read`, `documents.write`, `documents.delete`, `indexes.commit` |
| `admin` | Everything, including managing keys |

A role is expanded when the key is created and only the permissions are stored.
Nothing reads a role afterwards, so a key granted today never gains a permission
because a later version widened what a role covers. Note that `writer` cannot
change a definition - a runaway loader cannot reshape a schema.

## Being refused

Refusal comes in two shapes, and the difference matters.

- An index the key was granted **nothing** on is answered `404 Not Found`, the
  same as an index that does not exist, and is left out of `GET
  /v1alpha1/admin/indexes`. A key cannot find out what a deployment holds by
  comparing a refusal against a miss.
- An index the key can see but not use this way is answered `403 Forbidden`,
  naming the permission that was missing.

| Status | Code | When |
|--------|------|------|
| `401 Unauthorized` | `auth:unauthenticated` | No credential, or one that is malformed, unknown or lapsed. All four answer the same. Carries `WWW-Authenticate: Bearer` |
| `403 Forbidden` | `auth:forbidden` | A known caller reaching something they were not granted |
| `404 Not Found` | `index:not-found` | An index outside every pattern of the key |

## The root key

`EXOFIND_AUTH_ROOT_KEY` is a per-node credential allowed everything. It is not
stored anywhere, cannot be listed or revoked through the API, and exists to
create the first key and to get back in after the last key that could manage
keys was deleted. Under normal operation nothing uses it.

The value is either the key itself or `sha256:<hex>` of it, so the plaintext
need not sit in an environment variable. Generate one with
`openssl rand -base64 32`.

## Answering requests that carry no credential

`EXOFIND_AUTH_ANONYMOUS_KEY` names a key id. On a node where it is set, a request
arriving with no `Authorization` header is answered as that key; on a node where
it is not, such a request is refused. It is per-node configuration, so one node
in a fleet can serve anonymous callers while the rest refuse them.

Two things keep this narrow:

- The node refuses to start if the named key holds any permission other than
  `search`.
- Permissions other than `search` are left out at every request as well, so a key
  widened from another node after the node started widens nothing here.

See [Run a public demo node](../how-to/run-a-demo-node.md) for what else such a
node needs.

## Keys API

Under `/v1alpha1/admin/keys`. A key is created and revoked, never edited -
changing what something may do means creating the key it should have, moving
whatever uses it over, and revoking the old one, which leaves a moment where
both work rather than a moment where neither does.

```
GET    /v1alpha1/admin/keys        # every key, and how this node is configured
POST   /v1alpha1/admin/keys        # create one
DELETE /v1alpha1/admin/keys/{id}   # revoke one
```

### Creating a key

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

`role` and `permissions` may both be given and are unioned. `indexes` is required
for grants holding permissions of index scope - a grant that allows nothing is
refused rather than stored, because it would look right in a listing and refuse
every request. `expiresAt` is optional; without it the key does not expire.

The response is `201 Created` and carries the credential **once**:

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

Only a hash of the secret is stored, so a credential that is lost is replaced
rather than recovered. Logs record the `id`, never the credential.

### Listing keys

```json
{
  "keys": [ { "id": "...", "grants": [], "createdAt": "..." } ],
  "rootKeyConfigured": true,
  "anonymousKey": "fe3747c2761ef89d"
}
```

`keys` is shared by every node; the two fields below it are the answering node's
own configuration, so two nodes can answer the same list differently.

### Status codes

| Status | Code | When |
|--------|------|------|
| `400 Bad Request` | `auth:key:*` | The definition names a role, permission or index pattern that does not exist, or an expiry that is not a timestamp. Every problem is reported, not the first |
| `404 Not Found` | `auth:key:not_found` | Revoking an id no key is stored under |
| `409 Conflict` | `auth:keys:unavailable` | This node has nowhere to keep keys - it named object storage that could not be used for them |
| `409 Conflict` | `auth:keys:io_error` | The storage could not be reached. The stored keys are unchanged |
| `409 Conflict` | `auth:keys:conflict` | Other nodes kept changing the keys underneath this change. The stored keys are unchanged |

## Compatibility

A stored key can carry `required_features`, naming what its meaning depends on.
A node that does not know one of those names refuses the key outright rather than
honouring the part it understands.

The list exists for anything that **narrows** a key. Grants are additive, so a
permission name a node has no code for is dropped and grants nothing, which is
safe on its own; something that narrows a key and got dropped would allow more
than was written. Nothing this version writes needs a feature name - everything a
key can currently say shipped together.
