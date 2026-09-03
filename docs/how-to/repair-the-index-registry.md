# Repairing the index registry

This guide shows you how to detect, audit, and repair a missing or corrupt
index registry in an Exofind deployment, and how to use the registry audit to
inspect storage drift. Use this guide when a node reports registry errors, when
an index listing returns empty despite data existing in object storage, or when
checking for unreferenced data in a bucket.

## Prerequisites

Before you begin:

- Ensure that your deployment uses `object` storage mode. Registry audit and
  repair endpoints are unavailable in `local` storage mode.
- Obtain an API key with the deployment-scoped `registry.audit` and
  `registry.repair` permissions, or use the root key (`EXOFIND_AUTH_ROOT_KEY`).
  API keys generated from the `admin` role before these permissions existed do
  not include them.
- Ensure that no index rollouts are in flight before performing a repair.

## Recognize a lost or corrupt registry

The deployment registry is stored in remote object storage at
`registry/indexes.ef.bin` under the configured storage prefix. It records which
indexes exist, their available generations, and which generation serves requests
for the bare index name.

Identify registry problems by checking node logs, readiness checks, and index
listings:

- **Missing registry (`ABSENT`):** The node starts and reports ready, but
  `GET /v1alpha1/admin/indexes` returns an empty list even though index data
  remains under `indexes/<index>/<generation>/` in the bucket.
- **Corrupt registry (`CORRUPT`):** A node that starts after the corruption
  stays running but never becomes ready: `GET /q/health/ready` returns HTTP
  `503` with the `index-registry` check marked `DOWN`. A node that read the
  registry before the corruption stays ready and keeps serving the copy it
  holds. Either way, the node log records the following message:
  ```text
  The stored registry can not be parsed, using the copy this node holds. Repair it through the registry audit endpoint
  ```
  The node continues to serve its in-memory copy of the registry, and admin API
  endpoints remain accessible. Authentication continues to function because API
  keys reside in a separate `keys` storage object.

## Audit the registry and storage

Run an audit to compare the contents of the registry object against the data
stored in your bucket. The audit endpoint is read-only and is handled directly
by the receiving node without forwarding to an indexer.

1. Query the registry audit endpoint:
   ```http
   GET /v1alpha1/admin/registry/audit
   ```
2. Inspect the `registry` field in the response:
   - `PRESENT`: The registry object exists and can be parsed.
   - `ABSENT`: No registry object exists in the storage bucket.
   - `CORRUPT`: The registry object exists but its contents cannot be parsed.
3. Review the `indexes` list in the response. Each index entry provides the
   following fields:
   - `name`: The index name.
   - `registered`: `true` if the index is currently defined in the registry.
   - `live`: The generation answering queries for the bare index name. This
     field is omitted if unregistered or if no live generation is set.
   - `proposedLive`: The highest-numbered generation that a repair with
     `promoteNewest` would set as live.
   - `removedAt`: Present when a delete marked the storage and the sweep has not
     removed it yet. Marked entries are unregistered, not proposed for
     promotion, and a repair skips them unless restored.
   - `generations`: The generations found for the index.
4. Review the `stored` state for each generation:
   - `SYNCED`: The bucket contains a completed `manifest.ef.bin` file. Nodes
     can pull and serve this generation.
   - `INCOMPLETE`: The bucket prefix exists without a manifest. This occurs
     when an initial push was interrupted or when storage remains from a swept
     generation.
   - `MISSING`: The generation is registered in the registry, but no data
     exists in the bucket.
   Each generation also includes `removedAt` when a delete marked the storage
   and the sweep has not removed it yet. Marked generations are unregistered,
   not proposed for promotion, and a repair skips them unless restored.
5. Check the `unusable` list for prefixes in the bucket that do not match valid
   index or generation name formats.

## Inspect storage drift on a healthy deployment

You can run the audit endpoint on a healthy deployment (`registry: PRESENT`) to
detect orphaned or unreferenced data:

- **Unregistered `SYNCED` generations:** A deleted index now shows `removedAt`
  and waits for the sweep. An unregistered generation without `removedAt` is an
  interrupted rollout, or storage deleted before removal marks existed. To
  remove such leftover storage, register it with a repair and then delete it
  through the API, which marks it for the sweep.
- **`INCOMPLETE` generations:** Leftover prefixes from aborted pushes or disk
  sweeps.
- **`MISSING` generations:** Registered generations whose storage data was
  removed from the bucket.

Because object storage cannot determine whether an unregistered generation is
an interrupted rollout or leftover data from a deleted index, evaluate these
entries before manually cleaning storage objects.

## Repair the registry

A repair replaces a `CORRUPT` registry or creates an `ABSENT` registry using
valid data found in the bucket. The repair only registers `SYNCED` generations
that are not already present in the registry. It never deletes indexes,
generations, or bucket data. Existing registered indexes retain their settings,
features, and live generation assignments.

