# Admin API

The Admin API manages indexes under the `/v1alpha1/admin` path. Request and response bodies use JSON.

To index documents, use the [Documents API](documents-api.md). To manage API keys, see [Authentication](auth.md).

Every request requires credentials. For required permissions and information on why unauthorized indexes return `404 Not Found` instead of `403 Forbidden`, see [Authentication](auth.md).

## Endpoints

The Admin API provides the following endpoints:

```text
GET    /v1alpha1/admin/indexes                          # indexes the deployment holds
GET    /v1alpha1/admin/indexes/{name}                   # definition and status
PUT    /v1alpha1/admin/indexes/{name}                   # create or replace the definition
DELETE /v1alpha1/admin/indexes/{name}                   # remove an index or a generation
POST   /v1alpha1/admin/indexes/{name}/actions/promote   # answer for this generation
POST   /v1alpha1/admin/indexes/{name}/actions/commit    # push pending changes
POST   /v1alpha1/admin/indexes/{name}/actions/pull      # fetch the latest state now
POST   /v1alpha1/admin/indexes/{target}/actions/reindex # start a job
GET    /v1alpha1/admin/indexes/{name}/actions/reindex   # job status
POST   /v1alpha1/admin/indexes/{name}/actions/reindex/cancel # stop a job

GET    /v1alpha1/admin/indexers                         # which node writes which index
GET    /v1alpha1/admin/reindexes                        # every job across the deployment
```

Requests that modify an index (all endpoints except read requests and `pull`) run on the node that holds that index. The holder node can differ for each index. If another node receives the request, it forwards the request with the original credentials to the holder node and returns the holder's response.

When no node holds an index, the first candidate node that receives a write claims the index. If no candidate node is available to forward to, or if no candidate node sets `NODE_ADDRESS`, the server returns `409 Conflict`. If a holder node does not respond, the server returns `502 Bad Gateway`.

## Names and generations

An index holds generations. Documents and definitions belong to a generation rather than directly to the index. One generation is live, which means the index serves queries from it. An index name without a generation specifier refers to the live generation.

The `{name}` parameter accepts two formats:

| Name format | Description |
|-------------|-------------|
| `products` | The index, serving from the live generation. |
| `products@2` | Generation `2` of `products`, whether live or not. |

The `@` character is reserved and cannot appear in an index name. A generation is only reachable through its parent index. The registry state shared across the deployment tracks which generations exist and which generation is live.

Updating an index definition in place does not reindex existing documents. If a definition change affects indexing—such as adding `matching`, changing an analysis chain, or editing a synonym set—create and populate a new generation, then promote it. For step-by-step instructions, see [Roll out a definition change](../how-to/roll-out-a-definition-change.md).

A `DELETE` request on an index deletes the index and all of its generations. A `DELETE` request on a generation deletes only that generation. Deleting the live generation fails with `index:generation:is_live` until you promote another generation. Deleting an index or generation removes it from the shared registry across the deployment; other nodes remove their local copies during their next registry read. Deletion does not remove data held in remote storage. Both operations return `204 No Content`.

The `promote` action configures the index to serve from the specified generation. The change takes effect immediately on the receiving node and within `INDEXES_REFRESH_INTERVAL` on all other nodes. To roll back a deployment, promote the previous generation.

## Index resource

A `GET` request on an index endpoint and every successful `PUT` request return an index resource:

```json
{
  "name": "products",
  "generation": "2",
  "live": true,
  "version": "9f2c1a0b3d4e5f60",
  "definition": { "...": "as stored" },
  "status": {
    "state": "USABLE",
    "readOnly": false,
    "indexer": { "node": "node-a-7f21", "address": "http://node-a:8080" },
    "luceneCompatibility": "CURRENT",
    "luceneCreatedMajor": 10
  },
  "generations": [
    { "name": "1", "live": false, "createdAt": "2026-08-01T09:14:22Z" },
    { "name": "2", "live": true, "createdAt": "2026-08-16T11:02:07Z" }
  ]
}
```

