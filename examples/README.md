# Examples

Small pages that search real data through a node, one per thing worth
showing. Each example is a directory holding everything it needs: the index
definition, the documents, a script that loads them and the page that
searches them.

| Example | Shows |
|---------|-------|
| [livsmedel](livsmedel/) | Compound splitting in Swedish, facets counted sideways of filters, range buckets, highlighting |
| [airports](airports/) | Autocomplete, typo tolerance, prefix matchers, per-field weights, distance filters and sorts |
| [cleveland](cleveland/) | Facets counted sideways of filters on a wall of thumbnails, range buckets on a year, ordering, numbered pages against cursors, highlighting |

The pages talk to a node over the search API alone and share one design and
one client, in [`shared/`](shared/) - the look, the facet and hit controls,
and the code that decides which node to search. What one example alone needs
lives beside that example.

## Running them locally

Start a node - the [getting started
tutorial](../docs/tutorials/getting-started.md) walks through it from an
empty machine - then load an example and serve the pages:

```shell
mise run example:livsmedel     # or examples/livsmedel/load.sh
mise run example:airports      # and so on, one task per example
mise run examples              # or npm install && npm run dev, from here
```

Loading is `PUT` of the definition, `POST` of the documents and a commit,
which is all the [documents API](../docs/reference/documents-api.md) asks
for. `NODE` and `INDEX` point the script somewhere else, and `KEY` is the
credential it presents on every request:

```shell
NODE=https://demo.example.com KEY=exok_... examples/livsmedel/load.sh
```

Without a `KEY` the script sends no `Authorization` header at all, which is
what dev mode and `mise run example:*` want. A node that checks credentials
wants a key granted `indexes.write`, `documents.write` and `indexes.commit`
over the index - loading writes, so the search-only key a demo node answers
readers as cannot do it.

The node being loaded into has to hold the indexer role, which takes
`INDEXER=true`; a node without it answers every write with
`index:readonly`, so loading gets no further than the definition. Searching
needs nothing of the sort - any node with a copy answers the pages.

Dev mode answers requests from any origin, so the dev server reaches the
node. A node run any other way says for itself which origins it allows, with
`QUARKUS_HTTP_CORS_ENABLED` and `QUARKUS_HTTP_CORS_ORIGINS`.

## Which node a page searches

`VITE_EXOFIND_NODE`, given to the build, and `http://localhost:8080` for a
build that was not given one. Nothing a reader or a link can change points a
page somewhere else, so a deployed page searches the node it was built for
and only that one. The index is the one the example loads into.

## What a page is searching for

The query string is the search itself - the text and whatever
has been filtered - so what is on screen can be sent to someone or survive a
reload. A page replaces the current history entry rather than adding one,
because a keystroke is not somewhere to go back to:

```
/livsmedel/?q=sås&group=Rätter&energy=100-250
```

Anything the URL asks for that the page does not offer falls back to the
default rather than being refused, so a link outlives a page that has since
changed which filters it has.

## Adding an example

A directory with an `index.html` in it is an example - the Vite config finds
them, so there is no list to keep in step. What the new directory needs:

- `definition.json`, `documents.jsonl.gz` and a `load.sh` that puts them in a
  node, plus a `prepare.py` if the documents are built from a published
  source, and an `ATTRIBUTION.md` saying where the data comes from.
- `index.html` and a `main.js` that imports `../shared/exofind.css` and
  whatever of `../shared/` it uses.
- A row in the table above, an entry on the landing page in
  [`index.html`](index.html), and a `mise run example:<name>` task.

Reach for `shared/` before writing something local, and move something local
into it once a second example wants it.

## Building and hosting

`mise run examples:build` writes the site to `examples/dist`: the landing
page at the root and each example under its own path. Asset URLs are
relative, so the same output works served from a domain root or a
subdirectory.

The build decides which node the pages search:

```shell
VITE_EXOFIND_NODE=https://search.example.com npm run build
```

On Cloudflare Pages that is the build command `npm run build`, the build
directory `examples`, the output directory `examples/dist`, and
`VITE_EXOFIND_NODE` as an environment variable.

What the deployment needs from the node it points at:

- Load each example against the indexer once, with `NODE` pointing at it and
  `KEY` holding a credential it accepts. The documents live in object storage
  from the commit onward, so every node serves them.
- Serve searches from a node that is not an indexer candidate (`INDEXER`
  unset), so a page can never write to it.
- Allow the origin the pages are served from, through
  `QUARKUS_HTTP_CORS_ENABLED` and `QUARKUS_HTTP_CORS_ORIGINS`.
- Keep the admin API off the public address. The search API is the only one
  a page uses; everything else is management and has no authentication of
  its own yet.
