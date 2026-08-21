# Admin API

Management of indexes, under `/v1alpha1/admin`. Bodies are JSON.

Putting documents into an index is not part of this API - it has its own,
the [documents API](documents-api.md). Managing the keys that reach any of
these is under `/v1alpha1/admin/keys`, in [Authentication](auth.md).

Every request here presents a credential. Which permission each endpoint
needs, and why an index a key was granted nothing on answers `404` rather
than `403`, are in [Authentication](auth.md).

## Endpoints

```
GET    /v1alpha1/admin/indexes                          # indexes the deployment holds
GET    /v1alpha1/admin/indexes/{name}                   # definition and status
PUT    /v1alpha1/admin/indexes/{name}                   # create or replace the definition
DELETE /v1alpha1/admin/indexes/{name}                   # remove an index or a generation
POST   /v1alpha1/admin/indexes/{name}/actions/promote   # answer for this generation
POST   /v1alpha1/admin/indexes/{name}/actions/commit    # push pending changes
POST   /v1alpha1/admin/indexes/{name}/actions/pull      # fetch the latest state now
```

Requests that modify an index - everything here except the reads and `pull` -
run on the node holding the indexer role. Another node forwards the request
there itself - as it arrived, credential included - and answers with what the
indexer answered, so a client needs to do nothing special. When there is no
indexer to forward to, or the indexer set no `NODE_ADDRESS`, the request is
refused with `409 Conflict`; an indexer that does not answer is reported
with `502 Bad Gateway`.

## Names and generations

An index holds *generations*, and the documents and the definition belong to
a generation rather than to the index. One of them is live: the index answers
from it, and that is what a name without a generation means.

`{name}` is therefore either of two things:

| Written | Means |
|---------|-------|
| `products` | the index, answering from whichever generation is live |
| `products@2` | generation `2` of `products`, whether or not it is live |

The `@` is reserved and appears in no name of its own, so a generation is
only ever reachable through the index it belongs to. Which generations exist
and which one is live is registry state shared by the deployment, not
something each node decides.

A definition that the documents already indexed were *not* indexed under - a
field gaining `matching`, a changed analysis chain, an edited synonym set -
cannot be rolled out by changing an index in place: the documents would go on
being indexed the old way, and searches would quietly return less than they
should. Roll it out instead by filling a new generation and promoting it, as
in [Roll out a definition change](../how-to/roll-out-a-definition-change.md).

`DELETE` on an index removes it and every generation of it; on a generation it
removes that one alone, and the generation an index answers from is refused
with `index:generation:is_live` until another is promoted. Either way the index
is taken out of the registry, so it is gone for the whole deployment rather
than only for the answering node - the others remove their copies when they
next read the registry. What the remote holds under it is not removed. Both
answer `204 No Content`.

`promote` makes the index answer from the named generation. It takes effect on
the answering node at once and on every other within `INDEXES_REFRESH_INTERVAL`.
Nothing a caller holds changes, which is also what makes it the way to undo a
rollout: promote the generation that was answering before.

## The index resource

