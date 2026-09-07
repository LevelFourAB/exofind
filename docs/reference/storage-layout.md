# Storage layout

Everything a deployment in `object` mode writes lives under the prefix set by
`EXOFIND_STORAGE_REMOTE_PREFIX` in the bucket set by
`EXOFIND_STORAGE_REMOTE_BUCKET`. When no prefix is set, the objects sit at the
root of the bucket. Every `.ef.bin` object is a Protocol Buffers message. For
configuration parameters, see
[Configuration](configuration.md). For storage operations and
consistency requirements, see
[Object storage requirements](object-storage.md).

## Objects beside the indexes

The following objects and prefixes sit directly under the configured root
prefix in `object` mode:

| Key | Written by | Description |
| --- | --- | --- |
| `registry/indexes.ef.bin` | Any node that creates, promotes, or deletes an index or generation | The index registry: every index, its generations, which generation serves the bare name, and version hints. Single object, replaced conditionally on its entity tag. Nodes poll it on an interval with conditional (`If-None-Match`) reads. |
| `indexer-leadership.ef.bin` | Indexer candidates | The leadership table: one claim per index (node, address, expiry, load bucket, offered flag, taker) and one entry per candidate (node, address, expiry). Single object, replaced conditionally on its entity tag. |
| `auth/keys.ef.bin` | Nodes serving the keys API | Every API key of the deployment. Single object, replaced conditionally on its entity tag. The root key (`EXOFIND_AUTH_ROOT_KEY`) is never stored. |
| `jobs/reindex/records/<index>` | The node writing the index | One durable reindex job record per index name, retained after the job ends. |
| `jobs/reindex/active/<index>` | The node writing the index | One empty marker per reindex job that has not finished, named like its record. A candidate looking for jobs to resume lists this prefix and removes markers for finished records. |
| `.conditional-write-probe` | A node started as an indexer candidate | Scratch object written at startup to verify that the storage backend enforces conditional writes. The node deletes this object when the check finishes. |

`registry/indexes.ef.bin`, `indexer-leadership.ef.bin`, and `auth/keys.ef.bin`
are single objects replaced conditionally on their entity tag.

None of these objects live under `indexes/`, so background cleanup sweeps that
remove unreferenced index files do not touch them.

## Objects of one index

Every index lives under `indexes/<index>/`. Listing `indexes/` with `/` as the
delimiter reports one entry per index. Listing `indexes/<index>/` with `/` as
the delimiter reports one entry per generation.

| Key | Description |
| --- | --- |
| `indexes/<index>/settings.ef.bin` | The search settings of the index name. Replaced conditionally on its entity tag. Belongs to the index name rather than to a single generation. |
| `indexes/<index>/removed.ef.bin` | The removal mark of the whole index, holding `removed_at` in milliseconds since the epoch. Written when the index is deleted. |
| `indexes/<index>/<generation>/` | The prefix containing all files and state of one generation. |

## Objects of one generation

A generation lives under the prefix `indexes/<index>/<generation>/`:

| Key | Description |
| --- | --- |
| `manifest.ef.bin` | The generation manifest: the latest Lucene segment generation, the list of files (name, size, checksum, key), a version number, the epoch of the writer session that wrote it, and the Lucene versions that created and last wrote the index. Replaced conditionally on its entity tag by the index writer. |
| `removed.ef.bin` | The removal mark of this generation, holding `removed_at` in milliseconds since the epoch. Written when the generation is deleted. |
| `e<epoch>/<file>` | A file listed in the manifest, uploaded during the writer session with that epoch. Holds Lucene segment files (`.cfs`, `.si`, and related index files) and `definition.ef.bin` (the index definition). |
| `<file>` | A file written under an older layout before storage keys were recorded in the manifest. The manifest references these files by their bare names. |

The node adheres to the following rules when managing generation files:

- **Key reuse across epochs:** A file whose content has not changed since the
  previous manifest keeps its existing key. A generation updated across
  multiple writer sessions contains files spread across multiple `e<epoch>/`
  prefixes.
- **Push order:** A push uploads every new file first, writes the manifest
  conditionally, and then deletes the files referenced by the previous manifest
  that are absent from the new manifest.
- **Epoch isolation:** The epoch is assigned when a node claims the index
  writer role. Two writer sessions never write to the same key because each
  session uploads under its own epoch prefix.

For more details on synchronization mechanisms, see
[Synchronization](../explanation/synchronization.md).

## What a delete removes

Deleting an index or generation marks its storage and cleans up objects through
a background sweep:

- **Index deletion:** Deleting an index writes `indexes/<index>/removed.ef.bin`
  and removes the index from the registry. A background sweep on indexer
  candidates removes the entire `indexes/<index>/` prefix (including search
  settings and all generations) once the removal mark is older than
  `EXOFIND_INDEXES_REMOVAL_GRACE` (default `1h`). The sweep runs every
  `EXOFIND_INDEXES_REMOVAL_SWEEP_INTERVAL` (default `10m`).
- **Generation deletion:** Deleting a generation writes
  `indexes/<index>/<generation>/removed.ef.bin`. The background sweep removes
  that generation's prefix once the grace period expires.
- **Restoration:** Within the grace period, running a registry repair with
  `restore` removes the removal mark and restores the index or generation. See
  [Repair the index registry](../how-to/repair-the-index-registry.md).
- **Recreation:** Creating an index or generation under a marked prefix clears
  the existing prefix before writing new data, starting the new instance
  empty.

## The local directory

`EXOFIND_STORAGE_LOCAL_DIRECTORY` defines the local filesystem directory used by
the node.

The following table lists paths present in the local directory:

| Path | Storage mode | Description |
| --- | --- | --- |
| `node.lock` | `local` and `object` | File lock held while the process runs. A second node pointed at the same directory refuses to start. |
| `indexes/<index>@<generation>/` | `local` and `object` | Local copy of one generation. Holds Lucene files, `definition.ef.bin`, `manifest.ef.bin` (manifest of the local state), `manifest.ef.bin.tmp` (temporary manifest during writes), `usage.ef.bin` (timestamp of last use, read by the disk sweep), and Lucene `*.lock` files. |
| `registry.ef.bin` | `local` only | The index registry. |
| `keys.ef.bin` | `local` only | API keys. |
| `settings/<index>.ef.bin` | `local` only | Search settings, stored as one file per index name. |
| `jobs/reindex/records/<index>` | `local` only | Durable reindex job records. |

In `object` mode, the local directory stores cached copies of remote data.
`EXOFIND_INDEXES_DISK_MAX_SIZE` allows the node to evict the least recently used
closed copies. A local copy is never evicted if remote storage does not contain
all of its data.

In `local` mode, the directory holds the only copy of all indexes, settings,
and keys. No automatic eviction runs, and losing the local directory causes total
data loss. For maintenance procedures, see
[Operate a deployment](../how-to/operate-a-deployment.md).
