# Benchmarking the engine

This guide shows you how to run benchmarks to measure searching and indexing performance and how to compare results across code changes. Use this guide when you want to measure engine performance, test performance regressions, or evaluate data layouts.

Engine benchmarks use the [Java Microbenchmark Harness (JMH)][jmh] and live under `src/benchmark/java`. They compile and run only under the `benchmark` Maven profile.

## Prerequisites

Before you run the benchmarks, ensure that you have:

- `mise` installed on your machine.
- The project repository cloned locally.

## Comparing performance across changes

To measure the performance impact of a code change:

1. Run the benchmark on your baseline code and save the results to a file:
   ```shell
   mise run bench FilterBenchmark -rf json -rff before.json
   ```
2. Make your code change.
3. Clean and recompile the benchmark classes while preserving existing benchmark indexes:
   ```shell
   mv target/benchmark-indexes /tmp/
   ./mvnw -Pbenchmark clean test-compile
   mv /tmp/benchmark-indexes target/
   ```
   **Note:** `mise run bench` recompiles incrementally, which leaves generated JMH classes stale. Forks then fail with `NoClassDefFoundError: InfraControl` and the runner exits with code 0 while omitting benchmarks from the results.
4. Run the benchmark on your updated code and save the new results:
   ```shell
   mise run bench FilterBenchmark -rf json -rff after.json
   ```
5. Compare the `before.json` and `after.json` files side by side.

## Running API benchmarks

To benchmark search and indexing over the REST API:

1. Start a local node in a dedicated terminal:
   ```shell
   LOCAL_STORAGE_DIRECTORY=data/benchmark mise run run
   ```
   This command starts the node as an indexer without credential checks. Run the node in a separate process to prevent it from competing with JMH for CPU cores.
2. In another terminal, run the REST benchmarks:
   ```shell
   mise run bench 'rest\..*' -p node=http://localhost:8080
   ```
3. If your node requires authentication, provide an API key using `-p key=`:
   ```shell
   mise run bench 'rest\..*' -p node=http://localhost:8080 -p key=exok_...
   ```
   Ensure that the key has `indexes.write`, `documents.write`, and `indexes.commit` permissions on indexes named `benchmark-*`.

The setup process defines and populates the index if it does not already exist. Indexing benchmarks write to their own index and do not modify the search benchmark data.

## Comparing variant layouts

To evaluate different ways of holding product variants in a catalogue:

1. Run `GroupingBenchmark` with your chosen shapes and selectivity values:
   ```shell
   mise run bench GroupingBenchmark -p shape=NESTED,COLLAPSED -p selectivity=wide,narrow
   ```
   To disable query clause caching and measure raw layout cost, add `-p cache=off`.
2. Run `ShapeReport` to compare storage size, variant update costs, and query result accuracy across layouts:
   ```shell
   java -cp "target/classes:target/test-classes:$(cat target/benchmark-classpath.txt)" \
     se.l4.exofind.engine.benchmark.grouping.ShapeReport 100000
   ```

## Adding a search benchmark

To create a new search benchmark:

1. Define a benchmark class that accepts `LoadedIndex` as a parameter.
2. Build search requests in a method annotated with `@Setup(Level.Trial)` so that request construction is not timed.
3. Access required fields through `Corpus.Roles` instead of direct field names.

## Confirming results

To confirm that your benchmark results are accurate and comparable:

- **Run on a quiet machine:** Run benchmarks only on a machine with no other active workloads.
- **Verify parameters:** Check that both runs use the same corpus (`-p corpus=`), index size (`-p size=`), and batch size (`-p batch=`).
- **Use sufficient forks:** Run with at least three forks (`-f 3`). One fork measures a single JIT compilation; multiple forks isolate real differences from JIT variance.
- **Run the whole class:** Execute the full benchmark class to ensure an improvement in one clause does not cause regressions elsewhere.
- **Check memory allocation:** Run with `-prof gc` to measure allocation rates.