`GET` on an index, and every successful `PUT`, answer with the same shape:

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
    "luceneCompatibility": "CURRENT",
    "luceneCreatedMajor": 10
  },
  "generations": [
    { "name": "1", "live": false, "createdAt": "2026-08-01T09:14:22Z" },
    { "name": "2", "live": true, "createdAt": "2026-08-16T11:02:07Z" }
  ]
}
```

- `generation` is the one being described - the live one when the request
  named the index alone.
- `generations` lists every generation of the index, so reading it says what
  could be promoted as well as what is answering now.
- `version` identifies the definition and is also sent as an `ETag` header.
  Send it back as `If-Match` on `PUT` to fail with a conflict instead of
  overwriting a definition someone changed in the meantime.
- `definition` is the definition in effect - see
  [Field types](field-types.md) for its contents. Presets are stored
  expanded, so a definition reads back as the chain a preset became rather
  than the preset.
- `status` is observed state, reported by the node answering and never
  accepted as input.

A `PUT` that creates something answers `201 Created`; one that updates it
answers `200 OK`. The definition is desired state: it is sent in full and
anything left out is removed. What the request creates depends on the name:

| `PUT` on | Creates | Updates |
|----------|---------|---------|
| `products` | the index, with a first generation named `1` | the definition of the live generation |
| `products@2` | generation `2` of an existing index | the definition of generation `2` |

A generation created this way holds no documents and is not live - the index
goes on answering from the one it had. `PUT products@2` against an index that
does not exist answers `404`: there is nothing to add a generation to, and an
index is created by its own name so that which generation comes first stays
the engine's to decide.

A definition written by a newer version of the API may hold settings this one
has no name for. Reading such an index is refused rather than answered with
the parts that fit, and updating it is refused as
`index:definition:unrepresentable` rather than dropping what was left out -
sending back what was read would otherwise delete settings the caller never
saw. Both are `409 Conflict`, and neither is fixed by changing the request:
use a node running a version that knows the definition.

## Index states

`status.state` says where the index is in its synchronization with the
remote, as seen by the answering node:

| State | Meaning |
|-------|---------|
| `NEEDS_PULL` | A newer remote state exists and has not been pulled yet. |
| `PULLING` | The remote state is being fetched. Becomes `USABLE` when done. |
| `USABLE` | Serving searches. On a read only node this means likely up to date - as current as the last pull. |
| `MODIFIED` | Has local changes not yet pushed. Only the indexer reaches this. |
| `PUSHING` | Local changes are being pushed. Becomes `USABLE` when done. |
| `UNSUPPORTED` | The definition needs something this version of the engine does not have. Written by a newer node; fixed by upgrading this one. |
| `INCOMPATIBLE` | The Lucene files are too old for this build to open. Not fixed by upgrading - reindexing into a new generation is the only way back. |
| `CLOSED` | Closed on this node. Asking for the index anew opens a fresh instance. |

`status.readOnly` is whether this node can modify the index - only the node
holding the indexer role can.

## Lucene compatibility

`status.luceneCompatibility` says how much longer the index can be read.
Lucene opens an index created by the current major version and the one
before it, and an index in storage outlives that window, so the version that
created it is recorded and judged:

| Value | Meaning |
|-------|---------|
| `CURRENT` | Created by the major in use, so it survives the next one too. |
| `ENDING` | Readable now, dropped by the next Lucene major. Reindex it before upgrading the nodes across one. |
| `UNREADABLE` | Too old to open. The index reports state `INCOMPATIBLE` and only reindexing brings the documents back. |
| `UNKNOWN` | Nothing recorded a version and there is no commit to read one from, which is what an empty index looks like. |

`status.luceneCreatedMajor` is the recorded major, absent when it is
`UNKNOWN`.

## Actions

`commit` pushes pending changes - documents and definition - to storage and
answers the resulting status. `pull` fetches the latest remote state right
away rather than waiting for the refresh interval, and answers the resulting
status. Both act on the generation the name resolves to.

`promote` makes the index answer from the named generation, and answers with
the index resource for it. It names a generation: `promote` on an index alone
is refused with `index:generation:name_required`.

Nodes otherwise find indexes, generations and their changes on their own, on
the interval set by `INDEXES_REFRESH_INTERVAL`.

## Status codes

| Status | When |
|--------|------|
| `400 Bad Request` | The body failed validation, carrying every problem found - see [Errors](errors.md). |
| `401 Unauthorized` | No credential this node accepts - see [Authentication](auth.md). |
| `403 Forbidden` | The key was not granted this on this index. |
| `404 Not Found` | No index or generation by that name, including a `PUT` with `If-Match` on one that does not exist, and an index outside every pattern of the key. |
| `409 Conflict` | The index cannot be modified right now - there is no indexer to forward the request to, or the index is synchronizing. Also a definition holding settings this version of the API cannot describe, an index needing engine features this node does not have, and a registry that could not be written. |
| `412 Precondition Failed` | The version in `If-Match` is no longer the one in effect. |
| `502 Bad Gateway` | The request was forwarded to the indexer and it did not answer. |
| `503 Service Unavailable` | The request raced the index being closed to make room on this node. Retrying opens it again. |
