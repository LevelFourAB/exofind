# Exofind vs Manticore Search

Manticore Search is a SQL-first search database that you back up to a bucket. Exofind is an HTTP JSON engine whose index lives in one.

[Manticore Search](https://manticoresearch.com) is a mature, SQL-first search database with a twenty-year lineage that started with Sphinx in 2001. Exofind is an experimental search engine that stores indexes directly in S3-compatible object storage, and its `v1alpha1` API changes without backward compatibility. The central difference is where data lives and how you query it. Manticore Search is a stateful database that speaks the MySQL protocol and uses object storage as a backup target. Exofind treats object storage as the index itself, serves queries through an HTTP JSON API, and treats local disk as a disposable cache.

## At a glance

| | Exofind | Manticore Search |
| --- | --- | --- |
| License | Apache 2.0 | GPLv3 or later (Columnar Library is Apache 2.0) |
| Where the data lives | Object storage is authoritative; local disk is a cache | Local disk (row-wise, columnar, docstore); bucket is for backups |
| How nodes coordinate | No node-to-node communication; bucket coordinates nodes | Synchronous multi-master replication with Galera |
| Scaling | One writer per index; add search nodes behind a load balancer | Multi-master replication; columnar storage past the RAM of one node |
| Query interface | HTTP JSON API with OpenAPI document | SQL over MySQL protocol, HTTP JSON, and Elasticsearch write syntax |
| Language analysis | Per-value locale tags across 59 locales | Per-table morphology (stemming, lemmatizers, stop words) |
| Schema changes | Zero-downtime index generations with automatic reindexing | Table alter commands and real-time table schemas |
| Hosting | Self-hosted container image | Self-hosted database packages and containers |

## The main differences

### Authoritative bucket versus backup destination

Manticore Search stores data on the local disk of the machine. It offers row-wise storage for fast queries in memory, docstore storage for key-value retrieval, and columnar storage through the Manticore Columnar Library for datasets that exceed available RAM. You back up tables to S3-compatible storage with `manticore-backup` or `SQL BACKUP`, and you restore from that backup when a node fails.

Exofind keeps the authoritative copy of every index in an S3-compatible bucket. A node downloads Lucene index files to its local disk to act as a read cache. If a node fails, you start a replacement container that reads the index from the bucket without a manual restore process. Local disk usage is a cache budget that you configure with `exofind.indexes.disk.max-size`.

### SQL protocol versus HTTP JSON API

Manticore Search is SQL-first and implements the MySQL wire protocol. You connect to it with standard MySQL clients, command-line tools, and database drivers. It provides secondary indexing, a cost-based query optimizer, and parallel execution for complex queries. It also provides an HTTP JSON protocol and Elasticsearch-compatible write endpoints.

Exofind provides an HTTP JSON API described by an OpenAPI document. You define indexes by sending JSON documents in `PUT` requests, and you search through JSON POST requests. Exofind does not provide a SQL interface, a MySQL wire protocol, or relational database features.

### Synchronous Galera replication versus independent cache nodes

Manticore Search clusters nodes with the Galera library. Galera provides virtually synchronous multi-master replication. All nodes coordinate writes and agree on transactions, which provides strong consistency and immediate query visibility across the cluster.

Exofind nodes never communicate with each other. A leadership table in the storage bucket assigns index write permissions to one node at a time. Other nodes poll the bucket for updates at a configurable interval (`EXOFIND_INDEXES_REFRESH_INTERVAL`, which defaults to 30 seconds). Search nodes stay seconds behind the writer, but you scale search traffic by adding stateless reader nodes behind a standard HTTP load balancer.

### Value-level locales versus table-level morphology

Manticore Search configures text morphology at the table level. You define stemming rules, lemmatizers, stop words, and wordforms for a table. All text fields in that table share those morphological settings.

Exofind attaches the locale to the individual value rather than the field or the table. A single document field can store Swedish text in one record and Japanese text in another record. Exofind applies language rules for 59 locales, including compound splitting for Germanic languages and segmentation for Chinese, Japanese, and Korean.

## What Exofind trades away

Exofind buys its simplicity with real limits. Read these before you compare anything else.

- **Freshness is seconds, not milliseconds.** Galera confirms a write across the cluster before it returns. An Exofind search node answers from its last pull of the bucket, which defaults to 30 seconds behind.
- **One writer per index.** Write throughput for a single index is the throughput of one node. You spread Exofind writes by putting different indexes on different nodes.
- **No SQL and no columnar storage.** Exofind has no wire protocol a database driver speaks, and no store built for a dataset far larger than the disk of one node.
- **Early software, one team.** The `v1alpha1` API changes without keeping compatibility. Manticore Search has twenty years of lineage behind it.

## Where Manticore Search is the better choice

- You want to query search data with SQL and reuse existing MySQL drivers, tools, and application logic.
- You need columnar storage to run analytical and search queries on datasets that exceed the RAM of a single server.
- You need synchronous replication where every node in a cluster confirms writes and presents fresh data immediately.
- You want an established engine with a twenty-year lineage, active releases, and advanced features such as conversational chat models.

## Where Exofind is the better choice

- You want storage buckets to hold the authoritative index data so you can replace or scale search nodes without running restore procedures.
- You prefer to manage schemas and queries through a declarative HTTP JSON API with an OpenAPI specification.
- You store multilingual content where individual field values require distinct language rules and compound splitting.
- You want schema changes to build new index generations in the background and promote them with zero search downtime.

## Moving from Manticore Search

When you move from Manticore Search to Exofind, consider these differences in design:

- **Tables become index definitions:** In Manticore Search, you create real-time or plain tables with SQL statements. In Exofind, you send a JSON definition to `PUT /v1alpha1/admin/indexes/{name}`.
- **SQL queries become JSON requests:** Manticore queries use `SELECT` statements with match functions. Exofind queries use JSON payloads sent to `POST /v1alpha1/indexes/{name}/search`.
- **Table morphology becomes value locales:** Instead of setting table-wide morphology rules, pass a language tag with each string value or use the index default locale.
- **Backups become primary storage:** You do not run backup jobs to S3. You configure your Exofind nodes with bucket credentials, and the engine writes index files directly to the target bucket.

## Next steps

- [How Exofind is put together](/explanation/architecture/)
- [Define an index](/how-to/define-an-index/)
- [Supported locales](/reference/locales/)