## Reference

The following sections describe the corpora, benchmark classes, and configuration parameters.

### Corpora

Benchmarks generate documents deterministically from a single seed. Select a corpus with `-p corpus=`.

| Corpus | Description |
| --- | --- |
| `minimal` | Contains a key and two plain fields with no text analysis. Serves as the baseline. |
| `catalogue` | Simulates a product or place search with matched and completed text, filters, facets, a category tree, numbers, a timestamp, a geographic point, and nested variants. |
| `articles` | Contains short fields and one long body field dominated by text analysis and term volume. `articles:sv` indexes under Swedish, which splits compound words. |

Generated text draws from a vocabulary with realistic word frequencies. Benchmarks query for common, middling, and rare terms by name.

### Search benchmark classes

Search benchmarks open a committed index and execute queries without HTTP or Quarkus overhead. Set index size with `-p size=` (defaults to 100 000 documents).

| Class | Description |
| --- | --- |
| `FilterBenchmark` | Measures narrowing without ranking: equality, ranges, prefixes, negation, subtrees, distance, and exact match count costs. |
| `TextSearchBenchmark` | Measures text queries: single-word, multi-word, prefix, misspelled, quoted, highlighted, and second-pass searches when no hits match. |
| `FacetBenchmark` | Measures match counting per value, per bucket, and down category trees: single facet, full page, facet filtering, category drill-down, and count refreshes without document fetches. |
| `SortAndPageBenchmark` | Measures sorting by field versus relevance, ranking signals, and deep pagination with offsets versus cursors. |
| `NestedBenchmark` | Measures conditions on object field values and value counting. |
| `MatchedBenchmark` | Measures retrieving matching object field values per hit with and without conditions. Set returned hit count with `-p page=`. |
| `ValueHitsBenchmark` | Measures returning object field values as hits, sorting by value fields, faceting by value with document rollup, and calculating exact value totals. |

Indexes are built on the first run and cached in `target/benchmark-indexes`. Subsequent runs copy the cached index. Delete this directory if you modify indexing logic.

### Indexing benchmarks

Indexing benchmarks measure document write performance:

- `IndexingBenchmark`: Measures writing document batches into an empty index.
- `DocumentChangeBenchmark`: Measures replacing, patching, and deleting documents in an existing index.

Both benchmarks report throughput per batch. Divide the result by `-p batch=` to calculate per-document rates:

```shell
mise run bench IndexingBenchmark -p corpus=minimal,catalogue -p batch=5000
```

To isolate the cost of specific features, compare results between corpora. For example, `catalogue` minus `minimal` indicates the cost of analysis, facets, sorting, and nested values.

### Grouping benchmark layouts

`GroupingBenchmark` tests four catalogue layouts across six shapes and eight queries:

- Nested variants as sub-documents in a product block.
- One document per variant (searched using three grouping strategies).
- Rolled-up variant values on the product document.
- Separate indexes for products and variants.

In `GroupingBenchmark`, `-p size=` sets the number of products instead of documents. `-p selectivity=` controls how much of the catalogue matches the colour filter. Layouts that cannot answer a query report failures as part of the benchmark comparison.

### JMH command options

Pass arguments after the task name to supply options to the JMH runner:

```shell
mise run bench                      # Run all benchmarks (takes hours)
mise run bench TextSearchBenchmark  # Run a specific benchmark class
mise run bench 'Facet.*hierarchy'   # Run benchmarks matching a regular expression
```

Common options:

| Option | Description |
| --- | --- |
| `-f <n>` | Number of forks (for example, `-f 3`). |
| `-prof gc` | Enable garbage collection profiling to measure memory allocations. |
| `-rf <format>` | Results format (for example, `-rf json`). |
| `-rff <file>` | Results output file path (for example, `-rff before.json`). |
| `-p <param>=<value>` | Benchmark parameter (for example, `-p corpus=catalogue -p size=100000`). |

[jmh]: https://github.com/openjdk/jmh
