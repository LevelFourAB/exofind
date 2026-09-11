# Exofind vs Typesense

Typesense sizes its nodes by RAM and needs three of them to survive a failure. Exofind sizes its nodes by disk cache over one bucket.

[Typesense](https://typesense.org) is an open source search engine written in C++ that indexes data in memory and is far easier to start with. Exofind is an experimental search engine with a `v1alpha1` API that stores its authoritative indexes in S3-compatible object storage. The main difference between the two engines is how they size hardware: Typesense sizes nodes by RAM and replicates the full dataset across a Raft quorum, while Exofind sizes nodes by disk cache over a shared storage bucket with nodes that do not communicate.

## At a glance

| | Exofind | Typesense |
| --- | --- | --- |
| License | Apache 2.0 | GPL-3.0 |
| Where the data lives | Object storage, local disk cache | In-memory index, raw data on disk |
| How nodes coordinate | Through the bucket alone, no quorum | Raft consensus, minimum 3 nodes |
| Scaling | Add interchangeable reader nodes | Add nodes holding full dataset |
| Language analysis | 59 locales, per-value locale, compound splitting | ISO 639-1 per field, ICU rules |
| Schema changes | Background generations (writes continue) | In-place alter (blocks writes) |
| Hosting | Self-hosted only | Self-hosted or Typesense Cloud |

## The main difference

### RAM sizing compared to disk cache

Typesense requires enough RAM to hold all indexed fields in memory. Typesense recommends provisioning 2 to 3 times the size of your searchable fields in RAM. Unindexed fields reside on disk, but RAM remains the primary sizing constraint.

Exofind uses local disk as a bounded cache over an object storage bucket. The bucket holds the authoritative copy of every index. You set a maximum disk budget with `exofind.indexes.disk.max-size`, and the node removes the least recently used local index data when needed.

### Raft consensus compared to isolated nodes

Typesense uses the Raft consensus algorithm to manage clusters. High availability requires a minimum of three nodes, and each node stores a full replica of the searchable dataset in RAM.

Exofind nodes never talk to each other. They coordinate through the storage bucket using conditional writes and a leadership table. You can run a single node or add reader nodes behind a standard load balancer. Adding search capacity does not multiply memory requirements across nodes.

### Cluster write blocking compared to index generations

Typesense alters schemas in place across all nodes in parallel. While an alter operation runs, the cluster blocks all write operations until schema validation and indexing complete.

Exofind uses index generations. When a definition change requires a rebuild, Exofind creates a new generation beside the active generation. The active generation serves searches, and writers continue ingesting data. Once the new generation finishes indexing, Exofind promotes it without downtime.

### Per-field locale compared to per-value locale

Typesense sets language analysis at the field level with an ISO 639-1 code and uses ICU rules. Each field can hold only one language.

Exofind attaches the locale to individual field values rather than the schema field. A single document can store values in different languages inside the same field. Exofind also provides compound word splitting for languages such as German, Dutch, Swedish, Danish, Norwegian, Finnish, and Icelandic.

## What Exofind trades away

Exofind buys its simplicity with real limits. Read these before you compare anything else.

- **Freshness is seconds, not milliseconds.** A search node answers from its last pull of the bucket. `EXOFIND_INDEXES_REFRESH_INTERVAL` defaults to 30 seconds. A Typesense cluster applies a write to every node as it happens.
- **One writer per index.** Write throughput for a single index is the throughput of one node. You spread Exofind writes by putting different indexes on different nodes.
- **Nothing above the API.** Exofind has no dashboard, no analytics, and no front-end libraries. Typesense has a large community, official clients, and integrations for most frameworks.
- **Early software, one team.** The `v1alpha1` API changes without keeping compatibility, and Level Four AB is the only company behind it.

## Where Typesense is the better choice

- You want an instant-search setup that runs quickly with single-binary simplicity and no dependencies.
- You want a fully managed service through Typesense Cloud.
- Your searchable data fits comfortably inside server RAM.
- You need a stable production API with an active community and official client libraries.
- You need features such as federated multi-search out of the box.

## Where Exofind is the better choice

- Your dataset is larger than you want to store in RAM across multiple cluster nodes.
- You want object storage as the authoritative source of truth with disposable search nodes.
- You need to update schemas without blocking cluster write traffic.
- You index multilingual content in the same field or need compound word splitting for Germanic and Nordic languages.
- You require an Apache 2.0 license rather than a GPL-3.0 copyleft license.

## Moving from Typesense

- **Collections to indexes**: Typesense collections map to Exofind index definitions. In Exofind, you define indexes declaratively with `PUT` requests.
- **Field locales to value locales**: Instead of defining a single locale on a schema field, provide a locale tag with each field value in your documents.
- **Alters to generations**: Replace in-place alter operations with Exofind generations, which build new index versions in the background.
- **Cluster setup to independent nodes**: Replace Raft cluster peer configurations with an S3 bucket configuration and run stateless nodes behind a load balancer.

## Next steps

- [Run a node and define an index](/tutorials/getting-started/)
- [Define an index](/how-to/define-an-index/)
- [How Exofind is put together](/explanation/architecture/)
- [Supported locales](/reference/locales/)
- [Search an index](/how-to/search-an-index/)
