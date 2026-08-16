# Run a public demo node

A node that answers searches from a browser with no credential at all - what the
[example pages](../../examples/README.md) are pointed at. It is a deliberate
narrowing of a normal node, not the shape a deployment takes: read
[Secure a deployment](secure-a-deployment.md) for that one.

## Make the key it answers as

Create a key granted `search` and nothing else, over exactly the indexes the demo
should reach:

```http
POST /v1alpha1/admin/keys
Authorization: Bearer <root key>
Content-Type: application/json

{
  "description": "the demo pages",
  "grants": [{ "permissions": ["search"], "indexes": ["livsmedel", "airports", "cleveland"] }]
}
```

Keep its `id` from the response. The credential is not needed - the demo node
answers *as* this key rather than presenting it.

## Point a node at it

```shell
EXOFIND_AUTH_MODE=keys
EXOFIND_AUTH_ROOT_KEY=sha256:...
EXOFIND_AUTH_ANONYMOUS_KEY=<the id from above>
INDEXER=false
```

A request arriving with no `Authorization` header is now answered as that key.
Everything else still needs a credential, so the same node stays administrable
with the root key.

`EXOFIND_AUTH_ANONYMOUS_KEY` is per-node configuration, so this makes one node in
a fleet public while the rest go on refusing anonymous requests.

Two guards mean a mistake here fails rather than opens up:

- The node refuses to start if the key holds any permission other than `search`.
- Anything other than `search` is left out at every request too, so widening the
  key from another node widens nothing on this one.

## Narrow what it can cost you

A public search endpoint is a public compute endpoint. Three things beyond auth
are worth doing:

- **Give it read-only storage credentials.** `REMOTE_STORAGE_ACCESS_KEY` on this
  node only needs to read the bucket. With `INDEXER=false` as well, nothing about
  this node can cost you an index even if something goes wrong in it.
- **Put a CDN or proxy in front for rate limiting.** The engine does none.
- **Cap the queries.** `SEARCH_MAX_PAGE_DEPTH` bounds how far offset paging can
  reach; searches past it are refused rather than answered slowly.

## Load the examples into it

The demo node serves what an indexer has already committed, so the examples are
loaded against that indexer rather than against this node, with a key that may
write:

```shell
NODE=https://indexer.internal.example.com KEY=<a writing key> examples/livsmedel/load.sh
```

The anonymous key above cannot do this and is not meant to - it holds `search`
alone.

## Serve the example pages against it

The pages read the node from `?api=`, from what the connection panel remembers,
or from `VITE_EXOFIND_NODE` at build time:

```shell
VITE_EXOFIND_NODE=https://demo.example.com mise run examples:build
```

They carry no key, which is the point - nothing about them models how a real
deployment is reached. A reader who points the panel at their own node gets
whatever that node allows anonymously, which for a normal node is nothing.

The node needs to answer the origin the pages are served from:

```shell
QUARKUS_HTTP_CORS_ENABLED=true
QUARKUS_HTTP_CORS_ORIGINS=https://demo.example.com
```

Credentials are only ever read from the `Authorization` header, so CORS here is
about which pages may read the answers, not about what is allowed.
