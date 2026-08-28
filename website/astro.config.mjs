// @ts-check
import { fileURLToPath } from 'node:url';

import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

import { DEMOS } from './src/examples/demos.mjs';
import { remarkRewriteLinks, remarkStripTitle } from './src/plugins/remark-docs.mjs';
import { sidebarFrom } from './src/sidebar.mjs';
import { BASE, REPO, SITE } from './src/site.mjs';

const docsRoot = new URL('../docs/', import.meta.url);

export default defineConfig({
	site: SITE,
	base: BASE,

	/*
	 * The documentation lives in `docs/` and is read from the repository as
	 * well as from here, so the plugins that make a repository document into a
	 * page run over every document rather than the files being changed. What
	 * each one does is on the plugin.
	 */
	markdown: {
		remarkPlugins: [
			remarkStripTitle,
			[remarkRewriteLinks, { docsRoot: fileURLToPath(docsRoot) }]
		]
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

			customCss: ['./src/styles/site.css'],

			/*
			 * The header row carries a link into each part of the
			 * documentation, and gathers the search with the rest of the
			 * controls. Why, and what that costs, is on the component.
			 */
			components: {
				Header: './src/components/Header.astro'
			},

			sidebar: [
				...sidebarFrom(new URL('README.md', docsRoot)),
				{
					label: 'Demos',
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
