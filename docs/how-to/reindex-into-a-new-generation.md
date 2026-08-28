# Reindexing into a new generation

This guide shows you how to roll out an index definition change by having the engine reindex existing documents into a new generation.

Use this engine-driven procedure when your documents are already indexed and the source generation retains document copies (`source` mode set to a value other than `none`). The engine copies documents and replays changes internally without requiring you to stream documents back to Exofind. If your source index does not keep stored sources, or if you want to stream documents from an external system yourself, use the manual procedure in [Rolling out a definition change](roll-out-a-definition-change.md). For conceptual background, see [Generations](../explanation/generations.md).

## Prerequisites

Before you begin, verify the following:

- Your API key has the `indexes.reindex` permission (included in the `admin` role) and permissions for `products@*`. Checking job status requires `indexes.read`.
- The source generation has a primary key and keeps document sources (`source` mode is not `none`).
- The new generation definition uses the same primary key field name and type as the source generation.

## Starting the reindex job

You can create the target generation and start the reindex job in one request by using the `?reindex=` query parameter, or you can create the generation first and start the job in a separate step.

Choose a promotion strategy:

- `auto` (default): The engine streams documents, replays incoming writes, and promotes the new generation as soon as it catches up.
- `manual`: The engine fills the generation and pauses in the `ready` phase while keeping it up to date. You can compare search results before manually promoting.

### Option A: Create and start in one request

To create `products@2` and immediately start reindexing from the live generation, add `?reindex=auto` or `?reindex=manual` to your `PUT` request:

```http
PUT /v1alpha1/admin/indexes/products@2?reindex=manual
Content-Type: application/json

{
  "fields": {
    "id":    { "type": "string", "primaryKey": true, "required": true },
    "title": { "type": "string", "matching": { "typoTolerance": {} } },
    "brand": { "type": "string", "filter": {}, "facet": {} }
  }
}
```

### Option B: Create the generation and start the job separately

1. Create the empty generation:

   ```http
   PUT /v1alpha1/admin/indexes/products@2
   Content-Type: application/json

   {
     "fields": {
       "id":    { "type": "string", "primaryKey": true, "required": true },
       "title": { "type": "string", "matching": { "typoTolerance": {} } },
       "brand": { "type": "string", "filter": {}, "facet": {} }
     }
   }
   ```

2. Start the reindex job on the empty generation:

   ```http
   POST /v1alpha1/admin/indexes/products@2/actions/reindex
   Content-Type: application/json

   {
     "from": "products@1",
     "promote": "manual"
   }
   ```

   The endpoint returns `202 Accepted` with the initial job record. If you omit the request body, the job defaults to reading from the live generation with `promote: "auto"`.

## Tracking reindex progress

1. Check the status of the reindex job:

   ```http
   GET /v1alpha1/admin/indexes/products/actions/reindex
   ```

   The response displays the current phase and progress counts:

   ```json
   {
     "index": "products",
     "target": "products@2",
     "source": "products@1",
     "phase": "copying",
     "promote": "manual",
     "documentsCopied": 125000,
     "sourceDocuments": 2400000,
     "backlog": 4100,
     "error": null,
     "startedAt": "2026-08-28T10:15:30Z",
     "updatedAt": "2026-08-28T10:16:02Z"
   }
   ```

2. Monitor the `phase` field as the job progresses through its lifecycle:

   | Phase | Meaning |
   | --- | --- |
   | `pending` | Accepted and waiting for an available concurrency slot on the node. |
   | `copying` | Streaming documents from the source generation in primary key order. |
   | `replaying` | Applying documents modified in the source while the initial copy ran. |
   | `ready` | Caught up and waiting for promotion (`manual` mode only). The job runs periodic catch-up sweeps. |
   | `promoting` | Pausing incoming writes briefly for the final catch-up sweep and promotion. |
   | `done` | Successfully promoted and active. |
   | `failed` | Halted due to an error before promotion occurred. |
   | `cancelled` | Stopped by a cancellation request. |

To view every reindex job across your deployment, send a `GET` request to `/v1alpha1/admin/reindexes`.

## Comparing results and promoting (manual mode)

If you started the job with `promote: "manual"`, complete the following steps once the job reaches the `ready` phase:

1. Search both the target generation and the live index to compare query results:

   ```http
   POST /v1alpha1/indexes/products@2/search
   POST /v1alpha1/indexes/products/search
   ```

2. Promote the target generation when you are ready to make it live:

   ```http
   POST /v1alpha1/admin/indexes/products@2/actions/promote
   ```

   The promote endpoint drains any remaining change backlog, executes the promotion, and transitions the job to `done`. If you attempt to promote before the job reaches `ready`, the request returns `409 Conflict` with error code `reindex:target_busy`.

## Confirming the rollout

To confirm that the new generation is serving live traffic, search the index by name:

```http
POST /v1alpha1/indexes/products/search
```

The node that processed the promotion answers immediately from `products@2`. Other nodes answer from `products@2` within `INDEXES_REFRESH_INTERVAL`.

## Cancelling a job

To cancel an active reindex job:

1. Send a cancellation request:

   ```http
   POST /v1alpha1/admin/indexes/products/actions/reindex/cancel
   ```

2. Delete the target generation to clean up partially copied data:

   ```http
   DELETE /v1alpha1/admin/indexes/products@2
   ```

## Handling a failed job

If a document violates the target schema (for example, missing a required field or containing an incompatible type), the job halts in the `failed` phase before any promotion takes place:

1. Check the job status to identify the failure cause and document key:

   ```http
   GET /v1alpha1/admin/indexes/products/actions/reindex
   ```

   Inspect the `error` field in the response:

   ```json
   {
     "index": "products",
     "target": "products@2",
     "source": "products@1",
     "phase": "failed",
     "error": "The target refused the document with key `prod_12345`: Required field `sku` is missing",
     "updatedAt": "2026-08-28T10:18:12Z"
   }
   ```

2. Delete the failed target generation:

   ```http
   DELETE /v1alpha1/admin/indexes/products@2
   ```

3. Fix the definition or correct the document data in the live generation before starting a new reindex job.

## Deleting the previous generation

After confirming that the new generation works as expected, delete the old generation:

```http
DELETE /v1alpha1/admin/indexes/products@1
```

**Note:** You cannot delete the generation that an index currently answers from. Unremoved generations continue to consume storage and local disk space.
