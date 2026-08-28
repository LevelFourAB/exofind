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
  a long section such as the how-to guides is divided.

To add a new document, create a file in `docs/` and add a link to
`docs/README.md`. You do not need to edit files in this directory.

## The header

Starlight does not include top navigation. The front page and demo pages omit
the sidebar, which hides documentation links from visitors on those pages.

[`src/components/Header.astro`](src/components/Header.astro) replaces the
default header to provide section links:

- [`src/nav.mjs`](src/nav.mjs) reads documentation sections from the sidebar
  definition. Each section links to its first page. Adding a section to
  `docs/README.md` updates both the sidebar and the header.
- `src/nav.mjs` excludes the tutorial from the header because the front page
  links to it directly. If `nav.mjs` references a section that does not exist,
  the build fails.

The custom header also places search alongside the theme toggle and links.
By default, Starlight aligns search with the prose column, but the front page
and demo pages do not use a prose column. On narrow viewports where the sidebar
is hidden, the header replaces section links with a mobile menu while retaining
the title and search.

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

## Which node the demos search

Demos send search queries to the URL specified by `PUBLIC_EXOFIND_NODE` during
the build. When this variable is unset, the build defaults to
`http://localhost:8080`. Visitors or URL parameters cannot change this target
at runtime. Each demo searches an index named after the demo.

The deployment workflow reads this value from the `EXOFIND_DEMO_NODE`
repository variable. For node requirements, including preloaded datasets,
disabling the indexer role, and setting `QUARKUS_HTTP_CORS_ORIGINS`, see
[Running a public demo node](../docs/how-to/run-a-demo-node.md).

## Where the site is served from

`src/site.mjs` defines the site origin and base path. GitHub Pages serves the
project site under the repository name, so absolute URLs start with `/exofind`.
Astro applies this prefix automatically to generated links. Custom URLs use
`import.meta.env.BASE_URL`, and the remark plugin uses the same constant when
rewriting Markdown links.

## Commands

Use these `mise` tasks to run, build, and preview the site:

```shell
mise run site            # documentation and demos, on localhost:4321
mise run site:build      # writes website/dist
mise run site:preview    # serves what was built, under the same path as the deployment
```
