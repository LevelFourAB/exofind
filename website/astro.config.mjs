// @ts-check
import { fileURLToPath } from 'node:url';

import { defineConfig } from 'astro/config';
import { unified } from '@astrojs/markdown-remark';
import starlight from '@astrojs/starlight';
import starlightOpenAPI, { openAPISidebarGroups } from 'starlight-openapi';

import { DEMOS } from './src/examples/demos.mjs';
import { CATALOGUE, DOCS_INDEX, PARTS } from './src/parts.mjs';
import { remarkRewriteLinks, remarkStripTitle } from './src/plugins/remark-docs.mjs';
import { sidebarFrom } from './src/sidebar.mjs';
import { BASE, REPO, SITE } from './src/site.mjs';

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
	 */
	vite: {
		define: {
			__DOCS_PARTS__: JSON.stringify(PARTS),
			__DOCS_CATALOGUE__: JSON.stringify(CATALOGUE)
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
				[remarkRewriteLinks, { docsRoot: fileURLToPath(docsRoot) }]
			]
		})
	},

	integrations: [
		starlight({
			title: 'Exofind',
			description: 'A search engine that keeps its indexes in S3-compatible object storage.',
			favicon: '/favicon.svg',

			social: [
				{ icon: 'github', label: 'GitHub', href: REPO }
			],

			editLink: {
				baseUrl: `${REPO}/edit/main/`
			},

			/*
			 * Starlight runs its own Markdown transforms - asides, heading
			 * anchor links - only on files inside its collection directory,
			 * and these files are in `docs/` instead. Naming the directory
			 * here is what lets a `:::note` in a document become an aside
			 * rather than a bare `<div>`. The loader in
			 * `./src/content/loader.mjs` tells the renderer which file it is
			 * rendering, which is the other half of the same check.
			 */
			markdown: {
				processedDirs: ['../docs']
			},

			customCss: ['./src/styles/site.css'],

			/*
			 * A page per endpoint, generated from the OpenAPI document the
			 * engine build writes. The document is read from `public/`, so the
			 * site publishes it at `/openapi.yaml` as well - the manual tells
			 * a reader to build a client from it, and that reader should get
			 * the same copy these pages state. `mise run site:openapi`
			 * refreshes it.
			 *
			 * These pages state one endpoint's request and response in full.
			 * The pages under `docs/reference/` explain the parts of the API
			 * that are not one endpoint - a clause, a facet, a cursor - and
			 * each side links to the other.
			 */
			plugins: [
				starlightOpenAPI([
					{
						base: 'api',
						schema: './public/openapi.yaml',
						sidebar: {
							label: 'REST API',
							collapsed: false,
							operations: { badges: true }
						},
						/*
						 * A call in four languages on every endpoint page, so
						 * that a reader sees the request they are about to
						 * make rather than the shape of it. The request body
						 * and the response carry a generated example as well,
						 * which is what the two `true` settings are, and both
						 * give way to an example the OpenAPI document states
						 * itself where there is one.
						 */
						snippets: {
							operation: {
								clients: {
									shell: [ 'curl' ],
									javascript: [ 'fetch' ],
									java: [ 'nethttp' ],
									go: [ 'nethttp' ]
								},
								default: { target: 'shell', client: 'curl' }
							},
							requestBody: true,
							response: true
						}
					}
				])
			],

			/*
			 * The sidebar is settled here, after the generated section has
			 * been put in place of its placeholder. What it does and why is on
			 * the module.
			 */
			routeMiddleware: './src/route-data.mjs',

			/*
			 * The head adds the client router, so that a click within the
			 * documentation changes the content rather than the document. The
			 * header row carries a link into each part of the documentation,
			 * and gathers the search with the rest of the controls. The title
			 * of a page is labelled with the part of the manual it is in, the
			 * front page leads with one line rather than with the site name,
			 * and the theme is three cells rather than a dropdown. Why each is
			 * replaced, and what it costs, is on the component.
			 */
			components: {
				Head: './src/components/Head.astro',
				Header: './src/components/Header.astro',
				Hero: './src/components/Hero.astro',
				PageTitle: './src/components/PageTitle.astro',
				ThemeSelect: './src/components/ThemeSelect.astro'
			},

			sidebar: [
				...sidebarFrom(DOCS_INDEX),
				...openAPISidebarGroups,
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
		})
	]
});
