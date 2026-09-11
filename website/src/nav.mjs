/*
 * The sections in the site header, and the part of the manual a page is in.
 *
 * The parts come from `docs/README.md` through `./parts.mjs` and the links
 * from the sidebar of the page being rendered: the sidebar already carries the
 * path the site is served under and knows which page is current, and the index
 * says which part each document belongs to even where the sidebar shows a
 * section as its contents.
 *
 * A part is entered at its landing page, which `./pages/[part].astro` builds
 * from the same index and serves at the directory the documents of the part
 * are in. A part whose documents are in more than one directory has no landing
 * page and is entered at the first page listed under it.
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
export const PARTS = __DOCS_PARTS__;

/**
 * Every document the manual lists, by the slug the documentation index spells
 * it with. This is what `documentAt` looks a page up in, so that a page named
 * outside the index - by the front page - is named once and described by the
 * index rather than by a second copy of its title.
 *
 * @type {Map<string, { label: string, slug: string, description: string }>}
 */
const DOCUMENTS = new Map(
	PARTS
		.flatMap(part => part.groups)
		.flatMap(group => group.items)
		.map(item => [item.slug, item])
);

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
 * The landing page of a part is in no sidebar, so the path of the page being
 * rendered is what marks a section the reader is on the landing page of. Every
 * other page is marked by the sidebar.
 *
 * @param {any[]} sidebar `Astro.locals.starlightRoute.sidebar`
 * @param {string} pathname the path the page being rendered is served at,
 *   base and all
 * @returns {Section[]}
 * @throws {Error} if a part the header leaves out, or a group it carries, is
 *   not there to leave out or carry
 */
export function sectionsOf(sidebar, pathname) {
	const links = linksBySlug(sidebar);
	const omitted = new Set(OMITTED);
	const extra = new Set(EXTRA);
	const here = slugOf(pathname);

	const sections = [];

	for(const part of PARTS) {
		if(omitted.delete(part.label)) continue;

		const found = part.slugs.map(slug => links.get(slug)).filter(link => link);
		if(found.length === 0) continue;

		sections.push({
			label: part.label,
			href: entryTo(part) ?? found[0].href,
			current: found.some(link => link.isCurrent) || here === part.path
		});
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
 * What part of the site a page is in, or `null` when it is in none - the front
 * page and the demo pages are rendered without a sidebar, and a page in no
 * sidebar is in no part either.
 *
 * This is the label over a page title. Unlike the header, it names every part,
 * the tutorials included: what the header leaves out to spend its room
 * elsewhere, a reader still has to be told they are reading.
 *
 * The pages that are not documentation - an endpoint, the demo catalogue - are
 * in no part of the manual, and are labelled with the sidebar group they are
 * under instead. Without that an endpoint arrived at from a search result is a
 * page titled `Search an index` with nothing on it that says it is the REST API.
 *
 * @param {any[]} sidebar `Astro.locals.starlightRoute.sidebar`
 * @returns {string | null}
 */
export function sectionOf(sidebar) {
	const current = linksIn({ type: 'group', entries: sidebar })
		.find(link => link.isCurrent);

	if(!current) return null;

	const slug = slugOf(current.href);
	const part = PARTS.find(candidate => candidate.slugs.includes(slug));

	return part?.label ?? groupOver(sidebar, current);
}

/** The top-level sidebar group a link is under, or `null` for a link outside them all. */
function groupOver(sidebar, link) {
	const group = sidebar.find(entry => entry.type === 'group' && linksIn(entry).includes(link));

	return group?.label ?? null;
}

/**
 * Every part of the manual, with the page it is entered at.
 *
 * The header works from the sidebar of the page being rendered, because it
 * marks the section the reader is in. The footer cannot: it is on the demo
 * pages and the front page too, and those are rendered without a sidebar. So
 * it works from the index alone, which says the same thing about where a part
 * is entered.
 *
 * @returns {{ label: string, href: string }[]} every part, in the order the
 *   documentation index holds them
 */
export function parts() {
	return PARTS
		.filter(part => part.slugs.length > 0)
		.map(part => ({
			label: part.label,
			href: entryTo(part) ?? `${BASE}/${part.slugs[0]}/`
		}));
}

/**
 * One part of the manual by the name the documentation index gives it, with
 * the page it is entered at.
 *
 * A page outside the manual that links into a named part - the front page
 * does, under "Where to go" - asks for it here rather than writing the path,
 * so that the link leads to the landing page of the part and a section renamed
 * in the index fails the build instead of publishing a link to nothing.
 *
 * @param {string} label what the documentation index calls the part
 * @returns {{ label: string, href: string }}
 * @throws {Error} if the index lists no part of that name
 */
export function partNamed(label) {
	const part = PARTS.find(candidate => candidate.label === label);

	if(!part || part.slugs.length === 0) {
		throw new Error(`The documentation index has no part called ${label}`);
	}

	return { label: part.label, href: entryTo(part) ?? `${BASE}/${part.slugs[0]}/` };
}

/**
 * One document of the manual by its slug, which is the path `docs/README.md`
 * links it at, less the `.md`.
 *
 * The title and the sentence come from the index, so a page named here is
 * described the way it is described everywhere else on the site, and a page
 * that is renamed, moved or dropped fails the build rather than leaving a link
 * to nothing behind.
 *
 * @param {string} slug for example `how-to/define-an-index`
 * @returns {{ label: string, href: string, description: string }}
 * @throws {Error} if the index lists no document at that slug
 */
export function documentAt(slug) {
	const document = DOCUMENTS.get(slug);

	if(!document) {
		throw new Error(`The documentation index lists no document at ${slug}`);
	}

	return {
		label: document.label,
		href: `${BASE}/${slug}/`,
		description: document.description
	};
}

/**
 * The landing page of a part, or `null` for a part that has none. A part that
 * has none is entered at the first page listed under it - see the comment at
 * the top of this file.
 */
function entryTo(part) {
	return part.path ? `${BASE}/${part.path}/` : null;
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