The resource contains the following fields:

- `name`: The name of the index.
- `generation`: The generation described in the response. When the request specifies only the index name, this is the live generation.
- `live`: A boolean indicating whether this generation is the live generation.
- `version`: An identifier for the definition, also returned in the `ETag` header. Pass this value in the `If-Match` header on `PUT` requests to prevent overwriting concurrent updates.
- `definition`: The active index definition. See [Field types](field-types.md). Presets are stored expanded; the response returns the expanded chain rather than the preset name.
- `status`: The observed state reported by the answering node. The API does not accept this object as input.
- `generations`: A list of all generations for the index, including name, live status, and creation timestamp (`createdAt`).

A `PUT` request that creates a resource returns `201 Created`. A `PUT` request that updates an existing resource returns `200 OK`. The request body must contain the full definition; any omitted settings are removed.

The target of a `PUT` request depends on the name format:

| `PUT` target | Create behavior | Update behavior |
|--------------|-----------------|-----------------|
| `products` | Creates the index with an initial generation named `1`. | Updates the definition of the live generation. |
| `products@2` | Creates generation `2` under an existing index. | Updates the definition of generation `2`. |

A newly created generation contains no documents and is not live; the index continues serving from the previous live generation. A `PUT products@2` request on a non-existent index returns `404 Not Found`.

If an index definition contains settings from a newer API version that the current node does not recognize, reading the index returns `409 Conflict`. Updating such an index is rejected with `409 Conflict` and the error code `index:definition:unrepresentable`. To resolve these errors, send the request to a node running a version that supports the definition.

## Index states

The `status.state` field indicates the remote synchronization state as observed by the answering node:

| State | Description |
|-------|-------------|
| `NEEDS_PULL` | A newer remote state exists and has not been pulled yet. |
| `PULLING` | The node is fetching remote state. The state becomes `USABLE` when complete. |
| `USABLE` | The index is serving searches. On a read-only node, data is as current as the last pull. |
| `MODIFIED` | The index has local changes that are not yet pushed. Only writer nodes reach this state. |
| `PUSHING` | The node is pushing local changes. The state becomes `USABLE` when complete. |
| `UNSUPPORTED` | The definition requires engine features not present on this node version. Upgrade the node to resolve. |
| `INCOMPATIBLE` | The Lucene files are too old for this build to open. Reindexing into a new generation is required. |
| `CLOSED` | The index is closed on this node. A new request opens a fresh instance. |

The `status.readOnly` field indicates whether the answering node can modify the index. Only the node holding the index can modify it.

