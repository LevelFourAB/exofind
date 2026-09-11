/*
 * The comparison pages, read from the files themselves.
 *
 * A comparison is a Markdown page under `./content/pages/compare/`, written
 * for this site rather than for the repository - it addresses someone choosing
 * a search engine, and there is nothing in the manual it belongs under. The
 * loader in `./content/loader.mjs` reads that directory alongside `docs/`, so
 * a page there is a page of the site with nothing else to declare.
 *
 * What is declared here is the order, because alphabetical order is not the
 * order a reader wants to be offered these in. `ORDER` and the directory have
 * to hold the same names: a name with no file, or a file no name lists, fails
 * the build rather than leaving a footer link to nothing or a page nothing
 * links to.
 *
 * These pages are in no sidebar. They are written for someone who has not
 * decided to read the manual, so they are offered where the manual is not: a
 * column in the footer of every page - see `./components/Footer.astro` - and
 * the site's own search, which indexes them with everything else.
 *
 * This module is read while the site is configured, and never from a page. A
 * page is rendered from a bundle, where a path relative to a source file no
 * longer leads to the repository - the same reason `./parts.mjs` gives.
 * `../astro.config.mjs` defines the result into the bundle, and the footer
 * reads it there.
 */

import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/** Where the comparison pages are, which is also a root the loader reads. */
export const COMPARE_ROOT = new URL('./content/pages/compare/', import.meta.url);

/** The path the pages are served under, without a trailing slash. */
export const COMPARE_BASE = '/compare';

/**
 * The engines, in the order a reader is offered them: the one most readers
 * arrive from first, then the hosted product, then the engines that are
 * reached for when Elasticsearch is too much.
 */
const ORDER = ['elasticsearch', 'algolia', 'meilisearch', 'typesense', 'manticore'];

/**
 * @typedef {object} Comparison
 * @property {string} slug the file, which is also the last part of its URL
 * @property {string} title the `# H1` the page opens with
 * @property {string} engine the other engine, which is that title less the
 *   `Exofind vs` it opens with - a footer column of five titles that all start
 *   with the same two words says nothing with the first two thirds of its width
 * @property {string} summary the sentence under that heading
 */

/**
 * Every comparison page, in the order `ORDER` holds.
 *
 * The title and the summary are read from the file for the reason the sidebar
 * is read from `docs/README.md`: a second copy of either is a copy that goes
 * out of step with the page it describes.
 *
 * @returns {Comparison[]}
 * @throws {Error} if a name in `ORDER` has no file, or a file no name lists
 */
export function comparisons() {
	const root = fileURLToPath(COMPARE_ROOT);

	const found = new Set(readdirSync(root)
		.filter(name => name.endsWith('.md') && name !== 'README.md')
		.map(name => name.replace(/\.md$/, '')));

	const listing = ORDER.map(slug => {
		if(!found.delete(slug)) {
			throw new Error(`The comparisons are ordered with \`${slug}\`, which has no page`);
		}

		return read(root, slug);
	});

	if(found.size > 0) {
		throw new Error(
			`Comparison pages that the order does not list: ${[...found].join(', ')}`
		);
	}

	return listing;
}

/** One page's title and summary, which are its heading and the line under it. */
function read(root, slug) {
	const contents = readFileSync(`${root}/${slug}.md`, 'utf-8');

	const title = contents.match(/^#\s+(.+?)\s*$/m)?.[1];
	if(!title) throw new Error(`The comparison \`${slug}\` opens with no heading`);

	const summary = contents
		.slice(contents.indexOf(title) + title.length)
		.trim()
		.split(/\n\s*\n/)[0]
		?.replace(/\s+/g, ' ')
		.trim();

	if(!summary) throw new Error(`The comparison \`${slug}\` says nothing under its heading`);

	return { slug, title, engine: title.replace(/^Exofind vs\s+/, ''), summary };
}
