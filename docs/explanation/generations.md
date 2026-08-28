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

## What this does not do

Populating a new generation requires indexing documents into it. Because Exofind preserves every document in its original form (see [the source API reference](../reference/documents-api.md)), you do not need to send source documents again when rebuilding from an existing generation.

However, the engine does not automatically execute the rebuild process or manage documents written while a rebuild is in progress.
