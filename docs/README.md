# Exofind documentation

Exofind is an experimental search engine that keeps its indexes in S3
compatible object storage. Nodes hold local copies of the indexes they serve,
one node at a time is allowed to write, and every node answers searches from
its own copy.

The documentation is organized along [Diátaxis](https://diataxis.fr):
tutorials teach by doing, how-to guides solve a task, reference states what
is, and explanation says why it is that way.

> Exofind is experimental and the API is `v1alpha1` - it changes without
> keeping compatibility.

## Tutorials

- [Getting started](tutorials/getting-started.md) - run a node against local
  object storage and define your first index.

## How-to guides

- [Define an index](how-to/define-an-index.md) - create and update an index
  as desired state, pick what each field is for, avoid overwriting concurrent
  changes.
- [Index documents](how-to/index-documents.md) - send documents, load a
  dataset, keep it current with feeds and deletes, recover from a refused
  request.
- [Search an index](how-to/search-an-index.md) - the search box, the scope it
  runs in, the filters a user ticks, facets, ordering and highlighting.
- [Search by vector](how-to/search-by-vector.md) - index the vectors a model
  produces, find the nearest of them, and combine that with a text search.
- [Use sub-documents](how-to/use-sub-documents.md) - hold a list of values
  that are documents of their own, ask several things of one of them, and
  order, count and change them.
- [Roll out a definition change](how-to/roll-out-a-definition-change.md) -
  change what an index holds for documents already in it, without callers
  noticing.
- [Localize fields](how-to/localize-fields.md) - hold values in several
  languages and search them by locale.
- [Customize text analysis](how-to/customize-analysis.md) - presets, custom
  chains, and stopwords and synonyms shared between fields.
- [Paginate search results](how-to/paginate-search-results.md) - offsets,
  cursors and numbered pages, and when each is the right tool.
- [Run on one node](how-to/run-on-one-node.md) - keep everything on disk with
  nothing else running, and what that costs.
- [Run more than one node](how-to/run-multiple-nodes.md) - indexer candidacy,
  failover and how writes find the node that serves them.
- [Deploy on Kubernetes](how-to/deploy-on-kubernetes.md) - a pool that
  searches and a pool that writes, where the writes go, and what many indexes
  need from the host.
- [Operate a deployment](how-to/operate-a-deployment.md) - see what each node
  is serving, tell a node that is behind from one that is broken, keep disk
  and upgrades in hand.
- [Secure a deployment](how-to/secure-a-deployment.md) - bootstrap the first
  key, hand out one per thing that holds one, rotate them.
- [Run a public demo node](how-to/run-a-demo-node.md) - answer searches from a
  browser with no credential, and narrow what that can cost.
- [Survive Lucene upgrades](how-to/survive-lucene-upgrades.md) - notice an
  index nearing the end of its readable life and reindex it in time.
- [Benchmark the engine](how-to/benchmark-the-engine.md) - measure searching
  and indexing, and compare a change against what came before it.

## Reference

- [Configuration](reference/configuration.md) - every environment variable.
- [Authentication](reference/auth.md) - keys, permissions, roles and the
  keys API.
- [Admin API](reference/admin-api.md) - defining, inspecting and removing
  indexes; index states.
- [Documents API](reference/documents-api.md) - putting documents into an
  index, taking them out again, and the shape they are written in.
- [Search API](reference/search-api.md) - the request and response, every
  clause, matcher and sort.
- [Field types](reference/field-types.md) - the types a field can have and
  the ways each can be used.
- [Analysis](reference/analysis.md) - presets, tokenizers, char filters and
  token filters.
- [Locales](reference/locales.md) - the languages there are rules for, and
  what each one gets.
- [Errors](reference/errors.md) - the error body and the code vocabulary.

## Explanation

- [Architecture](explanation/architecture.md) - why storage is the source of
  truth, and what nodes are for.
- [Generations](explanation/generations.md) - why an index is a name with
  generations under it, and why the name is what callers hold.
- [Synchronization](explanation/synchronization.md) - manifests, epochs and
  the leadership table; what keeps two writers from corrupting an index.
- [Lucene compatibility](explanation/lucene-compatibility.md) - why an index
  can outlive the code that can read it, and what the engine does about it.
- [Relevance](explanation/relevance.md) - the layers results are ordered by,
  what each one is for, and which of them a change reaches without
  reindexing.
