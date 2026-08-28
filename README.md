# Exofind

Exofind is an experimental search engine that stores indexes in S3-compatible
object storage. Nodes maintain local copies, coordinate through the storage
bucket alone, and answer search queries from their local copies. Nodes are
interchangeable, require no persistent volumes for backup, and do not
communicate with each other directly.

Key features include:

- **Object storage as the source of truth:** When you wipe a node, the node
  pulls all index data back from the bucket. Only one node writes at a time;
  conditional writes prevent stale writers from corrupting data.
- **Search built on Lucene:** Full-text matching with typo tolerance and
  autocomplete, filtering, sorting, numbers, timestamps, geopoints, lists of
  nested objects, and k-nearest neighbors (KNN) vector search.
- **Locale-aware analysis:** Values carry their locale. Analysis, segmentation,
  and collation automatically adapt to the locale, including Chinese,
  Japanese, and Korean, without custom analyzer configuration.
- **Declarative index definitions:** You define an index by sending a `PUT`
  request with the desired definition, allowing index definitions to live in
  version control.
- **Zero-downtime schema migrations:** Indexes contain generations. When schema
  changes require reindexing, Exofind populates a new generation and promotes
  it. Clients and API keys continue using the index by name.
- **Shared API keys:** Keys are stored in the bucket. A key created on one
  node works on all nodes, supports scoping by permissions and index
  patterns, and can be revoked without redeploying services.