The `status.indexer` object identifies the holder node and the address where writes are forwarded. This field is omitted if no node holds the index, if the holder could not be read, if the holder provided no address, or on nodes using local storage. Data in this field can lag behind a node handover by a few seconds. For more information, see [Indexers](#indexers).

## Lucene compatibility

The `status.luceneCompatibility` field indicates Lucene version compatibility. Lucene supports indexes created by the current major version and the preceding major version:

| Value | Description |
|-------|-------------|
| `CURRENT` | Created by the current major version. Compatible with the current and next Lucene major versions. |
| `ENDING` | Readable by the current version, but unsupported by the next Lucene major version. Reindex before upgrading across major versions. |
| `UNREADABLE` | Too old to open. The index reports the `INCOMPATIBLE` state and requires reindexing. |
| `UNKNOWN` | No version was recorded and no commit exists to determine the version (for example, on an empty index). |

The `status.luceneCreatedMajor` field contains the recorded Lucene major version. This field is omitted when compatibility is `UNKNOWN`.

## Actions

The API provides index action endpoints:

- `commit`: Pushes pending changes (documents and definition) to storage and returns the resulting status.
- `pull`: Fetches the latest remote state immediately instead of waiting for the refresh interval, and returns the resulting status.
- `promote`: Configures the index to serve from the specified generation and returns the updated index resource. The request path must specify a generation name; calling `promote` without a generation returns `index:generation:name_required`. Promoting the target of a `ready` reindex job finishes the job, while promoting before the job is ready is refused with `409 Conflict` (`reindex:target_busy`).

`commit` and `pull` act on the generation specified in the request path (or the live generation if omitted). Nodes automatically discover indexes, generations, and changes at the interval configured by `INDEXES_REFRESH_INTERVAL`.

## Reindex

A reindex job populates a new generation by copying documents from an existing generation of the same index inside the engine, without resending documents from the client. For step-by-step instructions, see [Reindex into a new generation](../how-to/reindex-into-a-new-generation.md).

### Starting a job

To start a reindex job, send a `POST` request to `/v1alpha1/admin/indexes/{target}/actions/reindex`. This request requires the `indexes.reindex` permission.

The `{target}` must meet the following requirements:

- It must specify a generation by name (for example, `products@2`).
- The generation must already exist and must be empty.
- The generation must not be the live generation.
- The source generation must have a primary key and keep document sources (`source` mode not `none`).
- The primary key of the source and target generations must have the same field name and type.

If the target does not meet these requirements, the server returns `400 Bad Request`.

The request body accepts the following optional JSON fields:

- `from`: The generation to read documents from. Defaults to the live generation. Must belong to the same index as the target.
- `promote`: The promotion mode. `auto` (default) automatically promotes the target generation once it catches up with changes. `manual` pauses the job in the `ready` phase and keeps the target caught up until you manually promote it.

A successful request returns `202 Accepted` with the job record. The job runs in the background on the node holding the index.

An index can run at most one reindex job at a time. Starting a second job on an index returns `409 Conflict` with the error code `reindex:in_progress`. A finished job's record remains readable until a new job for that index replaces it.

### Creation parameter

You can create a generation and start a reindex job in one request by adding the `?reindex=auto` or `?reindex=manual` query parameter to a `PUT` request:

```text
PUT /v1alpha1/admin/indexes/products@2?reindex=auto
```

This creates the target generation with the definition in the request body and starts a reindex job reading from the live generation.

The `reindex` query parameter is one-shot and is not stored in the index definition. The server returns `400 Bad Request` if the request does not create a generation, such as on an initial index creation or on a `PUT` request that updates an existing generation's definition.

### Job record and phases

Reindex endpoints return a job record:

```json
{
  "index": "products",
  "target": "products@2",
  "source": "products@1",
  "phase": "copying",
  "promote": "auto",
  "documentsCopied": 125000,
  "sourceDocuments": 2400000,
  "backlog": 4100,
  "error": null,
  "startedAt": "2026-08-28T10:15:30Z",
  "updatedAt": "2026-08-28T10:16:02Z"
}
```

The job record contains the following fields:

- `index`: The name of the index.
- `target`: The generation being populated.
- `source`: The generation providing the source documents.
- `phase`: The current phase of the job.
- `promote`: The configured promote mode (`auto` or `manual`).
- `documentsCopied`: The number of confirmed documents copied to the target.
- `sourceDocuments`: The document count of the source generation when the copy started.
- `backlog`: The number of changed documents waiting to be replayed when the record was last written.
- `error`: The error message if the job failed, or `null`.
- `startedAt`: The timestamp when the job started.
- `updatedAt`: The timestamp when the job record was last updated.

A job progresses through the following phases:

| Phase | Description |
|---|---|
| `pending` | Accepted and waiting for a concurrency slot on the node. |
| `copying` | Streaming documents from the source to the target in primary key order. |
| `replaying` | Copying documents that changed in the source while the copy ran. |
| `ready` | Used only with `promote: manual`. Caught up and waiting for manual promotion, while continuing to catch up periodically. |
| `promoting` | Holding writes for the final drain and promotion. |
| `done` | Completed and promoted successfully. |
| `failed` | Stopped before promotion due to an error. The `error` field indicates the cause. |
| `cancelled` | Stopped before completion in response to a cancellation request. |

### Job status and fleet-wide listing

To check the status of a job on an index, send a `GET` request to `/v1alpha1/admin/indexes/{name}/actions/reindex`. If no job exists for the index, the server returns `404 Not Found` with the error code `reindex:not_found`.

To list every reindex job across the deployment, send a `GET` request to `/v1alpha1/admin/reindexes`.

Both endpoints require the `indexes.read` permission. Status and fleet-wide listings are served from a durable job record, so any node can serve the request and returns the same response.

### Cancelling a job

To stop an in-progress job, send a `POST` request to `/v1alpha1/admin/indexes/{name}/actions/reindex/cancel`. This requires the `indexes.reindex` permission.

Cancelling a job stops the background process and leaves the partially populated target generation in place. You can remove the generation with `DELETE /v1alpha1/admin/indexes/{target}`. Cancelling a finished job changes nothing.

### Target constraints and promotion

Direct document writes to a generation being filled by a reindex job return `409 Conflict` with the error code `reindex:target_busy`. Document writes to the live generation continue unaffected.

When a job configured with `promote: manual` reaches the `ready` phase, calling `POST /v1alpha1/admin/indexes/{target}/actions/promote` finishes the job by draining remaining changes, promoting the generation, and transitioning the job to `done`. Promoting a target generation before the job reaches the `ready` phase is refused with `409 Conflict` and the error code `reindex:target_busy`.

## Indexers

`GET /v1alpha1/admin/indexers` returns the candidate nodes competing to write indexes and the active writer claim for each index:

```json
{
  "candidates": [
    { "node": "node-a-7f21", "address": "http://node-a:8080", "expiresAt": "2026-08-21T10:15:30Z" },
    { "node": "node-b-90c4", "address": "http://node-b:8080", "expiresAt": "2026-08-21T10:15:32Z" }
  ],
  "claims": [
    { "index": "events", "node": "node-b-90c4", "address": "http://node-b:8080", "expiresAt": "2026-08-21T10:15:32Z" },
    { "index": "products", "node": "node-a-7f21", "address": "http://node-a:8080", "expiresAt": "2026-08-21T10:15:30Z" }
  ]
}
```

Any node can serve this endpoint from its local view of shared deployment state, including search-only nodes. The response can lag actual state by a few seconds.

This endpoint requires the `indexes.read` permission. If a credential lacks permissions for an index, that index is omitted from the `claims` list.

Indexes without an active claim are omitted from `claims` until a write operation assigns a writer. In candidate and claim entries:

- `address`: The target address for write forwarding. Omitted if the node did not set `NODE_ADDRESS`.
- `expiresAt`: The timestamp when the entry expires unless renewed by the node.

On nodes using local storage, `candidates` and `claims` are empty. If a node cannot read shared state from storage, it returns `503 Service Unavailable`.

## Status codes

The Admin API returns the following status codes:

| Status code | Condition |
|-------------|-----------|
| `400 Bad Request` | The request body failed validation. The response body details each validation error. See [Errors](errors.md). |
| `401 Unauthorized` | The request lacks valid credentials. See [Authentication](auth.md). |
| `403 Forbidden` | The credential does not have permission for the requested action on this index. |
| `404 Not Found` | The specified index or generation does not exist, a `PUT` request with `If-Match` targeted a non-existent resource, no reindex job exists for the index (`reindex:not_found`), or the index falls outside the credential's allowed patterns. |
| `409 Conflict` | The index cannot be modified because no forwarding node is available, the index is synchronizing, a reindex job is already in progress (`reindex:in_progress`), the target generation is busy being reindexed (`reindex:target_busy`), the definition contains unrepresentable settings, the index requires unsupported engine features, or writing to the registry failed. |
| `412 Precondition Failed` | The `If-Match` version does not match the current definition version. |
| `502 Bad Gateway` | The request was forwarded to the holder node, but the node did not respond. |
| `503 Service Unavailable` | The request conflicted with an index being closed to free resources (retrying reopens the index), or a node querying `/v1alpha1/admin/indexers` could not read the shared storage state. |
