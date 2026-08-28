# Examples

This directory contains datasets that the demo search pages use. Each directory holds everything needed to put one dataset into a node: the index definition, the documents, a script that loads them, and source attribution.

| Example | Shows |
|---------|-------|
| [livsmedel](livsmedel/) | Compound splitting in Swedish, facets counted sideways of filters, range buckets, highlighting |
| [airports](airports/) | Autocomplete, typo tolerance, prefix matchers, per-field weights, distance filters and sorts |
| [cleveland](cleveland/) | Facets counted sideways of filters on a wall of thumbnails, range buckets on a year, ordering, numbered pages against cursors, highlighting |

The pages that search these datasets are part of the website, located under [`website/src/examples/`](../website/src/examples/). A dataset directory and its demo page share the same name. They are kept separate because a page is built and published with the documentation, while a dataset is loaded into a node and stored in object storage.

## Running examples locally

To run the examples locally, start a node, load an example dataset, and serve the website:

1. Start a node. For instructions on starting a node, see the [getting started tutorial](../docs/tutorials/getting-started.md).
2. Load an example dataset and start the development server:

```shell
mise run example:livsmedel     # or examples/livsmedel/load.sh
mise run example:airports      # and so on, one task per example
mise run site                  # documentation and demo pages, on localhost:4321
```

The load script sends a `PUT` request with the index definition, a `POST` request with the documents, and a commit request to the [documents API](../docs/reference/documents-api.md).

To point the script to a different node or index, set `NODE` and `INDEX`. To pass credentials, set `KEY`:

```shell
NODE=https://demo.example.com KEY=exok_... examples/livsmedel/load.sh
```

Without a `KEY`, the script sends no `Authorization` header. This is the default for development mode and `mise run example:*`.

When a node enforces authentication, provide a key with `indexes.write`, `documents.write`, and `indexes.commit` permissions on the index. Search-only keys cannot load datasets because loading requires write permissions.

The target node must have the indexer role enabled with `EXOFIND_INDEXER_ENABLED=true`. A node without the indexer role returns `index:readonly` for write requests, which stops the load process after the index definition. Read and search requests do not require the indexer role; any node with a copy can serve search queries for the demo pages.

In development mode, the node accepts requests from any origin. In other environments, configure allowed origins using `QUARKUS_HTTP_CORS_ENABLED` and `QUARKUS_HTTP_CORS_ORIGINS`.

## Adding an example

To add an example, create a dataset directory here and a corresponding page in the website using the same name:

- In this directory:
  - `definition.json`: The index definition.
  - `documents.jsonl.gz`: The document data.
  - `load.sh`: A script that loads the definition and documents into a node.
  - `prepare.py`: A script to generate documents if they are built from a published source.
  - `ATTRIBUTION.md`: A file that identifies the data source.
  - A row in the table in this file.
  - A `mise run example:<name>` task.
- In the website:
  - An entry in [`website/src/examples/demos.mjs`](../website/src/examples/demos.mjs), which populates the catalogue, the front page, and the sidebar.
  - A `main.js` file next to the entry.
  - A page under `website/src/pages/examples/`. For details on page structure, see [`website/README.md`](../website/README.md).
