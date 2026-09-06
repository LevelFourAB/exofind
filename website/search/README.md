# The site's own search

The site is searched by a node running the engine the site documents. This
directory holds what puts the site into an index; the dialog that searches it
is [`../src/components/Search.astro`](../src/components/Search.astro) and
[`../src/components/search.js`](../src/components/search.js).

| File | What it is |
|------|------------|
| [`definition.json`](definition.json) | The index definition. |
| [`documents.mjs`](documents.mjs) | Turns the site into documents. |
| [`load.mjs`](load.mjs) | Puts those documents into a node. |

## Loading it

Run a node with the indexer role, then:

```shell
mise run site:index                    # or: node search/load.mjs
```

Point it at another node with `NODE`, and give that node the credential it
wants with `KEY`:

```shell
NODE=https://indexer.example.com KEY=exok_… mise run site:index
```

The key needs `indexes.write`, `documents.write` and `indexes.commit` over the
index, which is named `docs` unless `INDEX` says otherwise. A node that checks
no credentials, such as one in development mode, wants none at all.

To see what would be sent without sending it, use `--dry-run`, which writes the
documents to standard output as newline-delimited JSON.

The demo node the published pages search also answers this index, so its
anonymous key needs `search` over `docs` as well. See
[Running a public demo node](../../docs/how-to/run-a-demo-node.md).

## A document is a section, not a page

A page becomes one document per `##` or `###` heading, plus one for the text
above the first heading. A reader searching a manual is looking for the
paragraph that answers them rather than for the page it is on, and a section is
the smallest piece the site can link to: Starlight gives every heading an
anchor, and `documents.mjs` derives the same anchor from the same heading text
with the same slugger, so a hit lands on the paragraph rather than on the top
of the page.

The Markdown is read from `docs/` rather than from the built site, so indexing
needs no site build. Both agree because both derive the same things from the
same files. Two other sources are indexed as one document each: an endpoint
page, from the OpenAPI document under [`../public/`](../public/openapi.yaml),
and a demo page, from the list in
[`../src/examples/demos.mjs`](../src/examples/demos.mjs).

## What is tuned, and why

The title of the page, the heading of the section and its text are three fields
searched as one clause, weighted 8, 5 and 1. A search matches any of the words
rather than all of them, and a section holding more of them ranks higher: a
manual is searched by describing a problem, and requiring every word answers a
question with nothing.

The words that make a question are dropped by a stopword list in the
definition, on top of the ones the locale drops. The stopwords of a locale are
built for prose, so they drop `is` and `a` and keep `how` and `what` - which
would leave `what is a facet` answered by whichever page happens to have `what`
in its title. Only words that are a question, or the person asking it, are
listed. A word a reader could be looking for is left in however common it is.

The lead of a page is boosted, because a page is often searched for by name and
the section that answers it is then the top of the page rather than whichever
of its sections says the word most often.

## What a hit shows

A hit shows the highlighted fragment the search cut from the text, and the
stored excerpt when the search highlighted nothing. Both are shaped for a
manual full of tables and requests:

- A table row ends as a sentence. A search cuts a fragment on a sentence, so a
  table joined by spaces alone gives a fragment thousands of characters long.
  A stop only ends a sentence when an upper-case word follows it, so a table
  whose rows open with a language tag or a status code is still one sentence.
- The excerpt is cut from the prose of a section, without its tables and fenced
  blocks. The first 180 characters of a `curl` command say only that the
  section holds a request. The blocks stay in the text a search reads, so a
  setting name inside one still finds and highlights its section.
- The dialog cuts a snippet again before drawing it. See `clamped` in
  [`../src/components/search.js`](../src/components/search.js).

## Replacing what an earlier load wrote

Every document carries the stamp of the load that wrote it. Once the new
documents are committed, `load.mjs` deletes whatever still carries an older
stamp, which is how a renamed heading or a deleted page leaves the index. It is
done in that order rather than by emptying the index first so that a live node
can be reloaded: the index answers with the previous build until the moment it
answers with this one.

## When a node does not answer

The dialog falls back to Pagefind, the static index Starlight builds from the
rendered pages. It is part of the deployment and needs nothing running, and
Starlight builds it on every `astro build` whether or not the site searches
with it. Its hits are pages rather than sections and it does not forgive a
typo, so the dialog says when the answer came from it.

A development server builds no such index. There, a node that is not answering
is reported rather than worked around.
