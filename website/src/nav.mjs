/*
 * The sections in the site header, and the part of the manual a page is in.
 *
 * The parts come from `docs/README.md` through `./parts.mjs` and the links
 * from the sidebar of the page being rendered: the sidebar already carries the
 * path the site is served under and knows which page is current, and the index
 * says which part each document belongs to even where the sidebar shows a
 * section as its contents. There is no landing page per part, so a part is
 * entered at the first page listed under it.
 */

import { BASE } from './site.mjs';

/**
 * The parts of the manual, defined into the bundle by `astro.config.mjs` from
 * what `./parts.mjs` reads. A page is rendered from a bundle and cannot read
 * the repository, which is why they arrive as a constant rather than as an
 * import.
 *
 * @type {import('./sidebar.mjs').Part[]}
 */
/* global __DOCS_PARTS__ */
const PARTS = __DOCS_PARTS__;

/*
 * Parts the header leaves out. The tutorials are two documents and the front
 * page leads with them, so the header spends its room on the parts a reader
 * comes back to. A name here that matches no part is an error rather than a
 * part quietly returning to the header once it is renamed.
 */
const OMITTED = ['Tutorials'];

/*
 * Sidebar groups the header carries alongside the manual. The demos are pages
 * of this site rather than documents in `docs/`, so they are in no part and
 * are named here instead. A name that matches no group is an error, as above.
 */
const EXTRA = ['REST API', 'Demos'];

/**
 * @typedef {object} Section
 * @property {string} label what the section is called
 * @property {string} href the page it is entered at, base and all
 * @property {boolean} current whether the page being rendered is in it
 */

/**
 * The sections of the header: the parts of the manual in the order the
 * documentation index holds them, less the ones the header leaves out, and
 * then the sidebar groups that are not documentation. A section holding no
 * link at all is left out.
 *
 * @param {any[]} sidebar `Astro.locals.starlightRoute.sidebar`
 * @returns {Section[]}
 * @throws {Error} if a part the header leaves out, or a group it carries, is
 *   not there to leave out or carry
 */
export function sectionsOf(sidebar) {
	const links = linksBySlug(sidebar);
	const omitted = new Set(OMITTED);
	const extra = new Set(EXTRA);

	const sections = [];

	for(const part of PARTS) {
		if(omitted.delete(part.label)) continue;

		const found = part.slugs.map(slug => links.get(slug)).filter(link => link);
		if(found.length > 0) sections.push(sectionOver(part.label, found));
	}

	for(const entry of sidebar) {
		if(entry.type !== 'group' || !extra.delete(entry.label)) continue;

		const found = linksIn(entry);
		if(found.length > 0) sections.push(sectionOver(entry.label, found));
	}

	if(omitted.size > 0) {
		throw new Error(
			`The header is told to leave out parts the documentation index does not have: ${[...omitted].join(', ')}`
		);
	}

	if(extra.size > 0) {
		throw new Error(
			`The header is told to carry groups the sidebar does not have: ${[...extra].join(', ')}`
		);
	}

	return sections;
}

/**
 * What part of the manual a page is in, or `null` when it is in none - the
 * front page and the demo pages are rendered without a sidebar, and a page
 * that is not listed in `docs/README.md` is in no part either.
 *
 * This is the label over a page title. Unlike the header, it names every part,
 * the tutorials included: what the header leaves out to spend its room
 * elsewhere, a reader still has to be told they are reading.
 *
 * @param {any[]} sidebar `Astro.locals.starlightRoute.sidebar`
 * @returns {string | null}
 */
export function sectionOf(sidebar) {
	const current = linksIn({ type: 'group', entries: sidebar })
		.find(link => link.isCurrent);

	if(!current) return null;

	const slug = slugOf(current.href);
	return PARTS.find(part => part.slugs.includes(slug))?.label ?? null;
}

/** A header section over the links it holds, entered at the first of them. */
function sectionOver(label, links) {
	return {
		label,
		href: links[0].href,
		current: links.some(link => link.isCurrent)
	};
}

/** Every link in a sidebar, by the document it leads to. */
function linksBySlug(sidebar) {
	const links = linksIn({ type: 'group', entries: sidebar });
	return new Map(links.map(link => [slugOf(link.href), link]));
}

/** Every link under a sidebar entry, however deeply it is grouped. */
function linksIn(entry) {
	return entry.type === 'link' ? [entry] : entry.entries.flatMap(linksIn);
}

/**
 * The document a sidebar link leads to, spelled as `docs/README.md` spells it:
 * without the path the site is served under, and without the slash Starlight
 * ends a page URL with.
 */
function slugOf(href) {
	const path = BASE && href.startsWith(BASE) ? href.slice(BASE.length) : href;
	return path.replace(/^\/+/, '').replace(/\/+$/, '');
}
