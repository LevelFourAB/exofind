# Website

This directory contains the website source code. The website publishes the
documentation in [`docs/`](../docs/README.md) and demo pages that search a
node. The site is an [Astro](https://astro.build) project using
[Starlight](https://starlight.astro.build) for documentation, deployed to
GitHub Pages by `.github/workflows/pages.yml`.

## The documentation is not kept here

The documentation files reside in `docs/`, and the website project reads them
directly from their location. This keeps the files as plain Markdown with
relative links so they remain readable on GitHub.

Three components handle documentation loading:

- [`src/content/loader.mjs`](src/content/loader.mjs) reads the Markdown files
  and extracts each page title from its opening `# H1` heading.
- [`src/plugins/remark-docs.mjs`](src/plugins/remark-docs.mjs) removes the
  opening heading because the layout renders the title directly. It also
  rewrites relative links, such as `../reference/errors.md#codes`, to their
  published URLs. The build reports missing link targets.
- [`src/sidebar.mjs`](src/sidebar.mjs) generates the sidebar navigation from
  [`docs/README.md`](../docs/README.md). Adding a document to `docs/README.md`
  includes it in the sidebar. Each `##` heading becomes a sidebar group, and a
  `###` heading under it becomes a group nested inside that one, which is how
  a long section such as the how-to guides is divided. The sections named in
  `FLATTENED` are shown as their contents instead of as a group: the tutorials
  become top-level links, and the sub-sections of the how-to guides become the
  groups. The sections named in `CLOSED` arrive closed, and every other group
  arrives open. A closed group opens itself on a page inside it and stays open,
  and Starlight remembers whichever groups the reader works themselves.
- [`src/parts.mjs`](src/parts.mjs) reads the same file for which documents each
  `##` section holds, what divides them, and the paragraph the section opens
  with. `astro.config.mjs` defines the result into the bundle, because a
  rendered page cannot read the repository.

To add a new document, create a file in `docs/` and add a link to
`docs/README.md`. You do not need to edit files in this directory.

### The landing page of a part

[`src/pages/[part].astro`](src/pages/%5Bpart%5D.astro) serves a page for each
`##` section: `/how-to/`, `/reference/`, and one for every other section whose
documents are in a directory of their own. A sidebar group is navigation and
not a page, so without this those paths answer with nothing, and the header and
the footer have to link into the first document of a section instead.

The page leads with the paragraph that follows the `##` heading in
`docs/README.md`, and lists the documents under the `###` headings that divide
them, each with the sentence the index describes it with. Only the first
paragraph under the heading is taken, so a section can say something to a
reader of the index alone by writing a second one - the `Reference` section
points at `/api/` that way. [`DocList.astro`](src/components/DocList.astro)
draws the rows.

A section the index does not divide gets one heading, `All pages`, over the
whole list. Every part is then the same page with a different amount in it:
a heading starts each list, and the on-this-page panel has something to hold.
The demos page is built by hand to the same shape - a paragraph, an `All demos`
heading, then the rows - so that a reader who arrives from the header meets
one kind of index page.

Where a section lists documents from more than one directory there is no path
to serve the page at, so the section gets none and is entered at its first
document. Nothing fails; `directoryOf` in [`src/sidebar.mjs`](src/sidebar.mjs)
decides it.

These pages are not in the sidebar, and the site's own search does not find
them: [`search/documents.mjs`](search/documents.mjs) reads Markdown, and this
page is markup around what `docs/README.md` already says.

## Diagrams

A document draws a diagram by writing it as a `d2` code block, in the
[D2](https://d2lang.com) language:

````markdown
```d2 title="A node claims an index"
direction: right

leadership table -> node: grants the claim
node -> manifest: writes under its epoch
```
````

[`astro-d2`](https://astro-d2.vercel.app) replaces the block with the picture
it draws, so nothing else has to be added to the page. The words after `d2`
are the attributes of that one diagram: `title` is the alternative text and
should say what the diagram shows, `width` sets the width in pixels,
`sketch=true` draws it as if by hand, `layout` picks a different layout engine,
`animateInterval` draws a diagram written in several boards as one picture that
runs through them, and `src=./file.d2` takes the source from a file beside the
document instead of from the block. The
[attributes page](https://astro-d2.vercel.app/configuration/attributes/) lists
them all.

D2 draws the pictures, and `mise.toml` installs it with Node and pnpm. The
build fails where it is missing rather than publishing a page without its
diagram. The options every diagram is drawn with are in
[`astro.config.mjs`](astro.config.mjs).

Six things about the section are worth knowing before changing it:

- **An animated diagram is laid out once per frame.** `animateInterval` draws
  the boards of a `steps`, `scenarios` or `layers` block as one picture that
  runs through them, and D2 lays each board out on its own. The frames stay
  still only where every board holds the same shapes and the same connections,
  and a board changes what a shape is drawn with - `opacity`, `stroke`,
  `stroke-dash`, an arrowhead. A board that adds a shape, or that gives a label
  a different length, moves everything around it, and the picture jumps from
  frame to frame with nothing to report it. Give a caption that has to change a
  `width` of its own, and carry the wording of a connection in that caption
  rather than on the connection. The diagram in
  [`docs/explanation/architecture.md`](../docs/explanation/architecture.md) is
  written this way.
- **A diagram is a file, written at build time.** Each one becomes
  `public/images/diagrams/<page>-<n>.svg`, where `<page>` is the path of the
  page and `<n>` counts the diagrams on it from zero. The directory is rebuilt
  by every build and is not checked in.
- **A diagram is drawn in the grey the prose is, on no paper of its own.** Both
  are put in front of every block by `remarkDiagramStyle` in
  [`src/plugins/remark-diagrams.mjs`](src/plugins/remark-diagrams.mjs), which
  is where the palette is. It is the grey ramp of
  [`src/styles/site.css`](src/styles/site.css) copied out, because D2 is handed
  colours as text and reads no stylesheet - a change to the palette is made in
  both files. Both are deliberate. A page spends its one accent on the links,
  so a picture that argues in blue argues with them. And the ramp starts at the
  grey a paragraph is set in rather than at the grey a heading is set in, so a
  drawing carries no more weight on the page than the sentence that introduces
  it. A block that
  states a colour of its own still wins, because the preamble goes in front of
  what the block says. A diagram taken from a file with `src=` is read by D2
  itself and gets none of this, so state the colours in that file where one is
  used.
- **The paths are decided by `remarkDiagramPaths`** in the same file, because a
  file in `docs/` is outside this project and `astro-d2` would otherwise write
  the picture outside `public/`. Both plugins have to stay ahead of `astro-d2`
  in the list of remark plugins, which is what the comment in
  `astro.config.mjs` guards.
- **A diagram follows the reader's system setting rather than the site's
  theme.** D2 writes both a light and a dark version into the one file and
  chooses between them with `prefers-color-scheme`, and the picture is loaded
  as an image, where the theme the reader picked in the header does not reach
  it. A reader on Auto sees the theme they expect; one who picked Light on a
  dark desktop gets a dark diagram in a light page.
- **The source of a diagram is not indexed.** `d2` is the one fenced block
  [`search/documents.mjs`](search/documents.mjs) drops rather than keeps, the
  way it drops the alternative text of an image. Say in the prose what the
  diagram shows, or the site cannot find the page by it.

## The pages written for the site alone

`src/content/pages/` is a second root the same loader reads. It holds prose
that belongs to the site rather than to the manual, and that is too long to be
markup in an Astro page. A subdirectory there is the path the pages are served
under, the way a subdirectory of `docs/` is:
`src/content/pages/compare/algolia.md` is `/compare/algolia/`.

Today that is the comparison pages, one per search engine Exofind is weighed
against. They address someone who has not decided to read the manual, so they
are in no sidebar and belong to no part of it. A column in the footer of every
page is where a reader meets them - see
[`src/compare.mjs`](src/compare.mjs), which reads the directory and states the
order they are offered in. A page the order does not name, or a name with no
page, fails the build.

The title and the summary of a comparison are read from the file: the `# H1`
and the paragraph under it. That paragraph is the footer label's neighbour in
three places at once - the page description, the link preview and the search
result - so write it as one sentence that says what the comparison finds.

The `At a glance` table on each page is written to the same shape, because a
reader who opens two of these pages reads the second one against the first.
Exofind is the middle column on every page, in the order the title states it,
and the rows are `License`, `Where the data lives`, `How nodes coordinate`,
`Scaling`, `Language analysis`, `Schema changes` and `Hosting`, in that order.
A page adds a row where the difference is real and the others have nothing to
say about it - `Query interface` on the Manticore Search page is the one there
is. The corner cell is left empty: the rows are licences, architecture and
operations together, and every word that covers all of them is wrong about
some of them. Nothing checks any of this.

Two things follow from these pages being outside `docs/`:

- `astro.config.mjs` names both roots in `processedDirs`. A root left out gets
  no heading anchors, and a search hit lands on the top of the page.
- [`search/documents.mjs`](search/documents.mjs) reads both roots as well, and
  labels a hit from this one by the directory it is in. A root it does not read
  is a page the site publishes and cannot find.

## The REST API pages

`/api/` holds an overview, a page per endpoint and a page per shared type,
generated by [`src/openapi/`](src/openapi/) from
[`public/openapi.yaml`](public/openapi.yaml). The engine build writes that
document, and `mise run site:openapi` copies it here. It is checked in because
the deployment builds no Java; a stale copy is a site that builds and publishes
the wrong API.

Serving it from `public/` publishes it at `/openapi.yaml` as well, which is
what [`docs/how-to/generate-a-client.md`](../docs/how-to/generate-a-client.md)
sends a reader to.

These pages state what one endpoint accepts and returns. They do not replace
`docs/reference/`, which explains the parts of the API that are not one
endpoint - a clause, a facet, a cursor - and holds the anchors the descriptions
in the document link to. The prose in those descriptions is written in the Java
sources; see [`tools/api-descriptions/`](../tools/api-descriptions/README.md).

### How the pages are built

Six modules read the document and five components draw it. Each states its own
job at the top of the file; what follows is how they fit together.

- [`src/openapi/spec.mjs`](src/openapi/spec.mjs) reads the document and flattens
  it into operations and tag sections. Everything else reads it from there.
- [`src/openapi/types.mjs`](src/openapi/types.mjs) picks the types that get a
  page of their own, and answers which endpoints can hold each.
- [`src/openapi/schema.mjs`](src/openapi/schema.mjs) turns a schema into the
  rows of a table, and [`src/openapi/example.mjs`](src/openapi/example.mjs)
  turns one into a body to show.
- [`src/openapi/snippet.mjs`](src/openapi/snippet.mjs) writes the call in curl,
  JavaScript, Java and Go. Add a language, or swap the four for calls through a
  client library, here alone.
- [`src/openapi/sidebar.mjs`](src/openapi/sidebar.mjs) builds the sidebar group,
  and [`src/openapi/markdown.mjs`](src/openapi/markdown.mjs) renders the prose
  the document carries.
- [`src/components/api/`](src/components/api/) draws an endpoint:
  `Operation.astro` is the page, `Fields.astro` the rows and everything under
  them, `Schema.astro` a body, and `Panels.astro` the column beside it.
  `Type.astro` is the page of one type, drawn from the same rows.

An answer also states the error codes it carries. The engine writes them from
the `@ReturnsError` annotations on the endpoint into `x-error-codes` on the
answer, and `spec.mjs` reads them into `codes` on a response. `Operation.astro`
draws one row per code and drops the closing `Error codes:` paragraph the engine
adds to the description, because that paragraph exists for a generated client
rather than for this page. `ErrorCodeFilter` is the other half of that agreement.

Five things about the section are worth knowing before changing it:

- **A `$ref` is never followed to the end.** Sixteen schemas in this document
  reach themselves - a clause holds clauses - so a generator that expands every
  reference does not finish. `schema.mjs` carries the chain of type names a row
  is under and names a type that is already open above it instead of expanding
  it again. That is also what puts the type names on the page.
- **Only some types get a page, and the rule is one rule.** A type gets one when
  it is a tagged union, when it reaches itself, or when more than one parent
  points at it - the three ways a type ends up written out repeatedly or written
  out nowhere. The other schemas are stated on the endpoint that takes them, and
  their names stay text rather than links. `types.mjs` states the rule and holds
  the reasoning; it reads a document handed to it and imports nothing from
  `spec.mjs`, because [`search/documents.mjs`](search/documents.mjs) applies the
  same rule under plain Node, where the `?raw` import of the YAML does not work.
- **The document is imported, not read from disk.** A page is rendered from a
  bundle, where a path relative to a source file leads nowhere, so `spec.mjs`
  imports the YAML as text with `?raw`. That import is also what makes a
  refreshed document reload the dev server.
- **An endpoint page is as wide as a page of the manual plus the column its
  on-this-page list sits in**, which is the room the call is laid out in. The
  width is in [`src/styles/site.css`](src/styles/site.css) and the division of
  it is in `Operation.astro`; both are written against the rule that sets the
  width of every other page, so changing one of the three means reading all
  three.
- **A type page keeps the width of a page of the manual**, because it has an
  on-this-page list and an endpoint page does not. The rule that widens an
  endpoint is written against the `.api` class `Operation.astro` puts on the
  page, so nothing has to be said here for a type page to keep the ordinary
  layout - but a rule written against the URL instead would take that list's
  column away from it.
- **The sidebar group is built from the document**, not from `docs/README.md`,
  so the label in `sidebar.mjs` and the name in `EXTRA` in
  [`src/nav.mjs`](src/nav.mjs) have to agree. A name in `EXTRA` that matches no
  group fails the build. The section arrives closed and the tag groups inside it
  open: a closed tag is a second door between a reader and the endpoint they
  came for.

[`search/documents.mjs`](search/documents.mjs) indexes these pages from the same
document rather than from the built site, so it spells the URL of an endpoint
and of a type itself. A change to where a page is served is a change in both
files.

## What the site publishes for a crawler

`@astrojs/sitemap` writes `/sitemap-index.xml` and the sitemap it names from
every route the build produced, so a page is listed whether or not another page
links to it. It is configured in [`astro.config.mjs`](astro.config.mjs) with the
filter the link previews use, which keeps the routes that are not pages -
`llms.txt`, the Markdown copy of every document - out of it.

[`public/robots.txt`](public/robots.txt) points at the index. It states the
origin in full, as the sitemap convention asks for an absolute URL, so a change
to `SITE` in [`src/site.mjs`](src/site.mjs) is a change in that file too.

## What the site publishes for a machine reader

An agent asked to write something against a node arrives with no sidebar and no
search, and a rendered page gives it a layout to read around. Three files
answer that, and all three are generated:

- `/llms.txt` lists the manual, section by section, with the sentence
  `docs/README.md` describes each document with. It is built by
  [`src/pages/llms.txt.ts`](src/pages/llms.txt.ts) from the same index the
  sidebar and the header come from, so a document added to `docs/README.md`
  is listed there too. The order is not the sidebar's: the OpenAPI document
  and the full text come first, and the sections on running and operating
  nodes and the explanations close the file under `Optional`, the heading the
  llms.txt convention names for what a reader short of room can skip. A
  section moves in or out of that group by its label in that file.
- `/<doc>.md` serves a page as the Markdown it was written as, from
  [`src/pages/[...doc].md.ts`](src/pages/%5B...doc%5D.md.ts). These URLs mirror
  the layout of `docs/`, so a relative link between two documents resolves to
  the file it names. A link out of `docs/` does not: the rewriting in
  `src/plugins/remark-docs.mjs` runs when a page is rendered, and this route
  serves the source.
- `/llms-full.txt` holds the pages a client author needs, concatenated: the
  guides for the task first, then the reference, so that a reader that caps
  what it fetches keeps the guides. The list is in
  [`src/pages/llms-full.txt.ts`](src/pages/llms-full.txt.ts), and it is a
  subset because the manual is around 83,000 words and most of it answers
  questions a client author does not have. A slug in that list that is no
  longer a document fails the build.

What every generated file opens with - what Exofind is, the handful of facts a
caller needs before the first request, and the three requests from the
getting-started tutorial - is in [`src/llms.mjs`](src/llms.mjs). Those facts
are stated on the pages as well, and this is the one copy written for a reader
that fetches no page. The requests are a copy of the tutorial's, so a change
to what the tutorial sends is made in both.

## The header

Starlight does not include top navigation. The front page and demo pages omit
the sidebar, which hides documentation links from visitors on those pages.

[`src/components/Header.astro`](src/components/Header.astro) replaces the
default header to provide section links:

- [`src/nav.mjs`](src/nav.mjs) lists the parts of the manual, whatever shape
  the sidebar shows them in, and then the demo pages. Each section links to its
  landing page, and a section that has none links to its first page. Adding a
  section to `docs/README.md` updates both the sidebar and the header. The same
  file supplies the label over a page title, which names every part rather than
  only the ones the header carries.
- A landing page is in no sidebar, so `sectionsOf` also takes the path of the
  page being rendered: that is what marks the section a reader is on the
  landing page of. The label over a page title is left off there, because the
  title of the page is the name of the part.
- `src/nav.mjs` excludes the tutorials from the header because the front page
  links to them directly. If `nav.mjs` references a section that does not
  exist, the build fails.

The custom header also places search alongside the theme toggle and links.
By default, Starlight aligns search with the prose column, but the front page
and demo pages do not use a prose column. On narrow viewports where the sidebar
is hidden, the header replaces section links with a mobile menu while retaining
the title and search.

## The look

[`src/styles/site.css`](src/styles/site.css) holds the theme, and it is nearly
all of it. Astro applies it before Starlight declares its cascade layers, so
every rule in that file outranks the component it restyles no matter how
specific either one is. That is what lets one stylesheet do the work of three
dozen component overrides, and it is why a rule that stops working after a
Starlight upgrade is usually a renamed class rather than a specificity problem.

Six components are replaced for markup that CSS cannot reach:

- [`ThemeSelect.astro`](src/components/ThemeSelect.astro) shows Auto, Light and
  Dark as three cells rather than as a dropdown. It reads and writes the same
  `starlight-theme` entry Starlight's own control uses, so the inline script
  that settles the theme before the first paint keeps working.
- [`PageTitle.astro`](src/components/PageTitle.astro) labels a title with the
  part of the manual the page is in, which `src/nav.mjs` works out from the
  sidebar.
- [`Hero.astro`](src/components/Hero.astro) lays the front page hero out on the
  same line the documentation starts on, and labels it with the site name.
- [`DemoList.astro`](src/components/DemoList.astro) is the shared list of demos
  for the front page and the catalogue.
- [`DocList.astro`](src/components/DocList.astro) lists the documents of a part
  of the manual on its landing page, in the same rows the demos are listed in.
- [`Footer.astro`](src/components/Footer.astro) writes three columns of links
  and a line naming who publishes the engine under Starlight's own footer. It
  is on every page, a demo and an endpoint page included. The parts of the
  manual come from `docs/README.md` through `parts()` in
  [`src/nav.mjs`](src/nav.mjs), which reads the index rather than the sidebar
  because a demo page has no sidebar; the comparisons come from
  [`src/compare.mjs`](src/compare.mjs); and the name and the address come from
  `COMPANY` in [`src/site.mjs`](src/site.mjs), which the front page reads as
  well.

Two things about it are worth knowing before changing it:

- Section headings are numbered with a CSS counter, and the on-this-page list
  repeats the numbers. There is nothing to write in the Markdown. The endpoint
  pages are numbered too, and their headings arrive in shapes a written page has
  none of, so each is named in the rule that draws them; the comment over the
  rule says which and why.
- Everything a page says starts on one line, and the column to the left of it
  holds the section numerals and the ends of the rules under them. Blocks that
  start wide - a heading, an aside, prev/next, the colophon - do it with a
  negative margin and push their own text back with padding. Write those
  selectors with the child combinator: a descendant selector also finds the
  labels nested inside an aside or the pager and drags them out of their boxes.

Editing this file is the one change the dev server does not pick up. Starlight
loads it through a virtual module that Vite does not invalidate, so a change
shows up only after `mise run site` is restarted, or in `mise run site:build`.

## Line breaks in page markup

Astro compresses the HTML it builds. A line break inside a run of text becomes
a single space, but a line break between text and an inline element is removed,
and the word runs into the element:

```astro
<!-- Publishes "called<strong>gravlaxsås</strong>". -->
Swedish glues words together, so a sauce is called
<strong>gravlaxsås</strong>.

<!-- Publishes "called <strong>gravlaxsås</strong>". -->
Swedish glues words together, so a sauce is
called <strong>gravlaxsås</strong>.
```

Keep an inline element such as `<a>`, `<strong>`, or `<code>` on the same line
as the word before it and the word after it, and wrap the paragraph at a break
between two words. Nothing checks this, and the markup reads correctly in the
source, so the missing space shows up only on the published page.

Markdown under `docs/` is not affected. Those pages are rendered to HTML
strings before Astro sees them.

## Navigation

[`src/components/Head.astro`](src/components/Head.astro) replaces the default
head to add Astro's client router. Without it every link is a new document, and
the browser shows a blank page while it lays the next one out. The router swaps
the body instead, so the frame stays where it is.

The demo pages are left out of it. A demo sets its interface up when its module
is evaluated, and a module is evaluated once per document, so a demo routed to
a second time would never be wired up. A page the router finds no marker in is
navigated to the old way, which is why leaving the tag off those pages is the
whole opt-out. A new page that sets itself up outside a custom element belongs
in that exclusion too.

[`src/transitions.js`](src/transitions.js) carries across what the swap would
otherwise drop: the theme the reader picked, which a built document does not
know, and the sidebar's scroll position and open groups. The search is kept by
`transition:persist` in the header, because Starlight mounts it once per
document. Everything else Starlight ships is a custom element, and those are
built again from the markup that arrives.

## The demo pages

Each demo consists of two parts under the same name:

- The dataset in `examples/<name>/` in the repository, containing the index
  definition, documents, and loading script.
- The web page implementation in this directory:
  - [`src/examples/demos.mjs`](src/examples/demos.mjs) lists the available demos
    used to build the catalogue, front page, and sidebar.
  - `src/examples/<name>/main.js` implements the demo using plain JavaScript
    modules without a framework, built on `src/examples/shared/` for client
    requests, URL handling, controls, and UI styles.
  - `src/pages/examples/<name>.astro` defines the markup wrapped in
    [`src/layouts/Example.astro`](src/layouts/Example.astro).

The front page features a specific demo rather than the catalogue, requesting
it by name through `demo()`. If that demo is renamed or removed, the build
fails instead of leaving a broken link.

Demo pages use the Starlight layout, including the header, search, theme toggle,
and footer. They omit the documentation sidebar to provide space for facet
filters and search results. Readers can navigate back to documentation using
the links under the heading.

Demo pages require two conventions that the build does not check automatically:

- Wrap the interface in `<div class="demo not-content">`. The `demo` class
  anchors shared styles, and the `not-content` class prevents Starlight from
  applying prose styles to UI components.
- Import `shared/exofind.css` before the demo stylesheet in the page component
  rather than in the layout. Because the bundler can inline one stylesheet and
  link another, import order determines CSS precedence.
- Put the facet groups inside
  [`shared/Filters.astro`](src/examples/shared/Filters.astro), one group per
  `<div class="filters__group">`. The component folds the facets behind a single
  line on narrow viewports, and counts a group as filtering when it holds a
  ticked box, a range bucket other than `Any`, or a pressed button. A page that
  writes its own `<aside>` instead stacks the whole facet column above the first
  result on a phone.
- Say what the page is waiting for with
  [`shared/waiting.js`](src/examples/shared/waiting.js). Creating it draws the
  shape of an answer where the facets and the results will be, so the page
  opens as a search interface rather than as a column of empty headings, and
  `searching()`, `done()` and `failed()` in the demo's own `run` turn the
  spinner in the search field on and off. A demo that leaves them out opens
  empty and says nothing while a slow search runs.

## Which node the demos search

Demos send search queries to the URL specified by `PUBLIC_EXOFIND_NODE` during
the build. When this variable is unset, the build defaults to
`http://localhost:8080`. Visitors or URL parameters cannot change this target
at runtime. Each demo searches an index named after the demo.

The deployment workflow reads this value from the `EXOFIND_DEMO_NODE`
repository variable. For node requirements, including preloaded datasets,
disabling the indexer role, and setting `QUARKUS_HTTP_CORS_ORIGINS`, see
[Running a public demo node](../docs/how-to/run-a-demo-node.md).

## The search box

The site searches itself with a node, on the index named `docs` and against the
same `PUBLIC_EXOFIND_NODE` the demos search. `src/components/Search.astro`
replaces Starlight's search component, and `src/components/search.js` builds the
requests and draws the results.

A hit is a section of a page rather than a page. What builds those documents,
what the index definition tunes, and how to load it into a node is
[`search/README.md`](search/README.md). Load it with `mise run site:index`.

Before anything is typed, the dialog offers the reader's five most recent
searches over a short list of starting pages. A search is recorded once the
reader follows one of its results, and it is kept in that browser's
`localStorage` and read by nothing else. The starting pages are named by slug
in `STARTING_POINTS` in `src/components/Search.astro` and resolved against
`docs/README.md`, so a page that is renamed or dropped fails the build rather
than leaving a gap. Their labels come from the index too.

Pagefind stays enabled. It is what the dialog falls back to when no node
answers, and Starlight builds it during `astro build` whether or not it is what
the site searches with. A dev server builds no Pagefind index, so a node that
does not answer is reported there instead.

## Where the site is served from

`src/site.mjs` defines the site origin and base path, and nothing else decides
either. GitHub Pages serves the site from the custom domain `exofind.dev`, so
the base path is empty and absolute URLs start at the root.

The base path is a separate constant from the origin because links are built in
three places. Astro applies the base to the links it generates, page components
read `import.meta.env.BASE_URL` for the URLs they write themselves, and the
remark plugin imports `BASE` directly, because it rewrites Markdown links before
Astro sees them. Serving the site under a path again is a change to `BASE`
alone.

## Link preview images

Every page gets a PNG image that a link to it unfurls into, written next to the
page as `index.png`. `astro-opengraph-images` draws it after the pages are
built: it reads the Open Graph tags out of each built page, hands them to
`render` in `src/opengraph.mjs`, and writes the result.

Starlight fills in the other Open Graph tags. It names no image, so
`src/route-data.mjs` adds `og:image` and the tags that go with it. The path
comes from the `getImagePath` the integration exports, and the integration
compares that path against the file it wrote. A build fails where the two
disagree.

The image is drawn by Satori, which reads TrueType, OpenType, and WOFF fonts.
It cannot read WOFF2, so `src/opengraph.mjs` loads the static WOFF files of
Archivo and IBM Plex Mono. The site itself loads the variable Archivo, which
ships as WOFF2 alone. The colours are the dark half of the palette in
`src/styles/site.css`, copied rather than imported, because a preview card is
shown against a conversation and follows no theme.

The images are written at the end of a build, so `mise run site` serves pages
whose `og:image` points at a file the dev server does not have. Use
`mise run site:build` to see one.

To change what a card says, edit `render`. To change the size, edit
`PREVIEW_WIDTH` and `PREVIEW_HEIGHT` in `src/site.mjs`, which both the drawing
and the tags read.

## Commands

Use these `mise` tasks to run, build, and preview the site:

```shell
mise run site            # documentation and demos, on localhost:4321
mise run site:index      # loads the documentation into a node, for the search box
mise run site:build      # writes website/dist
mise run site:preview    # serves what was built, under the same path as the deployment
```
