# Exofind documentation

Exofind is an experimental search engine that keeps its indexes in S3-compatible
object storage. Nodes hold local copies of the indexes that they serve. Only
one node at a time can write, and every node answers searches from its own copy.

The documentation is organized according to [Diátaxis](https://diataxis.fr):
tutorials teach by doing, how-to guides solve a task, reference states what is,
and explanation explains why it is that way.

**Note:** Exofind is experimental and the API is `v1alpha1`. The API changes
without keeping compatibility.

## Tutorials

The documentation includes the following tutorial:

- [Getting started](tutorials/getting-started.md): Run a node against local
  object storage and define your first index.

## How-to guides

The documentation includes the following how-to guides:

- [Define an index](how-to/define-an-index.md): Create and update an index as
  desired state, pick what each field is for, and avoid overwriting concurrent
  changes.
- [Index documents](how-to/index-documents.md): Send documents, load a
  dataset, keep it current with feeds and deletes, and recover from a refused
  request.
- [Search an index](how-to/search-an-index.md): Work with the search box, the
  scope it runs in, user-selected filters, facets, ordering, and highlighting.
- [Search by vector](how-to/search-by-vector.md): Index the vectors that a model
  produces, find the nearest of them, and combine that with a text search.
- [Use sub-documents](how-to/use-sub-documents.md): Hold a list of values that
  are documents of their own, ask several things of one of them, and order,
  count, and change them.
- [Roll out a definition change](how-to/roll-out-a-definition-change.md):
  Change what an index holds for documents already in it, without callers
  noticing.
- [Reindex into a new generation](how-to/reindex-into-a-new-generation.md):
  Have the engine fill the new generation from the one it replaces, instead
  of sending every document again.
- [Localize fields](how-to/localize-fields.md): Hold values in several languages
  and search them by locale.
- [Customize text analysis](how-to/customize-analysis.md): Configure presets,
  custom chains, and stopwords and synonyms shared between fields.
- [Paginate search results](how-to/paginate-search-results.md): Use offsets,
  cursors, and numbered pages, and choose when each is the right tool.
- [Run on one node](how-to/run-on-one-node.md): Keep everything on disk with
  nothing else running, and manage what that costs.
- [Run more than one node](how-to/run-multiple-nodes.md): Configure indexer
  candidacy, failover, and how writes find the node that serves them.
- [Deploy on Kubernetes](how-to/deploy-on-kubernetes.md): Configure a pool that
  searches and a pool that writes, route writes, and handle host requirements for
  many indexes.
- [Operate a deployment](how-to/operate-a-deployment.md): See what each node is
  serving, tell a node that is behind from one that is broken, and manage disk
  and upgrades.
- [Repair the index registry](how-to/repair-the-index-registry.md): Audit the
  registry against what the storage holds, rebuild one that is lost or
  corrupt, and spot drift before it matters.
- [Secure a deployment](how-to/secure-a-deployment.md): Bootstrap the first key,
  hand out one key per client, and rotate keys.
- [Run a public demo node](how-to/run-a-demo-node.md): Answer searches from a
  browser with no credentials, and narrow what that can cost.
- [Survive Lucene upgrades](how-to/survive-lucene-upgrades.md): Notice an index
  nearing the end of its readable life and reindex it in time.
- [Benchmark the engine](how-to/benchmark-the-engine.md): Measure searching and
  indexing, and compare a change against what came before it.

## Reference

The documentation includes the following reference topics:

- [Configuration](reference/configuration.md): Every environment variable.
- [Authentication](reference/auth.md): Keys, permissions, roles, and the keys
  API.
- [Admin API](reference/admin-api.md): Defining, inspecting, and removing
  indexes; index states.
- [Documents API](reference/documents-api.md): Putting documents into an index,
  taking them out again, and the shape they are written in.
- [Search API](reference/search-api.md): The request and response, every clause,
  matcher, and sort.
- [Field types](reference/field-types.md): The types a field can have and the
  ways each can be used.
- [Analysis](reference/analysis.md): Presets, tokenizers, char filters, and
  token filters.
- [Locales](reference/locales.md): The languages with rules, and what each one
  gets.
- [Errors](reference/errors.md): The error body and the code vocabulary.

## Explanation

The documentation includes the following explanations:

- [Architecture](explanation/architecture.md): Why storage is the source of
  truth, and what nodes are for.
- [Generations](explanation/generations.md): Why an index is a name with
  generations under it, and why the name is what callers hold.
- [Synchronization](explanation/synchronization.md): Manifests, epochs, and the
  leadership table; what keeps two writers from corrupting an index.
- [Separating search and indexing nodes](explanation/deployment-shapes.md): Why
  searching and writing want different nodes, and what a deployment runs out of
  first as its index count grows.
- [Node memory and JVM configuration](explanation/node-resources.md): Why the
  heap is sized against the page cache, and what each JVM flag is there for.
- [Trust model](explanation/trust-model.md): What a key can reach, why it cannot
  be narrowed per end user, and why a browser must not hold one.
- [How sub-documents are stored](explanation/document-blocks.md): What a value
  of an `object` field costs to hold, change, and search.
- [Lucene compatibility](explanation/lucene-compatibility.md): Why an index can
  outlive the code that can read it, and what the engine does about it.
- [Relevance](explanation/relevance.md): The layers results are ordered by, what
  each one is for, and which of them a change reaches without reindexing.
