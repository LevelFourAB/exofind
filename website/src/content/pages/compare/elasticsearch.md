# Exofind vs Elasticsearch

Elasticsearch is a stateful cluster that you shard and operate. Exofind puts the index in a bucket and makes the nodes disposable.

[Elasticsearch](https://www.elastic.co/elasticsearch) is a distributed search and analytics engine that stores data across stateful node clusters. Exofind is an experimental search engine that keeps its authoritative indexes in S3-compatible object storage and runs nodes as local caches. The primary difference is how each engine manages state: self-managed Elasticsearch requires cluster coordination and disk replication across nodes, while Exofind provides an object-storage-first architecture on your own infrastructure under the Apache License 2.0. Exofind is experimental and uses a `v1alpha1` API that changes without backward compatibility.

## At a glance

| | Exofind | Elasticsearch |
| --- | --- | --- |
| License | Apache 2.0 | AGPLv3, SSPL 1.0, or Elastic License 2.0 |
| Where the data lives | S3-compatible object storage | Local node disks; object storage in serverless cloud |
| How nodes coordinate | Shared bucket metadata and conditional writes | Master nodes, cluster state gossip, and quorums |
| Scaling | One writer per index; search scales across stateless nodes | Sharding spreads one index across many nodes |
| Language analysis | Configured per value with locale tags | Configured per field in mapping |
| Schema changes | Built-in generations under the same index name | Reindex into a new index and swap an alias |
| Hosting | Self-managed nodes | Self-managed, Elastic Cloud, or cloud providers |

## The main difference

### Object storage as the source of truth

Self-managed Elasticsearch is stateful. It splits indexes into shards and copies them to replica shards on other nodes. You must size disks, place replicas, and run backups to protect your data. Nodes form a cluster with master elections and gossip protocols.

Elastic Cloud Serverless removes this operational burden by making cloud object storage the single source of truth. However, this stateless architecture is exclusive to Elastic Cloud and is not available for self-managed Elasticsearch.

Exofind brings that stateless model to your own infrastructure. The object storage bucket holds the authoritative copy of every index. Exofind nodes do not talk to each other and do not use consensus protocols like Raft. Nodes download index data to local disk as a cache. A node never holds the only copy of committed data, so you replace a node instead of recovering one.

### Sharding and write scaling

Elasticsearch splits a single index into multiple shards across several machines. This design allows a single index to accept more write traffic and store more data than one machine can handle.

Exofind uses single-writer architecture per index. One node writes to an index at a time, while candidate nodes coordinate write ownership through a leadership table in the bucket. Write throughput for one index is limited to the capacity of a single machine. You scale write throughput by distributing different indexes across different nodes.

### Schema changes and index generations

In Elasticsearch, you cannot change the data type or analyzer of an existing field. To apply a breaking schema change, you must define a new index, run the `_reindex` API to copy the data, and update an index alias. You must build and maintain this migration workflow yourself.

Exofind provides schema migrations as a native feature called generations. When a configuration change requires reindexing, Exofind creates a new generation, such as `books@2`. The engine fills the new generation from the old data and promotes it when ready. Clients continue to query the bare index name with the same API keys throughout the migration.

### Language analysis per field versus per value

Elasticsearch attaches analyzers to fields in the index mapping. A field has one analyzer. To index content in multiple languages, you must create separate fields or separate indexes for each language and route queries accordingly.

Exofind attaches the language locale to the individual value rather than the field. A single field can contain a Swedish title and a Japanese title in different documents. Exofind analyzes each value with its own language rules, including stemming, stop words, and compound word splitting.

## What Exofind trades away

Exofind buys its simplicity with real limits. Read these before you compare anything else.

- **Freshness is seconds, not milliseconds.** A search node answers from its last pull of the bucket. `EXOFIND_INDEXES_REFRESH_INTERVAL` defaults to 30 seconds. Elasticsearch replicates operations between nodes and refreshes in about a second.
- **One writer per index.** Write throughput for a single index is the throughput of one node. Elasticsearch shards a single index across many nodes. You spread Exofind writes by putting different indexes on different nodes.
- **Application search only.** Exofind has no aggregations for analytics, no ES|QL, no cross-cluster search, no ingest pipelines, and no Kibana.
- **Early software, one team.** The `v1alpha1` API changes without keeping compatibility, and Level Four AB is the only company behind it. Elasticsearch has fifteen years of production use, clients for most languages, and people who already know it.

## Where Elasticsearch is the better choice

- **You need a single index larger than one machine.** Elasticsearch splits an index across shards on multiple nodes to scale data volume and write throughput.
- **You need an analytics or observability backend.** Elasticsearch provides aggregations, ES|QL, Kibana dashboards, and ingest pipelines for logs and metrics.
- **You need a mature, battle-tested platform.** Elasticsearch has a large ecosystem, official client libraries for most languages, and extensive operational documentation.
- **You want a fully managed SaaS service.** Elastic Cloud and other hosted providers manage infrastructure, updates, and maintenance for you.

## Where Exofind is the better choice

- **You want a stateless, object-storage-first architecture on your own infrastructure.** Exofind stores all primary data in S3 or compatible storage under the Apache 2.0 license.
- **You want minimal operational complexity.** Exofind nodes coordinate through object storage without master nodes, quorums, or cluster networking.
- **You manage multilingual content in the same fields.** Exofind applies language analysis per value across 59 supported locales without requiring separate fields per language.
- **You need automated zero-downtime schema migrations.** Exofind manages index generations and data backfilling internally without custom reindexing scripts.

## Moving from Elasticsearch

- **Matching engine:** Both engines use Apache Lucene for scoring and text matching.
- **Aliases and reindexing:** Elasticsearch aliases and the `_reindex` API correspond to Exofind generations. Exofind automates the backfill and swap process behind the index name.
- **Field analyzers:** Elasticsearch field-level analyzers map to Exofind locale-tagged field values or custom analysis chains.
- **Cluster state:** Elasticsearch cluster management, shard allocations, and replica settings do not exist in Exofind. You configure a bucket and run nodes behind a standard load balancer.
- **Analytics and logs:** Exofind is designed for application search. It does not replace Elasticsearch aggregations, ES|QL, or Kibana log visualization.

## Next steps

- [Run a node and define an index](/tutorials/getting-started/)
- [How Exofind is put together](/explanation/architecture/)
- [Supported locales](/reference/locales/)
- [Define an index](/how-to/define-an-index/)
- [Search an index](/how-to/search-an-index/)
