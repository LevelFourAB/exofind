# Glossary

## Bucket

The remote S3-compatible object storage container where authoritative index
data and deployment state are stored in object mode. See
[Architecture](../explanation/architecture.md) and
[Storage layout](storage-layout.md).

## Candidate

A node started with the indexer property enabled (`EXOFIND_INDEXER_ENABLED=true`)
that competes to write indexes. Candidate nodes divide indexes among themselves
through the leadership table. See
[Separating search and indexing nodes](../explanation/deployment-shapes.md).

## Claim

An entry in the leadership table that assigns write responsibility for an index
to a specific holder node with an expiration timestamp. A candidate node must
periodically renew its claims before their lease expires. See
[Synchronization](../explanation/synchronization.md).

## Commit

An action that flushes Lucene changes and writes pending documents and
definition updates to storage as a manifest push. See
[Admin API](admin-api.md).

## Definition

The schema configuration of an index or generation specifying its fields, data
types, field usages, analyzers, and ranking rules. See
[Field types](field-types.md).

## Definition version

An identifier for an index definition returned in the `version` field and the
`ETag` header. It is passed in `If-Match` headers on `PUT` requests to prevent
overwriting concurrent updates. See [Admin API](admin-api.md).

## Deployment

The nodes that share one bucket and prefix in object mode, or the single node
that owns a directory in local mode. Keys and the registry belong to the
deployment. See [Architecture](../explanation/architecture.md).

## Epoch

A writer session identifier claimed by an indexer before uploading files. Epoch
scoping ensures that files are uploaded under `e<epoch>/` so concurrent writer
sessions never write to the same storage keys. See
[Synchronization](../explanation/synchronization.md).

## Facet

A field usage that enables value count aggregations or range buckets over
matching search results. See [Field types](field-types.md).

## Generation

A complete physical version of an index containing its own Lucene files,
manifest, definition, and documents, formatted as `index@generation`. An index
holds one or more generations, with one designated as live. See
[Generations](../explanation/generations.md).

## Grant

An element of an API key combining a set of permissions or a role with a set of
index patterns. Grants are evaluated as a union to authorize API requests. See
[Authentication](auth.md).

## Hint

A version a writer reports in the registry after a push or a settings change so
other nodes learn a generation or its settings moved sooner. A hint is advisory,
and a node still verifies its copies against the manifests on an interval. See
[Synchronization](../explanation/synchronization.md).

## Index

A named collection of searchable documents structured as a name with one or
more generations beneath it. The bare index name routes queries to the current
live generation. See [Generations](../explanation/generations.md).

## Index pattern

An exact index name or a prefix ending with `*` specified in an API key grant.
Patterns restrict which indexes a grant permits an API key to access. See
[Authentication](auth.md).

## Indexer

A candidate node holding an active claim to write to an index. An indexer runs
an active Lucene writer and serves write requests for that index. See
[Separating search and indexing nodes](../explanation/deployment-shapes.md).

## Key

In authentication, an API credential consisting of grants and an optional
expiration that authorizes API requests. In storage, an object path in the
bucket. In object fields, a child field property marking the unique identifier
of an object value in an array. See [Authentication](auth.md) and
[Field types](field-types.md).

## Leadership table

A shared storage object (`indexer-leadership.ef.bin`) that tracks node
liveness, candidate nodes, and active index writer claims. See
[Synchronization](../explanation/synchronization.md).

## Live generation

The designated generation of an index that currently serves search queries and
receives writes addressed to the bare index name. See
[Generations](../explanation/generations.md).

## Local copy

The cached Lucene directory, manifest, and definition files stored on a node's
local disk under `indexes/<index>@<generation>/`. See
[Architecture](../explanation/architecture.md) and
[Storage layout](storage-layout.md).

## Local mode

A single-node storage configuration (`EXOFIND_STORAGE_MODE=local`) where all
index data, registries, keys, and settings reside on the local disk without an
object storage bucket. See [Architecture](../explanation/architecture.md).

## Manifest

The storage object (`manifest.ef.bin`) that lists the Lucene files of an index
generation along with their sizes, checksums, keys, version number, and writer
epoch. See [Synchronization](../explanation/synchronization.md) and
[Storage layout](storage-layout.md).

## Node

An individual running Exofind instance that serves search requests locally and
can optionally act as an indexer candidate. Nodes coordinate entirely through
shared storage. See [Architecture](../explanation/architecture.md).

## Object mode

The storage configuration (`EXOFIND_STORAGE_MODE=object`) where authoritative
index data, registries, keys, and leadership state reside in an S3-compatible
bucket, and nodes use local disks as ephemeral caches. See
[Architecture](../explanation/architecture.md).

## Permission

An immutable capability assigned to API keys inside grants that authorizes a
specific operation, such as `search`, `documents.write`, or `registry.repair`.
See [Authentication](auth.md).

## Prefix

In storage, the path hierarchy under which an index, generation, or deployment
objects are organized. In index patterns, a name prefix ending with `*` matching
multiple indexes. See [Authentication](auth.md) and
[Storage layout](storage-layout.md).

## Primary key

A unique document identifier field marked with `primaryKey: true`. Documents
with matching primary keys overwrite existing documents, and change tracking
during reindexing records changes by primary key. See
[Field types](field-types.md).

## Promotion

The action of switching an index to serve queries from a specified generation
using a conditional write to the index registry. See
[Generations](../explanation/generations.md).

## Pull

An operation where a node downloads new or updated index files and manifests
from object storage to its local disk. See
[Architecture](../explanation/architecture.md).

## Push

An operation where a writer node uploads new index files to storage,
conditionally updates the remote manifest, and deletes unreferenced obsolete
objects. See [Synchronization](../explanation/synchronization.md).

## Refresh interval

The configured interval at which nodes re-read shared storage objects to
discover changes to indexes or search settings. See
[Synchronization](../explanation/synchronization.md).

## Registry

A single storage object (`registry/indexes.ef.bin` or `registry.ef.bin`) that
records which indexes exist, which generations each index contains, which
generation is live, and version hints. See
[Generations](../explanation/generations.md).

## Reindex job

A background task running on the index writer node that populates a new
generation by copying documents from an existing generation and replaying
logged changes. See [Admin API](admin-api.md).

## Removal mark

A storage object (`removed.ef.bin`) written under an index or generation prefix
upon deletion, staging the prefix for removal by a background sweep after a
grace period. See [Generations](../explanation/generations.md) and
[Storage layout](storage-layout.md).

## Root key

A per-node credential configured through `EXOFIND_AUTH_ROOT_KEY` with full
administrative permissions. It is never stored in key storage and cannot be
listed or revoked through the API. See [Authentication](auth.md).

## Scope

The evaluation boundary of a permission, which is either index-scoped
(restricted by index patterns) or deployment-scoped (applying across the entire
deployment). See [Authentication](auth.md).

## Search settings

Per-index configuration stored under `indexes/<index>/settings.ef.bin` that
controls query-time behavior, including ranking overrides, synonyms, typo
exclusions, and field interpretation. Search settings attach to the index name
rather than a generation. See [Admin API](admin-api.md) and
[Storage layout](storage-layout.md).

## Sub-document

An isolated document instance created for each object in an array when an
`object` field is configured with `"mode": "nested"`. Sub-documents can be
searched with nested queries and returned as individual hits. See
[Field types](field-types.md).

## Writer

The single candidate node currently holding the claim to modify an index,
accumulate local changes, and push updates to storage. See
[Architecture](../explanation/architecture.md).
