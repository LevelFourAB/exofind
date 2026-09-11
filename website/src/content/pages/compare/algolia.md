# Exofind vs Algolia

Algolia is a hosted product billed by searches and records. Exofind is an engine you run yourself, on your own servers and your own bucket.

[Algolia](https://www.algolia.com) is a mature, hosted search platform that set the expectations for how an interactive search box feels. Exofind is an experimental open-source search engine with a `v1alpha1` API that you run on your own infrastructure. The central difference is between buying a complete hosted service with user interfaces and analytics, and running an engine API on top of your own object storage.

## At a glance

| | Exofind | Algolia |
| --- | --- | --- |
| License | Apache License 2.0 | Proprietary |
| Where the data lives | Your S3-compatible bucket and node disk | Algolia cloud servers |
| How nodes coordinate | Through the storage bucket alone | Managed distributed network |
| Scaling | Add nodes behind a standard load balancer | Managed scaling by tier |
| Language analysis | 59 locales, per-value locales, Nordic compound splitting | Many languages, phonemic typo tolerance, 3 compound languages |
| Schema changes | Declarative index definitions with background reindexing | Dashboard settings and API updates |
| Hosting | Self-hosted container image | Fully hosted cloud service |

## The main difference

### Hosted product versus self-hosted engine
Algolia is a cloud service. You do not manage nodes, configure storage, or maintain an on-call rotation for search infrastructure. Algolia runs a distributed network of servers to keep latency low near end users. Exofind provides a container image that you deploy on your own servers or cloud infrastructure. Exofind is experimental, and its `v1alpha1` API can change without backward compatibility.

### Request billing versus infrastructure costs
Algolia charges for usage based on search requests and total records. Reported pricing figures for 2026 list a free Build tier with 10,000 requests and 1 million records, a Grow tier at roughly $0.50 per 1,000 requests and $0.40 per 1,000 records above included allowances, a Grow Plus tier at $1.75 per 1,000 requests, and enterprise Elevate plans starting around $50,000 annually. Check Algolia's pricing page for current rates.

Exofind costs only the servers and the object storage bucket you run. Your bill does not increase when users submit more search requests.

### Data location and record limits
With Algolia, your documents reside on Algolia's infrastructure. Standard plans limit individual records to 10 KB, with higher plans allowing up to 100 KB. Splitting larger documents is common with Algolia.

Exofind keeps the authoritative copy of your data in an S3-compatible object storage bucket that you own. Nodes use local disk as a cache. Exofind does not enforce record size tiers.

### UI tooling versus API-only design
Algolia is a complete search product. It provides a web dashboard, search analytics, A/B testing, merchandising rules, query suggestions, and InstantSearch UI libraries for front-end frameworks.

Exofind provides a search engine API with an OpenAPI specification. Exofind does not have a dashboard, an analytics user interface, merchandising tools, or front-end component libraries.

## What Exofind trades away

Exofind buys its simplicity with real limits. Read these before you compare anything else.

- **You operate it.** Algolia is on call for its own service. With Exofind, the nodes, the bucket, the upgrades, and the alerts are yours.
- **Freshness is seconds, not milliseconds.** A search node answers from its last pull of the bucket. `EXOFIND_INDEXES_REFRESH_INTERVAL` defaults to 30 seconds.
- **No product above the API.** There is no dashboard, no search analytics, no A/B testing, no personalization, no merchandising interface for a non-engineer, and no InstantSearch libraries.
- **Early software, one team.** The `v1alpha1` API changes without keeping compatibility, and Level Four AB is the only company behind it. Algolia is a mature product with a large company behind it. The two are not equivalent.

## Where Algolia is the better choice

- You want a complete product with a management dashboard, analytics, and merchandising rules for non-engineers.
- You want pre-built front-end components such as InstantSearch libraries for your application.
- You prefer a fully managed cloud service and do not want to operate search infrastructure.
- You need low global edge latency across a distributed search network.
- You need built-in personalization, A/B testing, and recommendations without building them yourself.

## Where Exofind is the better choice

- Your data residency rules, privacy policies, or regulations require your catalogue to stay on your own infrastructure.
- You want predictable hosting costs based on your compute nodes and bucket storage rather than search query volume.
- You need per-value locale analysis or compound splitting for Nordic languages like Danish, Icelandic, Norwegian, and Swedish.
- You want to index documents without splitting them to fit 10 KB or 100 KB record size tiers.
- You want open-source software under the Apache License 2.0 that you can inspect and run locally.

## Moving from Algolia

- Records map to JSON documents sent to Exofind index endpoints.
- Algolia index settings map to declarative Exofind index definitions, but Exofind does not support visual merchandising rules.
- Typo tolerance and facet configurations map to field definitions and search settings in Exofind.
- Front-end integration requires your own client code or code generated from the Exofind OpenAPI specification, because Exofind has no InstantSearch libraries.

## Next steps

- [Run a node and define an index](/tutorials/getting-started/)
- [Define an index](/how-to/define-an-index/)
- [How Exofind is put together](/explanation/architecture/)
- [Supported locales](/reference/locales/)
- [Search an index](/how-to/search-an-index/)
