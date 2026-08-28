# Generations

This document explains why Exofind uses index generations to manage schema and definition changes, how generations work with the registry and storage, and why index names remain decoupled from physical versions.

An index in Exofind is a name with one or more generations beneath it. The documents and the index definition belong to a specific generation. One generation is live, and the index name routes search requests to that live generation. For example, `products` is the index, and `products@2` is a generation of that index.

Generations exist because an index definition and the documents indexed under it cannot be changed separately:

- Enabling `matching` for a field writes a Lucene field that does not exist in previously indexed documents.
- The analysis chain that tokenizes a field value is fixed when the document is indexed. A modified analysis chain, an updated synonym set, or a new locale does not affect existing documents in the index.

Applying these changes in place causes searches to return fewer results than expected without raising an error. This failure mode leaves no error to catch, nothing to report, and no way for a caller to distinguish incomplete results from correct results.

Because in-place modifications are unsafe, the engine does not support them. To apply a definition change to indexed documents, you populate a new generation and promote it. Promotion requires a single conditional write to one object.

## Why the name is what callers hold

The alternative approach—creating `products_v3` alongside `products` and requiring every caller to switch—moves the problem rather than solving it. Every application configuration, stored query, and access key that references the index must change, often at different times.

Access keys present the most difficult scenario. Because keys grant permissions over index patterns, a deployment that rebuilds indexes under new names must choose between two approaches:

- Grant permissions using a wildcard such as `products*`. This allows applications to access and search retired generations, which contain stale data by definition.
- Rewrite key grants on every rollout. In a deployment with one index per tenant, this requires rewriting thousands of grants inside a single object for an operation that should remain transparent.

With generations, the index name never changes. A key granted access to `products` follows the index across rollouts and cannot address a generation directly. A generation is formatted as `products@2`, which does not match patterns for `products`.

The key used to run the rollout is granted `products@*`. This pattern reaches the generations of `products` without matching any other index. Because `@` cannot appear in an index name, the prefix cannot extend beyond the specified index. Neither key requires changes during a rollout.

## Why the separator is reserved

Making the relationship structural rather than conventional provides these guarantees. Because `@` cannot appear in an index name or a generation name, every full identifier decomposes into exactly one index and one generation. A generation is reachable only through the index to which it belongs. An index name can never serve data from another index, regardless of what the registry contains. This isolation is structural rather than dependent on runtime checks.

The alternative is a free mapping of names to indexes. That approach requires authorization checks to prevent callers from pointing their index names at another tenant's index, and those checks must succeed consistently.

## The registry

The registry is a single storage object that records which indexes exist, which generations each index contains, and which generation is currently live. Nodes update the registry using conditional writes and reread it at configured refresh intervals.

This architecture provides two key properties:

- **Index creation is deterministic:** Creating an index is a race that exactly one node wins. Index existence is determined by a single conditional write rather than by each node's view of a directory listing.
- **Discovery requires one request:** A node reads the registry using an `If-None-Match` header. If the registry has not changed, storage returns an empty response. In contrast, discovering state by listing storage requires requests that grow with the number of indexes. That request volume doubles during rollouts when old generations are retained alongside new ones.

A single registry object also makes index deletion definitive. Paginated listings can be incomplete without indicating missing entries, which requires nodes to observe an absence multiple times before deleting local copies. Because a node reads the entire registry at a specific version, it deletes its local copy the first time an index is absent from the registry. A node that fails to read the registry deletes nothing.

The trade-off is that the registry is load-bearing. If a deployment loses its registry object, it loses track of its indexes, even though the underlying index data remains in storage under `indexes/<index>/<generation>/`. The key store uses the same single-object pattern for the same reasons.

## What a generation is in storage

A generation is a complete index with its own Lucene files, manifest, and epochs, stored under the prefix of its parent index. Nesting generations under the index prefix provides two benefits:

- Listing items under `indexes/` with a delimiter returns one entry per index, regardless of how many generations exist under that index.
- Deleting an index requires removing only a single prefix.

All mechanisms described in [Synchronization](synchronization.md) apply to generations without modification. Two generations of the same index are as independent as two separate indexes. Nodes push and pull them independently, and epoch-scoped keys prevent concurrent writer sessions from overwriting each other within each generation.

## Writes while filling a new generation

Populating a new generation takes time. For a large catalogue, the process can take hours. Writes continue during this time, but they flow only to the live generation.

To prevent documents written during a rebuild from disappearing at promotion, the index tracks changes in a change log. Tracking records which documents change:

- Adding or replacing a document records its primary key.
- A partial update records the primary key of the modified document.
- Removing a document by key records that key.
- Removing documents by query resolves the query to primary keys before deletion and records those keys.

The change log is an unordered set of keys. It identifies which documents might differ from the state when tracking began. A process catches up the new generation by reading each recorded document from the live generation in its current state. If the document exists, the process writes it to the new generation; if it was deleted, the process removes it. This replay is idempotent and order-insensitive.

Replay occurs in rounds using log snapshots. Each round replays documents modified since the previous snapshot, making each subsequent round smaller. The rebuild finishes under a brief write hold:

1. New writes pause at a gate while in-flight writes finish. Searches, commits, and pushes continue normally.
2. The final tail of the change log replays to the new generation.
3. The new generation is promoted using a conditional registry write.
4. The write hold releases.

Writes experience brief latency during the final hold—typically a few seconds—without rejected requests or new error states. Because tracking continues after promotion until it is ended, any write that completes against the old generation during the handoff is captured and replayed to the new live generation in a final sweep.

The change log is stored alongside Lucene files in `changes.ef.bin`. It commits and pushes with index manifests, allowing tracking to survive indexer failover.

### Tracking requirements

Change tracking requires two index capabilities:

- **A primary key:** Changes are recorded by document key.
- **Kept sources:** Removing documents by query requires reading stored copies to resolve keys, and replay requires reading source documents from the live generation. Kept sources are enabled by default.

If an index lacks either capability, change tracking is unavailable. Rebuilding such an index requires pausing writes for the entire copy duration.

### Rejected alternatives

Two alternative designs were rejected:

- **A read-only window for the entire rebuild:** This approach pauses all indexing for hours on large datasets. It remains only as a fallback when an index lacks a primary key or kept sources.
- **Dual-write to both generations:** Directing writes to both generations simultaneously fails because write operations behave differently across generations:
  - Partial updates require a document to exist first, but in the generation being filled the document usually does not exist yet.
  - Query-based deletions use the analysis chain of each generation. Because generations often exist specifically to change analysis rules, the same query deletes different documents in each generation.
  - Concurrent writes race against the process copying older versions of the same documents into the new generation.

Dual-write would also require changing the registry format to list multiple writable generations. That change would require the registry required-features mechanism, causing older nodes in a mixed fleet to reject the index outright.

### Registry compatibility

The change tracking design requires no changes to the registry format. A deployment undergoing a rebuild appears in the registry identical to a deployment where an operator manually created an extra generation. Because the registry format is unchanged, the required-features list remains empty. Older nodes in a mixed fleet process the index without modification, and nodes that do not support change tracking treat the log file as inert bytes.

## What this does not do

Populating a new generation requires indexing documents into it. Because Exofind preserves every document in its original form (see [the source API reference](../reference/documents-api.md)), you do not need to send source documents again when rebuilding from an existing generation.

However, the engine does not automatically execute the rebuild process. Filling the new generation, replaying the change log, and promoting the generation are driven externally through the documents API and the registry.
