# Make a write visible to search

You index a document, search for it straight away, and it is not there. Nothing is broken. Two separate delays sit between a write and a search that can see it. This guide shows how write visibility works in Exofind and the options you have to make written documents visible to search queries.

## Understanding the two delays

When you write a document, the change passes through two stages before all nodes can return it in search results.

```
Write -> [Commit delay on writer] -> Remote storage -> [Refresh delay on readers] -> Searchable everywhere
```

1. **The commit on the writer node**: Only one node writes to an index at a time. Changes become searchable on the writer when that node commits them to Lucene and pushes the commit to remote storage. A commit triggers automatically based on two configuration variables:
   - `EXOFIND_INDEXES_COMMIT_MAX_CHANGES` (default: `10000`): Triggers a commit when this many uncommitted changes accumulate.
   - `EXOFIND_INDEXES_COMMIT_MAX_INTERVAL` (default: `5s`): Triggers a commit when the oldest uncommitted change waits this long.
2. **The refresh on reader nodes**: Every other node serves searches from its own local copy of the index. A reader node polls the index registry, which names the version of every index the deployment holds, and pulls an index whose version has moved past its copy. `EXOFIND_INDEXES_REFRESH_INTERVAL` (default: `30s`) sets the longest a node waits before it considers pulling, and the shortest it leaves between two pulls of the same generation. The variable `EXOFIND_INDEXES_VERIFY_INTERVAL` (default: `10m`) sets the maximum time a node can go without verifying an index manifest.

With default settings, a write takes about 5 seconds to become searchable on the writer node. It becomes searchable on every node about 15 seconds after the write on an index that is otherwise quiet, and within about 35 seconds on an index that is written continuously.

In `LOCAL` storage mode, there is only one node and one copy of the index, so only the commit delay exists. In `OBJECT` storage mode with multiple nodes, both delays exist.

### Why searches miss behind a load balancer

When your application sits behind a load balancer:

- Writes are forwarded automatically to the writer node, regardless of which node receives the request.
- Searches are served locally by whichever node receives them and are never forwarded.

Because the load balancer typically sends the write and the search to different nodes, the search lands on a reader node whose local copy has not yet pulled the latest commit.

For more details on configuration settings, see the [Configuration reference](../reference/configuration.md).

## Prerequisites

- An active Exofind deployment with at least one index.
- An API key with the permissions required for the strategy you choose:
  - `indexes.read` to locate writer nodes and check status.
  - `indexes.commit` to trigger immediate commits.
  - `indexes.pull` to force a node to synchronize with storage.
  - `search` to run search queries.

## Choose a visibility strategy

Select one of the following four strategies based on your application's architecture and consistency requirements.

### Option 1: Search the writer node directly

The writer node makes changes searchable immediately after a commit finishes. If your application can route requests directly to a specific node, you can send searches straight to the writer.

1. Find the writer node by requesting the index status (requires `indexes.read`):

   ```http
   GET /v1alpha1/admin/indexes/products HTTP/1.1
   Host: exofind.example.com
   Authorization: Bearer <token>
   ```

   The response contains the `indexer` object with the writer node's address:

   ```json
   {
     "state": "USABLE",
     "readOnly": false,
     "indexer": {
       "node": "node-1",
       "address": "http://node-1.internal:8080"
     }
   }
   ```

   Alternatively, list the candidate nodes and which node writes each index using `GET /v1alpha1/admin/indexers`. An index that your key covers nothing of is left out of the listing.

2. Send your search request directly to the writer's address (requires `search`):

   ```http
   POST /v1alpha1/indexes/products/search HTTP/1.1
   Host: node-1.internal:8080
   Authorization: Bearer <token>
   Content-Type: application/json

   {
     "query": [
       { "type": "text", "text": "laptop" }
     ]
   }
   ```

**Trade-off:** This provides the lowest latency for read-after-write consistency, but it concentrates search traffic on the writer node and requires direct network access to individual nodes.

### Option 2: Explicitly commit and wait out the refresh interval

If your application cannot bypass the load balancer, trigger an explicit commit and wait for reader nodes to pull the update.

1. Trigger a commit on the index (requires `indexes.commit`):

   ```http
   POST /v1alpha1/admin/indexes/products/actions/commit HTTP/1.1
   Host: exofind.example.com
   Authorization: Bearer <token>
   ```

   You can send this request to any node behind the load balancer; it is forwarded to the writer.

