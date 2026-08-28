# Running a public demo node

This guide shows you how to configure a public demo node that answers search requests from a browser without credentials, such as for the [example pages](../../examples/README.md). This setup is a deliberate narrowing of a standard node and is not intended for production deployments. To secure a production deployment, see [Secure a deployment](secure-a-deployment.md).

## Prerequisites

Before you begin, ensure you have:

- A root key for administrative requests.
- An indexer node and a key with write permissions.

## Creating the search key

1. Send a `POST` request to `/v1alpha1/admin/keys` to create a key with the `search` permission for the demo indexes:

   ```http
   POST /v1alpha1/admin/keys
   Authorization: Bearer <root key>
   Content-Type: application/json

   {
     "description": "the demo pages",
     "grants": [{ "permissions": ["search"], "indexes": ["livsmedel", "airports", "cleveland"] }]
   }
   ```

2. Save the `id` from the response.

   You do not need the secret credential because the demo node answers as this key rather than presenting the credential.

## Configuring the demo node

1. Configure the demo node with the anonymous key and disable the indexer:

   ```shell
   EXOFIND_AUTH_MODE=keys
   EXOFIND_AUTH_ROOT_KEY=sha256:...
   EXOFIND_AUTH_ANONYMOUS_KEY=<the id from above>
   INDEXER=false
   ```

   The node answers requests that arrive without an `Authorization` header as that key. All other requests still require credentials, so you can administer the node with the root key.

   `EXOFIND_AUTH_ANONYMOUS_KEY` is a per-node setting. This setting makes one node in a fleet public while other nodes continue to reject anonymous requests.

   The node uses two guards to prevent configuration mistakes:

   - The node refuses to start if the key holds any permission other than `search`.
   - The node excludes any permission other than `search` on every request. Granting additional permissions to the key on another node does not grant additional access on this node.

## Limiting resource consumption

A public search endpoint consumes public compute resources. To limit costs and protect data, configure the following settings:

- **Configure read-only storage credentials:** Set `REMOTE_STORAGE_ACCESS_KEY` to grant read-only access to the bucket. Combined with `INDEXER=false`, this prevents modifications to an index.
- **Configure rate limiting:** Place a Content Delivery Network (CDN) or reverse proxy in front of the node. The search engine does not provide rate limiting.
- **Cap queries:** Set `SEARCH_MAX_PAGE_DEPTH` to bound offset pagination depth. The node refuses searches that exceed this depth rather than answering them slowly.

## Loading example data

1. Load example data into the indexer node using a key with write permissions:

   ```shell
   NODE=https://indexer.internal.example.com KEY=<a writing key> examples/livsmedel/load.sh
   ```

   The demo node serves data that an indexer has committed. The anonymous key cannot load data because it only holds the `search` permission.

## Serving the example pages

1. Enable Cross-Origin Resource Sharing (CORS) on the demo node so browser pages can access it:

   ```shell
   QUARKUS_HTTP_CORS_ENABLED=true
   QUARKUS_HTTP_CORS_ORIGINS=https://demo.example.com
   ```

   Credentials are only read from the `Authorization` header. CORS controls which web origins can read search responses, not which actions are permitted.

2. Build the site that holds the demo pages:

   ```shell
   PUBLIC_EXOFIND_NODE=https://demo.example.com mise run site:build
   ```

   The build decides which node the pages search. Nothing a reader or a link can change points a page somewhere else, and the pages send no credentials, so a deployment searches the one node it was built for.

## Verifying the setup

To confirm that the public demo node functions correctly:

1. Send a search request without an `Authorization` header to the demo node and confirm that it returns results for the granted indexes.
2. Open the built demo pages in a browser and confirm that search queries succeed without credentials.
