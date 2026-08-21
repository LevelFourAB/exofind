# Secure a deployment

Nodes check credentials by default. This is what to hand out, to whom, and how
to get the first key.

## Bootstrap the first key

Give one node a root key. It is allowed everything, is not stored anywhere, and
exists so that a deployment always has a way in.

```shell
openssl rand -base64 32          # keep this somewhere a person can reach it
```

```shell
EXOFIND_AUTH_ROOT_KEY=<that value> ...
```

Prefer `EXOFIND_AUTH_ROOT_KEY=sha256:$(printf %s "<that value>" | shasum -a 256 | cut -d' ' -f1)`
so the key itself is not in an environment variable.

Create the key that will do the administering from then on:

```http
POST /v1alpha1/admin/keys
Authorization: Bearer <root key>
Content-Type: application/json

{
  "description": "deployment pipeline",
  "grants": [{ "role": "admin", "indexes": ["*"] }]
}
```

The response carries the credential once. Put it wherever your pipeline keeps
secrets - it cannot be read back.

Once a stored key holds `keys.write`, other nodes need no root key of their own.
A node in `keys` mode refuses to start only when it can find neither.

## Hand out one key per thing that holds one

Three identities cover almost every deployment, and they have different
lifecycles - which is the reason to split them rather than share one key.

**Whatever applies definitions** - CI, a deploy job - holds `admin` over the
indexes it owns. Definitions live in version control, so this is a pipeline's
key, rarely a person's.

```json
{ "description": "ci", "grants": [{ "role": "admin", "indexes": ["*"] }] }
```

**Whatever loads documents** holds `writer`. Note what that excludes: a writer
cannot change a definition, so a runaway loader cannot reshape a schema.

```json
{ "description": "product feed", "grants": [{ "role": "writer", "indexes": ["products"] }] }
```

**Whatever serves searches** - your application's own backend - holds `reader`,
scoped to the indexes it queries. This is the widest deployment surface and the
least power.

```json
{ "description": "web backend", "grants": [{ "role": "reader", "indexes": ["products", "articles"] }] }
```

Scope every key with index patterns. A pattern is a name or a prefix followed by
`*`, so a per-tenant naming scheme scopes cleanly:

```json
{ "grants": [{ "role": "writer", "indexes": ["tenant-42-*"] }] }
```

Name indexes exactly where you can. A key granted `products` reaches the index
and no generation of it, so it follows the index across a rollout without being
able to address - or list - the generations it moves between. Add `products@*`
only to the key that performs rollouts; see [patterns and
generations](../reference/auth.md#patterns-and-generations).

## Search from a browser

Put your own backend in front. A key that reaches a node reaches every index its
patterns cover, with no way to narrow that per end user - so a page holding one
can be read by anyone who opens developer tools, and a page that filters results
in JavaScript filters nothing.

The exception is a page you are willing to let anyone search in full, which is
what [a demo node](run-a-demo-node.md) is for.

## Rotate a key

Keys are created and revoked, never edited. To replace one:

1. Create the new key with the grants it should have.
2. Move whatever uses it over and confirm it works.
3. Revoke the old key: `DELETE /v1alpha1/admin/keys/{id}`.

That order leaves a moment where both work rather than a moment where neither
does. Revoking takes effect on the node that served the request at once, and on
every other node within its `EXOFIND_AUTH_REFRESH_INTERVAL` (10s by default) -
so a key leaked into a public repository stops working in seconds, not at the
next deploy.

Managing keys does not need to reach any particular node, so this works while
no candidate is up to take writes.

Setting `expiresAt` on a key makes it stop working on its own, which is worth
doing for anything short-lived - a contractor's key, a one-off migration.

## Turn checking off

`EXOFIND_AUTH_MODE=none` answers every request as though it were allowed
everything. Dev mode runs this way. Anywhere reachable by more than your own
laptop, it means anyone who can reach the port can delete every index.
