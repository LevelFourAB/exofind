# Exofind vs Meilisearch

Meilisearch is the shortest path to a search box, and it clusters only under a commercial license. Exofind runs many nodes over one bucket.

[Meilisearch](https://www.meilisearch.com) is an open source search engine written in Rust that provides fast, typo-tolerant search with minimal setup. Exofind is an experimental search engine written in Java that uses Apache Lucene and stores indexes in S3-compatible object storage. Exofind uses an experimental `v1alpha1` API that changes without keeping compatibility. The core difference is where data lives: Meilisearch relies on a local database directory that you must back up, while Exofind uses object storage as the authoritative source of truth and treats nodes as disposable caches.

## At a glance

| | Exofind | Meilisearch |
| --- | --- | --- |
| License | Apache 2.0 | MIT core, BSL 1.1 for Enterprise Edition |
| Where the data lives | S3-compatible object storage bucket | Local LMDB directory (`./data.ms`) |
| How nodes coordinate | Through the bucket alone, no quorum | Leader-based, in Enterprise Edition |
| Scaling | Multiple search nodes and writer failover included; no index sharding | Single node in MIT core; sharding and replication in Enterprise Edition |
| Language analysis | Explicit per-value locale tags across 59 supported locales | Automatic language detection via Charabia and `whatlang` |
| Schema changes | Explicit index definition; rejects unsafe changes and supports index generations | Schemaless; reindexes in the background when settings change |
| Hosting | Self-hosted only | Self-hosted or managed via Meilisearch Cloud |

## The main difference

### Local LMDB directory versus object storage bucket

Meilisearch stores index data on disk using LMDB, a memory-mapped key-value store. Memory mapping provides in-memory read speeds with ACID disk persistence. The local data directory on that machine is the authoritative copy of the data. You must manage, replicate, and back up this local disk volume.

Exofind keeps the authoritative copy of every index in an S3-compatible object storage bucket. Nodes store local Lucene directories as temporary caches. If a node fails, you can replace it immediately, and the new node downloads the index data from the bucket. Local disks act only as cache budgets that evict unused data safely.

### Open-source clustering versus commercial enterprise features

In the open-source MIT version, Meilisearch runs on a single node. High availability through replication and horizontal scaling through index sharding require Meilisearch Enterprise Edition, which is licensed under the Business Source License 1.1. In Meilisearch Enterprise Edition, one index can be sharded across multiple machines.

Exofind includes multi-node search and writer failover directly in its Apache 2.0 code. Any number of search nodes can read from the shared bucket behind a standard load balancer. Candidate nodes coordinate writer failover through leadership records in the bucket. However, Exofind does not shard a single index across multiple machines; one index must fit on a single node.

### Automatic language detection versus declared locales

Meilisearch uses the Charabia tokenizer and detects the language of each field automatically with the `whatlang` library. This approach requires no initial language configuration. However, automatic detection can misidentify short strings, brand names, or mixed-language text.

Exofind requires the application to declare the locale on the value itself. Exofind provides language-specific rules, stopwords, and stemming across 59 locales, including compound splitting for languages such as German, Dutch, and Swedish, as well as dedicated segmentation for Chinese, Japanese, and Korean. You can also configure custom analysis chains, token filters, and synonym sets.

### Schemaless ingestion versus explicit index definitions

Meilisearch is schemaless. You can send JSON documents without defining fields in advance. You then update index settings to declare which attributes are searchable, filterable, or sortable, which triggers background reindexing tasks.

Exofind requires an explicit index definition before indexing documents. This definition can live in version control. If a definition change would cause existing documents to return incorrect results, Exofind rejects the update with a named error code instead of applying it silently. For large schema updates, Exofind builds a new index generation in the background while the existing generation continues to serve searches.

## What Exofind trades away

Exofind buys its simplicity with real limits. Read these before you compare anything else.

- **Freshness is seconds, not milliseconds.** A search node answers from its last pull of the bucket. `EXOFIND_INDEXES_REFRESH_INTERVAL` defaults to 30 seconds. A single Meilisearch node answers from the index it just wrote.
- **More to write before the first search.** Meilisearch takes documents and works. Exofind wants a definition first, and every usage of a field is opt-in.
- **Nothing above the API.** Exofind has no dashboard, no analytics, and no front-end libraries. Meilisearch has clients and integrations for most frameworks.
- **Early software, one team.** The `v1alpha1` API changes without keeping compatibility, and Level Four AB is the only company behind it.

## Where Meilisearch is the better choice

- You want the fastest setup from nothing to a running search interface with minimal configuration.
- You want a fully managed service through Meilisearch Cloud without managing infrastructure.
- You have an index that exceeds the storage capacity of a single machine and requires sharding across multiple instances.
- You prefer automatic language detection without tagging the language of every document or field.
- You prefer the permissive MIT license for single-node deployments.

## Where Exofind is the better choice

- You need multi-node read scaling and automatic writer failover in an open-source deployment without commercial licensing fees.
- You want object storage as the single source of truth so you can treat search nodes as stateless, disposable caches.
- You need exact text analysis with per-value locale tagging, compound word splitting, or custom tokenization pipelines.
- You want declarative, version-controlled index schemas that prevent breaking changes.
- You need zero-downtime index rebuilds and migrations through index generations that populate data from prior generations.

## Moving from Meilisearch

- **Storage**: Replace local volume backups with an S3-compatible bucket on Amazon S3, Google Cloud Storage, MinIO, or SeaweedFS.
- **Documents and Locales**: Add locale tags to text values when sending documents to Exofind instead of relying on automatic detection.
- **Schema**: Convert dynamic index settings into an explicit Exofind index definition before sending documents.
- **Search and Filtering**: Map filterable and sortable attributes to explicit field definitions with assigned roles in Exofind.

## Next steps

- [Run a node and define an index](/tutorials/getting-started/)
- [Define an index](/how-to/define-an-index/)
- [How Exofind is put together](/explanation/architecture/)