1. Verify that no rollout operations are currently running.
2. Send a repair request to the admin API.

   To register all `SYNCED` generations without setting live generations for
   newly created indexes:
   ```http
   POST /v1alpha1/admin/registry/actions/repair
   ```

   To register all `SYNCED` generations and configure each newly created index
   to answer for its highest-numbered generation:
   ```http
   POST /v1alpha1/admin/registry/actions/repair
   Content-Type: application/json

   {
     "promoteNewest": true
   }
   ```
   **Note:** `promoteNewest` only promotes generations with numeric names.
   Generations with non-numeric names are not promoted automatically.

   To restore marked storage during the repair, provide the `restore` field with
   a list of index or generation names:
   ```http
   POST /v1alpha1/admin/registry/actions/repair
   Content-Type: application/json

   {
     "promoteNewest": true,
     "restore": ["books"]
   }
   ```

3. Inspect the repair response:
   ```json
   {
     "createdIndexes": ["books"],
     "addedGenerations": ["books@1", "books@2"],
     "promoted": ["books@2"],
     "restored": ["books"]
   }
   ```
   The `restored` field lists the names whose removal mark the repair removed.
   If all lists in the response are empty, the registry was already complete.
4. If you did not use `promoteNewest`, or if an index uses non-numeric
   generation names, manually promote the desired generation for each restored
   index:
   ```http
   POST /v1alpha1/admin/indexes/products@1/actions/promote
   ```

## Restore a deleted index or generation

When an index or generation was deleted, you can restore it before the
background sweep removes its storage from the bucket.

1. Run the audit endpoint and find the entry with `removedAt`:
   ```http
   GET /v1alpha1/admin/registry/audit
   ```
2. Send a repair request with `restore` naming the index or generation, and set
   `promoteNewest` to `true` if the index should answer for its highest-numbered
   generation:
   ```http
   POST /v1alpha1/admin/registry/actions/repair
   Content-Type: application/json

   {
     "promoteNewest": true,
     "restore": ["books"]
   }
   ```
3. Promote another generation if needed:
   ```http
   POST /v1alpha1/admin/indexes/books@1/actions/promote
   ```

**Note:** Restoring works only while the mark stands, that is within
`EXOFIND_INDEXES_REMOVAL_GRACE` after the delete. The registry does not remember
which generation was live before the delete. The search settings apply again
once the index is registered.

## Handle errors and unsupported findings

When auditing or repairing the registry, handle errors and unfixable findings as
follows:

- **`409 Conflict` with `index:registry:audit_unavailable`:** The node is
  running with `EXOFIND_STORAGE_MODE=local`. Local storage mode does not support
  audit or repair endpoints.
- **`409 Conflict` with `index:registry:io_error`:** Remote object storage is
  unreachable. Check network connectivity and storage credentials.
- **`409 Conflict` with `index:registry:conflict`:** A concurrent registry
  write occurred during the repair. Retry the request when other operations
  finish.
- **`MISSING` findings:** The repair does not restore missing bucket data.
  Remove the generation from the registry or restore the missing objects from a
  storage backup.
- **`INCOMPLETE` and `unusable` findings:** The repair ignores these prefixes.
  An `INCOMPLETE` generation carrying `removedAt` is an interrupted removal that
  the next sweep pass finishes. Delete other unneeded leftover objects directly
  from your bucket.

## Confirm the registry status

After running a repair, verify that the deployment is serving the repaired
indexes:

1. Check the node readiness endpoint:
   ```http
   GET /q/health/ready
   ```
   Verify that the response returns HTTP status `200` and the `index-registry`
   check reports `"status": "UP"`.
2. Run the audit endpoint to verify registry integrity:
   ```http
   GET /v1alpha1/admin/registry/audit
   ```
   Verify that `"registry": "PRESENT"` and all expected generations report
   `"registered": true`.
3. Query the index listing on the node that handled the repair:
   ```http
   GET /v1alpha1/admin/indexes
   ```
   Verify that all restored indexes are listed.

The node that executed the repair serves the updated registry immediately.
Other nodes pick up the updated registry during their next refresh pass, within
the duration configured by `EXOFIND_INDEXES_REFRESH_INTERVAL` (30 seconds by
default).

## Related

- [Admin API](../reference/admin-api.md) - Endpoints and payload structures for index management.
- [Authentication](../reference/auth.md) - API key permissions, roles, and the root key.
- [Operating a deployment](operate-a-deployment.md) - Monitoring node health, writer assignments, and storage operations.
- [Rolling out a definition change](roll-out-a-definition-change.md) - Creating and switching between index generations safely.
- [Generations](../explanation/generations.md) - How generation numbering, promotion, and immutable storage layouts work.
