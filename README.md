# exofind

Exofind is an experimental search engine that keeps its indexes in S3
compatible object storage. Nodes hold local copies, coordinate through the
bucket alone, and answer searches from their own copy - so nodes are
interchangeable, need no volumes worth backing up, and never talk to each
other.

- **Object storage is the source of truth.** A node can be wiped and pulls
  everything back. One node at a time writes; conditional writes in the
  storage keep a stale writer from corrupting anything.
- **Search built on Lucene**: full text matching with typo tolerance and
  autocomplete; filtering and sorting; numbers, timestamps, geo points,
  lists of nested objects and KNN vector search.
- **Locale aware**: values carry their locale, and analysis, segmentation
  and collation follow it - including Chinese, Japanese and Korean, with no
  analyzer configuration.
- **Definitions are desired state**: an index is defined by `PUT`ting the
  definition it should have, so definitions live in version control.
- **Schema changes roll out behind the name**: an index holds generations,
  and a change that existing documents were not indexed under is rolled out
  by filling a new one and promoting it. Callers - and their keys - go on
  using the index by name.
- **Keys are shared through the bucket** like everything else: a key created
  on one node works on every node, scoped to permissions and index patterns,
  and revoked without redeploying anything.

Full documentation lives in [`docs/`](docs/README.md), organized along
[Diátaxis](https://diataxis.fr):

- **[Tutorials](docs/README.md#tutorials)** — run a node against local
  object storage and define your first index.
- **[How-to guides](docs/README.md#how-to-guides)** — defining indexes,
  rolling out schema changes, localized fields, custom analysis, pagination,
  running more than one node, surviving Lucene upgrades.
- **[Reference](docs/README.md#reference)** — configuration, the admin and
  search APIs, field types, analysis components, error codes.
- **[Explanation](docs/README.md#explanation)** — the architecture, why an
  index is a name with generations under it, how synchronization stays safe
  without coordination between nodes, and why Lucene compatibility needs
  managing.

## Quick start

With [mise](https://mise.jdx.dev/) and Docker installed:

```shell
mise install         # Java and Maven
mise run storage     # object storage on localhost:9000
echo "s3.bucket.create -name exofind" | docker exec -i seaweedfs weed shell

EXOFIND_STORAGE_MODE=object \
REMOTE_STORAGE_URL=http://localhost:9000 \
REMOTE_STORAGE_ACCESS_KEY=exofind \
REMOTE_STORAGE_SECRET_KEY=exofind123 \
REMOTE_STORAGE_BUCKET=exofind \
LOCAL_STORAGE_DIRECTORY=data/indexes \
INDEXER=true \
mise run dev
```

Or without storage to run against, keeping everything on disk - see [Run on
one node](docs/how-to/run-on-one-node.md):

```shell
LOCAL_STORAGE_DIRECTORY=data/indexes mise run dev
```

Define an index by sending the definition it should have - the same request
creates and updates it:

```http
PUT /v1alpha1/admin/indexes/books
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

Put documents in, and commit to make them searchable:

```http
POST /v1alpha1/indexes/books/documents
Content-Type: application/json

{
  "documents": [
    { "id": "1", "title": "Silent Spring", "category": "non-fiction", "published": true }
  ]
}
```

Take one out again by its key, or a batch of them by a query:

```http
DELETE /v1alpha1/indexes/books/documents/1
```

Search from any node - a flat list of clauses is an implicit AND:

```http
POST /v1alpha1/indexes/books/search
Content-Type: application/json

{
  "query": [
    { "type": "text", "text": "silent spr" },
    { "field": "published", "match": { "value": true } }
  ],
  "limit": 20
}
```

Dev mode checks no credentials, which is why the requests above carry none.
Anywhere else a node wants `Authorization: Bearer <key>` - see
[Secure a deployment](docs/how-to/secure-a-deployment.md).

The [getting started tutorial](docs/tutorials/getting-started.md) walks
through this from an empty machine.

## Examples

[`examples/`](examples/README.md) holds small pages that search real data
through a running node - each one a definition, a dataset and a page, sharing
one design and one search client. With a node running:

```shell
mise run example:livsmedel     # loads 2 606 Swedish foods and commits
mise run example:airports      # 8 799 airports, completed as you type
mise run example:cleveland     # 30 000 museum objects, on a wall of thumbnails
mise run examples              # serves the pages against that node
```

Searching the first for `sås` finds 21 foods when only whole words match and
124 when compounds are split, which is what that page is there to show.

## Development

Exofind uses [Quarkus](https://quarkus.io/) as its framework, with the
toolchain managed by mise. The common workflows are tasks:

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

`mise run bench` takes a benchmark pattern and any JMH option after it; with
neither it runs every benchmark there is, which takes hours. See [Benchmark the
engine](docs/how-to/benchmark-the-engine.md) for the scenarios it covers.

Both image tasks package the application first and copy the result in, so the
JDK the image runs on is the one in `mise.toml`. `mise run image` builds for
the machine it runs on; `mise run image:amd64` builds for x86-64 whatever that
machine is, which is what a host of a different architecture needs. Either
reads the same configuration as a node run from source, except that
`LOCAL_STORAGE_DIRECTORY` already points at `/data`:

```shell
docker run --rm -p 8080:8080 \
  -e REMOTE_STORAGE_URL=http://host.docker.internal:9000 \
  -e REMOTE_STORAGE_ACCESS_KEY=exofind \
  -e REMOTE_STORAGE_SECRET_KEY=exofind123 \
  -e REMOTE_STORAGE_BUCKET=exofind \
  -e EXOFIND_AUTH_ROOT_KEY=dev-root-key \
  exofind/engine:dev
```

Unlike dev mode this checks credentials, so requests carry
`Authorization: Bearer dev-root-key` until you create keys with it.

The image sizes the heap against the container's memory limit and starts the
JVM with what Lucene wants from it. `JAVA_OPTS_APPEND` changes one of those
without restating the others - see [The
JVM](docs/reference/configuration.md#the-jvm).

## Published images

The same image, for x86-64 and arm64, is published to
`ghcr.io/levelfourab/exofind` on every merge to `main` and on every release:

| Tag            | What it is                                              |
| -------------- | ------------------------------------------------------- |
| `0.1.0`        | One release, and the same image for as long as it exists |
| `0.1`          | The newest patch of that minor version                   |
| `0`            | The newest release that has not broken compatibility     |
| `latest`       | The newest release there is                             |
| `main-latest`  | The tip of `main`, ahead of any release                 |
| `main-a1b2c3d` | One commit on `main`                                    |

A deployment that has to come back up as the same version pins `0.1.0` or a
`main-<rev>`; the rest move under it.

## Releases

Commit messages are [Conventional
Commits](https://www.conventionalcommits.org/), and Release Please turns them
into releases: it keeps a pull request open holding the next version number and
the changelog entries the commits since the last release add up to. Merging
that pull request is the release - it writes `CHANGELOG.md`, tags the commit,
publishes the GitHub release and the images above.

Which part of the version moves is the commit types deciding: `fix` a patch,
`feat` a minor, and a `!` or a `BREAKING CHANGE:` footer a minor as well for as
long as the major is 0. The version in `pom.xml` is written by the same pull
request and carries `-SNAPSHOT` between releases, so the version a build
reports is the release it came after.
