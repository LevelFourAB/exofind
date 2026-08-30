# Securing a deployment

Use this guide to secure an Exofind deployment by bootstrapping an initial administrative key and creating scoped keys for your services. Exofind nodes check credentials by default.

## Prerequisites

Before you begin, make sure that you have:

- An Exofind deployment with nodes running in `keys` mode (the default).
- A tool such as OpenSSL to generate random strings.
- Network access to an Exofind node.

## Bootstrapping the initial key

To create the first administrative key, provide a root key to one node:

1. Generate a root key secret:

   ```shell
   openssl rand -base64 32          # keep this somewhere a person can reach it
   ```

   Store this value securely. The root key has full permissions, is not stored anywhere, and ensures that a deployment always has an entry point.

2. Set the root key on one node using the `EXOFIND_AUTH_ROOT_KEY` environment variable:

   ```shell
   EXOFIND_AUTH_ROOT_KEY=<that value> ...
   ```

   Prefer setting a hashed key so the plaintext value does not appear in an environment variable:

   ```shell
   EXOFIND_AUTH_ROOT_KEY=sha256:$(printf %s "<that value>" | shasum -a 256 | cut -d' ' -f1)
   ```

3. Create the administrative key that manages authentication from then on:

   ```http
   POST /v1alpha1/admin/keys
   Authorization: Bearer <root key>
   Content-Type: application/json

   {
     "description": "deployment pipeline",
     "grants": [{ "role": "admin", "indexes": ["*"] }]
   }
   ```

   The response returns the credential once. Store it in your continuous integration (CI) secrets store; you cannot retrieve it again.

## Creating scoped keys for services

Three identities cover almost every deployment. Because they have different lifecycles, create separate keys for each component instead of sharing one key:

1. Create an administrative key for definition management. Components that apply definitions, such as CI or deploy jobs, require the `admin` role over the indexes they manage:

   ```json
   { "description": "ci", "grants": [{ "role": "admin", "indexes": ["*"] }] }
   ```

   Definitions live in version control, so assign this key to a pipeline rather than an individual.

2. Create a writer key for loading documents. Components that load documents require the `writer` role:

   ```json
   { "description": "product feed", "grants": [{ "role": "writer", "indexes": ["products"] }] }
   ```

   A writer cannot change definitions, preventing a runaway loader from reshaping a schema.

3. Create a reader key for search queries. Components that serve searches, such as your application backend, require the `reader` role scoped to the indexes they query:

   ```json
   { "description": "web backend", "grants": [{ "role": "reader", "indexes": ["products", "articles"] }] }
   ```

   This key has the widest deployment surface and the least privilege.

Scope every key with index patterns. A pattern is a name or a prefix followed by `*`. For example, use a prefix pattern for per-tenant scoping:

```json
{ "grants": [{ "role": "writer", "indexes": ["tenant-42-*"] }] }
```

Name indexes exactly where possible. A key granted `products` accesses the index and no generations of it, following the index across rollouts without being able to address or list the generations it moves between. Add `products@*` only to keys that perform rollouts. For more details, see [patterns and generations](../reference/auth.md#patterns-and-generations).

## Confirming the configuration

To confirm your deployment security setup:

- Verify that your components can authenticate and perform their allowed actions with their assigned keys.
- Once a stored key holds `keys.write`, verify that additional nodes start without a root key configured. A node running in `keys` mode refuses to start only when it cannot find a root key or a stored key with `keys.write`.

## Rotating a key

Keys are created and revoked, never edited. To replace a key:

1. Create the new key with the required grants.
2. Update the service that uses the key and confirm that it works.
3. Revoke the old key:

   ```http
   DELETE /v1alpha1/admin/keys/{id}
   ```

This order ensures that both keys work momentarily during the transition, preventing downtime.

Revocation takes effect immediately on the node that served the request, and on every other node within its `EXOFIND_AUTH_REFRESH_INTERVAL` (10s by default). A key leaked into a public repository stops working in seconds.

Managing keys does not require reaching a specific node, so rotation works while no candidate is up to take writes.

To make short-lived credentials expire automatically, set `expiresAt` when creating the key. Use this for temporary access, such as for a contractor or a one-off migration.

## Searching from a browser

Put your own backend in front of search requests. A key reaches every index its patterns cover and cannot be narrowed per end user, so a page that holds one hands every visitor that reach - see [Trust model](../explanation/trust-model.md).

The exception is a page that you permit anyone to search in full, as described in [a demo node](run-a-demo-node.md).

## Disabling credential checking

Setting `EXOFIND_AUTH_MODE=none` answers every request as though it were allowed everything. Dev mode runs this way. In any environment reachable by more than your own laptop, this setting allows anyone who can reach the port to delete every index. For both modes, see [Modes](../reference/auth.md#modes).

## Related

- [Authentication](../reference/auth.md) - Keys, permissions, roles, and the keys API.
- [Trust model](../explanation/trust-model.md) - What a key reaches, and why it cannot be narrowed per end user.
- [Run a public demo node](run-a-demo-node.md) - Answering searches with no credentials on purpose.
- [Configuration](../reference/configuration.md) - The authentication settings and the bootstrap key.
- [Generate an API client](generate-a-client.md) - Giving a generated client its key.
- [Deploy on Kubernetes](deploy-on-kubernetes.md) - Holding keys in a cluster.
