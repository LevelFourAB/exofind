/*
 * What the site publishes for a machine reader.
 *
 * An agent asked to write something against a node arrives with no sidebar and
 * no search, and fetching a documentation page gives it a layout to read
 * around. Three files answer that: `/llms.txt` lists the manual, `/<doc>.md`
 * serves a page as the Markdown it was written as, and `/llms-full.txt` holds
 * the pages a client author needs in one fetch. The endpoints that build them
 * are in `./pages/`.
 *
 * The listing is generated from `docs/README.md`, so a page added to the
 * manual is in it - see `./sidebar.mjs`. The facts below are the ones a caller
 * needs before the first request and that no single page is the place for;
 * they are stated once here because both files carry them. The three requests
 * in it are the ones from the getting-started tutorial, kept in step by hand:
 * they are here so that a reader of `/llms.txt` alone can make a first call
 * without fetching a page, and a reader of a capped fetch of `/llms-full.txt`
 * has them before whatever the cap cut off.
 */

import { SITE, withBase } from './site.mjs';

/** Absolute URL of a path on the site, which is how these files link. */
export function siteUrl(path) {
	return new URL(withBase(path), SITE).href;
}

/** The published page of a document. */
export function pageUrl(slug) {
	return siteUrl(`/${slug}/`);
}

/** The Markdown source of a document, served by `./pages/[...doc].md.ts`. */
export function sourceUrl(slug) {
	return siteUrl(`/${slug}.md`);
}

/** The OpenAPI document, which the engine build writes. */
export const OPENAPI = siteUrl('/openapi.yaml');

/** The pages a client author needs, in one file. */
export const FULL = siteUrl('/llms-full.txt');

/**
 * The opening of both generated files: what Exofind is, and what a caller has
 * to know before the first request.
 */
export const PREAMBLE = `# Exofind

> An experimental search engine that keeps its indexes in S3-compatible object
> storage. Nodes hold local copies of the indexes they serve, one node writes
> to an index at a time, and every node answers searches from its own copy.

Before you call a node:

- The HTTP API is served under the path prefix \`/v1alpha1\`. The API is
  experimental and changes without keeping backward compatibility.
- Requests and responses use \`application/json\`. The documents endpoints also
  accept and return \`application/x-ndjson\`, one document per line.
- A key is sent as \`Authorization: Bearer <key>\`. Absent, malformed, unknown,
  and lapsed keys all answer \`401\`. An index a key has no permission on
  answers \`404\` with the code \`index:not-found\`.
- An index name in a path is either the name, such as \`books\`, which serves
  from the active generation, or one generation, such as \`books@2\`.
- \`GET /q/health/ready\` reports whether a node is ready. It is outside the
  versioned API and takes no key.
- Generate a client from the OpenAPI document at
  ${OPENAPI}, and read the pages below for what it cannot state.

The three requests most clients make. A definition names every field and what
it is for, documents are sent in batches under their primary key, and a search
is a list of clauses that a hit satisfies together:

\`\`\`
PUT /v1alpha1/admin/indexes/books
{
  "fields": {
    "id": { "type": "string", "primaryKey": true, "required": true },
    "title": { "type": "string", "matching": {}, "sort": {} },
    "published": { "type": "boolean", "filter": {} }
  }
}

POST /v1alpha1/indexes/books/documents
{ "documents": [ { "id": "1", "title": "Silent Spring", "published": true } ] }

POST /v1alpha1/indexes/books/search
{
  "query": [
    { "type": "text", "text": "spring" },
    { "field": "published", "match": { "value": true } }
  ]
}
\`\`\`

What to expect from them:

- A search answers \`hits\`, each with \`id\`, \`score\` and \`document\`, and
  \`total\` with \`count\` and \`exact\`. Sending a document under an existing
  key replaces the document.
- A field is usable only in the ways its definition enables. A \`field\` clause
  on a field without \`filter\`, or a sort on one without \`sort\`, answers
  \`400\` with the code \`index:query:usage_not_enabled\`.
- A write is not searchable until the writer commits, which happens on its own
  within 5 seconds by default, or at once after
  \`POST /v1alpha1/admin/indexes/books/actions/commit\`. Every other node
  serves it after pulling the commit, within a further 30 seconds by default.
  A search that misses a document just indexed is this, not a lost write.
`;
