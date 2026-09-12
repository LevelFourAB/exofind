// @ts-check
import { fileURLToPath } from 'node:url';

import { defineConfig } from 'astro/config';
import { unified } from '@astrojs/markdown-remark';
import sitemap from '@astrojs/sitemap';
import starlight from '@astrojs/starlight';
import astroD2 from 'astro-d2';
import openGraphImages from 'astro-opengraph-images';

import { comparisons } from './src/compare.mjs';
import { DEMOS } from './src/examples/demos.mjs';
import { apiSidebarGroup } from './src/openapi/sidebar.mjs';
import { FONTS, isPage, render } from './src/opengraph.mjs';
import { CATALOGUE, DOCS_INDEX, PARTS } from './src/parts.mjs';
import { remarkDiagramPaths, remarkDiagramStyle } from './src/plugins/remark-diagrams.mjs';
import { remarkRewriteLinks, remarkStripTitle } from './src/plugins/remark-docs.mjs';
import { sidebarFrom } from './src/sidebar.mjs';
import { BASE, PREVIEW_HEIGHT, PREVIEW_WIDTH, REPO, SITE } from './src/site.mjs';

const docsRoot = new URL('../docs/', import.meta.url);

export default defineConfig({
	site: SITE,
	// Astro wants a path where `BASE` holds a prefix to concatenate, and the
	// root is the one place the two spellings differ: `/` here, empty there.
	base: BASE || '/',

	/*
	 * The documentation lives in `docs/` and is read from the repository as
	 * well as from here, so the plugins that make a repository document into a
	 * page run over every document rather than the files being changed. What
	 * each one does is on the plugin.
	 */
	/*
	 * The parts of the manual are read from `docs/README.md` here and handed
	 * to the pages as a constant, because a rendered page is a bundle and a
	 * path relative to a source file no longer leads to the repository from
	 * one. What reads them is `./src/nav.mjs`, and the catalogue the same file
	 * yields is read by `./src/pages/llms.txt.ts`.
	 *
	 * The comparison pages arrive the same way and for the same reason. They
	 * are read from the files themselves by `./src/compare.mjs`, and the
	 * footer of every page is what lists them.
	 */
	vite: {
		define: {
			__DOCS_PARTS__: JSON.stringify(PARTS),
			__DOCS_CATALOGUE__: JSON.stringify(CATALOGUE),
			__COMPARISONS__: JSON.stringify(comparisons())
		}
	},

	/*
	 * The processor is named rather than left to Astro because an integration
	 * extends the one that is named: Expressive Code adds itself to whichever
	 * processor the configuration holds, and the loader in
	 * `./src/content/loader.mjs` renders every document through that same
	 * object. Naming `unified` is also what keeps the plugins below running,
	 * as the default processor does not take remark plugins.
	 */
	markdown: {
		processor: unified({
			remarkPlugins: [
				remarkStripTitle,
				[remarkRewriteLinks, { docsRoot: fileURLToPath(docsRoot) }],
				/*
				 * Before `astro-d2`, which adds itself to this same list when
				 * the integration below is set up, and reads what these two
				 * write.
				 */
				remarkDiagramPaths,
				remarkDiagramStyle
			]
		})
	},

	integrations: [
		starlight({
			title: 'Exofind',
			description: 'Delightful search built on object storage',
			favicon: '/favicon.svg',

			social: [
				{ icon: 'github', label: 'GitHub', href: REPO }
			],

			/*
			 * Starlight runs its own Markdown transforms - asides, heading
			 * anchor links - only on files inside its collection directory,
			 * and these files are in `docs/` instead. Naming the directory
			 * here is what lets a `:::note` in a document become an aside
			 * rather than a bare `<div>`. The loader in
			 * `./src/content/loader.mjs` tells the renderer which file it is
			 * rendering, which is the other half of the same check.
			 *
			 * Both roots the loader reads are named, or a heading on a
			 * comparison page would get no anchor and the site's own search
			 * would send a hit for it to the top of the page.
			 */
			markdown: {
				processedDirs: ['../docs', './src/content/pages']
			},

			customCss: ['./src/styles/site.css'],

			/*
			 * What a page carries besides the page: the link preview image.
			 * What it does and why is on the module.
			 */
			routeMiddleware: './src/route-data.mjs',

			/*
			 * The head adds the client router, so that a click within the
			 * documentation changes the content rather than the document. The
			 * header row carries a link into each part of the documentation,
			 * and gathers the search with the rest of the controls. The search
			 * asks a node rather than the static index, which is what the site
			 * is for. The title of a page is labelled with the part of the
			 * manual it is in, the front page leads with one line rather than
			 * with the site name, and the theme is three cells rather than a
			 * dropdown. The end of a page names who publishes the engine. The
			 * frame gives every page the same menu, at the one width the site
			 * becomes a single column at, and the button that opens it is
			 * shown at that width rather than Starlight's. Why each is
			 * replaced, and what it costs, is on the component.
			 *
			 * Pagefind is left on. It is what the search falls back to when no
			 * node answers, and Starlight builds it whether or not it is what
			 * the site searches with.
			 */
			components: {
				Footer: './src/components/Footer.astro',
				Head: './src/components/Head.astro',
				Header: './src/components/Header.astro',
				MobileMenuToggle: './src/components/MobileMenuToggle.astro',
				PageFrame: './src/components/PageFrame.astro',
				Hero: './src/components/Hero.astro',
				PageTitle: './src/components/PageTitle.astro',
				Search: './src/components/Search.astro',
				ThemeSelect: './src/components/ThemeSelect.astro'
			},

			sidebar: [
				...sidebarFrom(DOCS_INDEX),
				/*
				 * A page per endpoint, generated from the OpenAPI document the
				 * engine build writes - see `./src/openapi/`. The document is
				 * read from `public/`, so the site publishes it at
				 * `/openapi.yaml` as well: the manual tells a reader to build a
				 * client from it, and that reader should get the same copy
				 * these pages state. `mise run site:openapi` refreshes it.
				 *
				 * These pages state one endpoint's request and response in
				 * full. The pages under `docs/reference/` explain the parts of
				 * the API that are not one endpoint - a clause, a facet, a
				 * cursor - and each side links to the other.
				 */
				apiSidebarGroup(),
				{
					label: 'Demos',
					/*
					 * Closed for the reason the reference and the explanations
					 * are - see `CLOSED` in `./src/sidebar.mjs`. It opens
					 * itself on the catalogue, which is the only page of the
					 * section that keeps the sidebar; a demo runs the width of
					 * the window and has links of its own back to the manual.
					 */
					collapsed: true,
					items: [
						{ label: 'All demos', link: '/examples/' },
						...DEMOS.map(entry => ({
							label: entry.title,
							link: `/examples/${entry.name}/`
						}))
					]
				}
			]
		}),

		/*
		 * Diagrams, written as ```d2 code blocks in the Markdown under
		 * `docs/` and drawn by the D2 binary that `mise.toml` names. The SVG
		 * is written under `public/images/diagrams/` and the block is
		 * replaced by an `<img>` pointing at it; `remarkDiagramPaths` above
		 * is what decides the rest of that path for a file outside this
		 * project.
		 *
		 * A diagram arrives as a picture rather than as markup, so it follows
		 * the reader's system setting for dark mode rather than the theme
		 * they picked on the site. `website/README.md` says what that costs.
		 *
		 * The padding is well under the default of 100, because a diagram is
		 * drawn into the same column the prose is set in and the default
		 * spends a fifth of that column on margin.
		 *
		 * The themes named here are the ones `remarkDiagramStyle` paints over
		 * rather than the ones a reader sees. They are named all the same, so
		 * that anything a later release of D2 draws with a colour this site
		 * does not state yet arrives in grey rather than in the blue and pink
		 * of D2's own defaults.
		 */
		astroD2({
			output: 'images/diagrams',
			pad: 20,
			theme: { default: '1', dark: '200' }
		}),

		/*
		 * A link preview image per page, drawn after the pages are built and
		 * written next to each one. What it shows and how is on
		 * `./src/opengraph.mjs`, and the tag that points at it is added in
		 * `./src/route-data.mjs`.
		 *
		 * The integration reads every route Astro built, so `pathFilter`
		 * keeps it to the ones that produced a page - `llms.txt` and the
		 * Markdown copy of each document are routes as well.
		 */
		openGraphImages({
			options: { width: PREVIEW_WIDTH, height: PREVIEW_HEIGHT, fonts: FONTS },
			pathFilter: isPage,
			render
		}),

		/*
		 * `/sitemap-index.xml` and the sitemap it names, so a crawler is told
		 * every page rather than finding the ones it can reach by link.
		 * `public/robots.txt` points at the index.
		 *
		 * The filter is the one the link previews use, and for the same
		 * reason: the site publishes routes that are not pages - `llms.txt`,
		 * the Markdown copy of every document - and a crawler has no use for
		 * them.
		 */
		sitemap({
			filter: page => isPage(new URL(page))
		})
	]
});
