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
	// Astro wants a path where `BASE` holds a prefix to concatenate, and the
	// root is the one place the two spellings differ: `/` here, empty there.
	base: BASE || '/',

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
				...sidebarFrom(new URL('README.md', docsRoot)),
				{
					label: 'Demos',
					/* Collapsed for the reason the documentation sections are. */
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