2. Wait for the duration of `EXOFIND_INDEXES_REFRESH_INTERVAL` (default: 30 seconds) before executing search queries against the load balancer.

**Trade-off:** This works behind standard load balancers without special routing, but your client must tolerate a delay of up to 30 seconds.

### Option 3: Force a pull on the answering node

You can instruct a specific node to pull the latest state from remote storage immediately.

1. Ensure the writer has committed the changes, either automatically or through an explicit commit call.
2. Send a pull action directly to the node you plan to search (requires `indexes.pull`):

   ```http
   POST /v1alpha1/admin/indexes/products/actions/pull HTTP/1.1
   Host: node-2.internal:8080
   Authorization: Bearer <token>
   ```

**Trade-off:** A pull runs only on the node that receives the request and is never forwarded. This only works if your client addresses that specific node directly for both the pull and the subsequent search.

### Option 4: Design ingestion around eventual consistency

For bulk data loading or asynchronous ingestion pipelines, structure your workflow so that immediate search visibility is not required.

1. Stream write requests using `POST /v1alpha1/indexes/{name}/documents` (requires `documents.write`) without committing between batches.
2. Send a single commit request at the end of the ingestion run.
3. Wait for one `EXOFIND_INDEXES_REFRESH_INTERVAL` before directing user traffic to the index.

**Trade-off:** This yields the highest write throughput and lowest resource overhead, but requires asynchronous application design.

## Confirming the result

To verify that an index is ready and check how many documents a node can search:

1. Check the index status on the node (requires `indexes.read`):

   ```http
   GET /v1alpha1/admin/indexes/products HTTP/1.1
   Host: exofind.example.com
   Authorization: Bearer <token>
   ```

   Check these fields in the response:
   - `state`: Must be `USABLE` (or `MODIFIED` on a writer with pending local changes). A state of `NEEDS_PULL` or `PULLING` indicates the node is synchronizing with storage.
   - `readOnly`: Shows `false` on the writer node and `true` on reader nodes.
   - `indexer`: Identifies the node currently responsible for writing the index.

2. Count the searchable documents on the node by running a search with `limit` set to `0` (requires `search`):

   ```http
   POST /v1alpha1/indexes/products/search HTTP/1.1
   Host: exofind.example.com
   Authorization: Bearer <token>
   Content-Type: application/json

   {
     "limit": 0,
     "total": "exact"
   }
   ```

   The `total.count` field in the response shows how many documents the answering node can search. Uncommitted documents are not included in this count. Ask for `"total": "exact"` as shown: counting defaults to `"estimate"`, which stops once the count exceeds the returned window and reports `total.exact` as `false`, so an estimate cannot tell you whether one particular write has arrived.

## What Exofind does not provide

Nothing in the API tells you whether a particular node holds a particular write:

- **No version tokens or receipts**: Exofind does not provide acknowledgement tokens, transaction IDs, or sequence numbers that you can poll. You cannot ask a node if it contains a specific write.
- **No response markers**: Search responses do not include generation markers or metadata indicating which index commit answered the query.
- **Status is not a receipt**: An index state of `USABLE` on a reader node indicates that the local index is healthy and operational; it does not guarantee that the node holds the most recent commit.
- **Search keys cannot query admin status**: An API key with only the `search` permission cannot call commit, pull, or status endpoints. Applications that manage visibility must have keys with administrative permissions (`indexes.read`, `indexes.commit`, `indexes.pull`).

## Related

- [Index documents](index-documents.md) - Sending documents, loading a dataset, and committing once at the end.
- [Search an index](search-an-index.md) - The search request.
- [Run more than one node](run-multiple-nodes.md) - Configuring which nodes take the writes.
- [Configuration](../reference/configuration.md) - Every commit and refresh variable.
- [Admin API](../reference/admin-api.md) - Index status, index states, and the actions.
- [API conventions](../reference/api-conventions.md) - Why a write is forwarded to the writer while a search is served where it lands.
- [Architecture](../explanation/architecture.md) - Why storage is the source of truth.
- [Synchronization](../explanation/synchronization.md) - What keeps two writers from corrupting an index.
