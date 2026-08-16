# Benchmark the engine

The benchmarks measure searching and indexing so that a change can be shown to
have made something faster rather than argued to have. They are [JMH][jmh]
benchmarks under `src/benchmark/java`, compiled and run only under the
`benchmark` Maven profile - an ordinary build never sees them.

```shell
mise run bench                      # everything, which takes hours
mise run bench TextSearchBenchmark  # one class
mise run bench 'Facet.*hierarchy'   # anything matching a regular expression
```

Anything after the task name is passed on to the JMH runner, so its options
work as they do anywhere else - `-f 3` for three forks, `-prof gc` for
allocation, `-rf json -rff before.json` to write results out.

[jmh]: https://github.com/openjdk/jmh

## What runs against what

Documents are generated rather than loaded, from one seed, so an index built
today holds the same documents as one built last month. Three corpora, chosen
with `-p corpus=`:

| Corpus      | What it holds                                                                                             |
| ----------- | --------------------------------------------------------------------------------------------------------- |
| `minimal`   | A key and two plain fields. Nothing is analyzed - the baseline every other number is read against.         |
| `catalogue` | A product or place search: matched and completed text, filters, facets, a category tree, numbers, a timestamp, a geo point and nested variants. |
| `articles`  | Short fields and one long body, so analysis and term volume dominate. `articles:sv` indexes it under Swedish, whose chain splits compound words. |

Text is drawn from a vocabulary whose words are as unevenly common as words in
real text are, so a term's rank decides how many documents hold it. The
benchmarks look for a common one, a middling one and a rare one by name.

## The search benchmarks

They open a committed index of a corpus and search it, with no HTTP and no
Quarkus in the way. How large that index is comes from `-p size=` and defaults
to 100 000 documents.

| Class                  | What it measures                                                                              |
| ---------------------- | ---------------------------------------------------------------------------------------------- |
| `FilterBenchmark`      | Narrowing without ranking - equality, ranges, prefixes, negation, a subtree, a distance, and what an exact total costs. |
| `TextSearchBenchmark`  | Text from a search box - one word, several, half-typed, misspelled, quoted, highlighted, and the second pass a search that found nothing makes. |
| `FacetBenchmark`       | Counting the matches per value, per bucket and down a tree, one facet and a page's worth.       |
| `SortAndPageBenchmark` | Ordering by a field rather than by relevance, ranking signals, and a deep page reached by offset against the same page reached by cursor. |
| `NestedBenchmark`      | Conditions on the values inside an object field, and counting them.                             |

The first run of a size builds the index and keeps it in
`target/benchmark-indexes`; later runs copy it. Building 100 000 catalogue
documents takes minutes, so leave the directory alone between runs and delete
it when the change being measured is one that alters what gets written.

## The indexing benchmarks

`IndexingBenchmark` writes a batch into an empty index, and
`DocumentChangeBenchmark` replaces, patches and deletes documents in an index
that already holds them. Both report per batch, so divide by `-p batch=` for a
rate.

```shell
mise run bench IndexingBenchmark -p corpus=minimal,catalogue -p batch=5000
```

Running one corpus against another is how the cost of a usage is told apart
from the cost of holding a document at all: `catalogue` minus `minimal` is what
the analysis, facets, sorting and nested values in a catalogue document cost.

## The API benchmarks

`RestSearchBenchmark` and `RestIndexingBenchmark` send real requests to a real
node, so they measure the mapping and the round trip as well as the engine.
Start a node yourself first - benchmarking a server from inside the JVM running
it makes the two compete for the same cores.

```shell
LOCAL_STORAGE_DIRECTORY=data/benchmark mise run run   # in one terminal
mise run bench 'rest\..*' -p node=http://localhost:8080
```

`mise run run` already starts the node as the indexer and with no credentials
checked, which is what the benchmarks need - any other node has to be the
indexer too, or every write is refused as readonly. A node that checks
credentials wants `-p key=exok_...` with `indexes.write`, `documents.write` and
`indexes.commit` over the indexes named `benchmark-*`.

Setup defines the index and fills it if it does not already hold the right
number of documents, so a second run against the same node starts measuring
immediately. Nothing is removed afterwards; the indexing benchmarks write to an
index of their own, so they cannot disturb what a search benchmark reads.

## Compare a change

Only numbers from the same machine, doing nothing else, compare at all. Write
the results out before and after:

```shell
mise run bench FilterBenchmark -rf json -rff before.json
# make the change
mise run bench FilterBenchmark -rf json -rff after.json
```

Then read the two side by side. What a comparison needs to be worth anything:

- **The same corpus, size and batch.** They are printed with every result.
- **Enough forks.** One fork measures one JIT compilation of the code; `-f 3`
  or more is what tells a real difference from a lucky one. The default is one,
  for a run that finishes.
- **A whole benchmark class.** A change that helps one clause and hurts
  another is only visible if both were run.

`-prof gc` answers how much a search allocates, which is often the thing to
change rather than the time itself.

## Add a benchmark

A search benchmark takes `LoadedIndex` as a parameter and builds its requests
in a `@Setup(Level.Trial)` method, so what is timed is the search rather than
the describing of it. Name the field it needs through `Corpus.Roles` rather
than by name, and the same benchmark runs over any corpus that has one.