Full documentation is available in [`docs/`](docs/README.md), and is published
along with the demo pages at
[levelfourab.github.io/exofind](https://levelfourab.github.io/exofind/). It is
organized by [Diátaxis](https://diataxis.fr):

- **[Tutorials](docs/README.md#tutorials)**: Run the published image with
  Docker and define your first index, then move it onto an object storage
  bucket.
- **[How-to guides](docs/README.md#how-to-guides)**: Define indexes, roll out
  schema changes, use localized fields and custom analysis, configure
  pagination, run multiple nodes, and manage Lucene upgrades.
- **[Reference](docs/README.md#reference)**: Configuration settings, admin and
  search APIs, field types, analysis components, and error codes.
- **[Explanation](docs/README.md#explanation)**: Architectural design, index
  generations, synchronization mechanisms, and Lucene compatibility.

## Quick start

Start a node using Docker:

```shell
docker run -d --name exofind -p 8080:8080 \
  -v exofind-data:/data \
  -e EXOFIND_AUTH_ROOT_KEY=exok_quickstart \
  ghcr.io/levelfourab/exofind:main-latest
```

The node stores indexes, the index registry, and API keys in the `exofind-data`
volume on local disk. Replace the root key value before exposing the node over a
network. `GET /q/health/ready` reports when the node is ready without requiring
a key. For production deployments, pin a release tag from
[Published images](#published-images).

Define an index by sending the desired definition in a `PUT` request. The same
request creates or updates the index:

```http
PUT /v1alpha1/admin/indexes/books
Authorization: Bearer exok_quickstart
Content-Type: application/json

{
  "fields": {
    "id": { "type": "string", "primaryKey": true, "required": true },
    "title": { "type": "string", "matching": { "typoTolerance": {} }, "sort": {} },
    "category": { "type": "string", "filter": {}, "facet": {} },
    "published": { "type": "boolean", "filter": {} }
  }
}
```

Add documents to the index:

```http
POST /v1alpha1/indexes/books/documents
Authorization: Bearer exok_quickstart
Content-Type: application/json

{
  "documents": [
    { "id": "1", "title": "Silent Spring", "category": "non-fiction", "published": true }
  ]
}
```

Delete a document by its primary key, or delete multiple documents by query:

```http
DELETE /v1alpha1/indexes/books/documents/1
Authorization: Bearer exok_quickstart
```

Search from any node. A list of query clauses uses an implicit `AND` condition:

```http
POST /v1alpha1/indexes/books/search
Authorization: Bearer exok_quickstart
Content-Type: application/json

{
  "query": [
    { "type": "text", "text": "silent spr" },
    { "field": "published", "match": { "value": true } }
  ],
  "limit": 20
}
```

The image enforces credentials, so requests carry a bearer token. The root key
is for bootstrapping; a real deployment uses it once to create keys through
`POST /v1alpha1/admin/keys`, one per client. For details, see
[Secure a deployment](docs/how-to/secure-a-deployment.md).

For step-by-step walkthroughs, see:

- [Getting started](docs/tutorials/getting-started.md) - the walkthrough this
  section compresses.
- [Getting started with object storage](docs/tutorials/getting-started-with-object-storage.md) -
  the same walkthrough using an object storage bucket.

## Examples

Demo pages that search real datasets through a running node, one per thing
worth showing. The datasets are in [`examples/`](examples/README.md) - an index
definition, the documents and a script that loads them - and the pages that
search them are part of the [website](website/README.md).

To run them against a running node:

```shell
mise run example:livsmedel     # loads 2 606 Swedish foods and commits
mise run example:airports      # 8 799 airports, completed as you type
mise run example:cleveland     # 30 000 museum objects, on a wall of thumbnails
mise run site                  # serves the pages against that node
```

Searching the food dataset for `sås` matches 21 items when matching whole words
only, and 124 items when compound words are split.

## Development

Exofind uses [Quarkus](https://quarkus.io/) with toolchains managed by
[mise](https://mise.jdx.dev/). Common workflows include:

```shell
mise run dev         # Quarkus dev mode with hot reload
mise run build       # mvn package
mise run test        # mvn test
mise run verify      # full verification build
mise run bench       # JMH benchmarks for searching and indexing
mise run image       # build the container image
mise run image:amd64 # the same image for an x86-64 host
mise run storage      # start object storage via docker compose
mise run storage:stop # stop object storage
```

`mise run bench` accepts a benchmark pattern and JMH options. Without
arguments, it runs all benchmarks, which takes several hours. For details on
benchmark scenarios, see
[Benchmark the engine](docs/how-to/benchmark-the-engine.md).

Both container build tasks package the application first and copy the build
artifacts into the image using the JDK configured in `mise.toml`.
The `mise run image` command builds for the host architecture, while
`mise run image:amd64` builds for x86-64. Both images use the same configuration
as running from source, but default `EXOFIND_STORAGE_LOCAL_DIRECTORY` to
`/data`:

```shell
docker run --rm -p 8080:8080 \
  -e EXOFIND_STORAGE_MODE=object \
  -e EXOFIND_STORAGE_REMOTE_URL=http://host.docker.internal:9000 \
  -e EXOFIND_STORAGE_REMOTE_ACCESS_KEY=exofind \
  -e EXOFIND_STORAGE_REMOTE_SECRET_KEY=exofind123 \
  -e EXOFIND_STORAGE_REMOTE_BUCKET=exofind \
  -e EXOFIND_AUTH_ROOT_KEY=dev-root-key \
  exofind/engine:dev
```

Unlike development mode, the container image enforces credentials. Pass
`Authorization: Bearer dev-root-key` on requests until you create additional
keys.

The image sizes the JVM heap based on the container memory limit and configures
Lucene JVM settings. To append or override JVM options, use
`JAVA_OPTS_APPEND`. For details, see
[The JVM](docs/reference/configuration.md#the-jvm).

## Published images

Images for x86-64 and arm64 are published to `ghcr.io/levelfourab/exofind` on
each release and on every merge to `main`:

| Tag            | What it is                                              |
| -------------- | ------------------------------------------------------- |
| `0.1.0`        | One release, and the same image for as long as it exists |
| `0.1`          | The newest patch of that minor version                   |
| `0`            | The newest release that has not broken compatibility     |
| `latest`       | The newest release there is                             |
| `main-latest`  | The tip of `main`, ahead of any release                 |
| `main-a1b2c3d` | One commit on `main`                                    |

To keep a deployment on an exact version, pin `0.1.0` or a `main-<rev>` tag.
Other tags update as new releases are published.

## Releases

Commit messages follow the
[Conventional Commits](https://www.conventionalcommits.org/) specification.
Release Please uses these commit messages to maintain an open pull request with
the next version number and changelog entries. Merging the pull request creates
the release: it updates `CHANGELOG.md`, tags the commit, and publishes the
GitHub release and container images.

Commit types determine the version increment:
- `fix` increments the patch version.
- `feat` increments the minor version.
- `!` or a `BREAKING CHANGE:` footer increments the minor version while the major
  version is 0.

The version in `pom.xml` is managed by the release pull request and uses
`-SNAPSHOT` between releases to indicate development after the preceding
release.

## License

Copyright 2026 Level Four AB

Licensed under the Apache License, Version 2.0. You may not use this work
except in compliance with the License; a copy is in [LICENSE](LICENSE) and at
<https://www.apache.org/licenses/LICENSE-2.0>.
