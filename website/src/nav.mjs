/*
 * The sections in the site header, taken from the sidebar of the page being
 * rendered.
 *
 * Reading the sidebar rather than the documentation index is what keeps the
 * header from being a third list to keep in step: the sidebar is already built
 * from `docs/README.md` and already carries the path the site is served
 * under, so a section that is added, renamed or reordered there arrives here
 * on its own. There is no landing page per section, so a section is entered at
 * the first page the sidebar lists under it.
 */

/*
 * Sections the header leaves out. The tutorial is one document and the front
 * page leads with it, so the header spends its room on the parts a reader
 * comes back to. A name here that matches no section is an error rather than
 * a section quietly returning to the header once it is renamed.
 */
const OMITTED = ['Tutorials'];

/**
 * @typedef {object} Section
 * @property {string} label what the sidebar calls the section
 * @property {string} href the page it is entered at, base and all
 * @property {boolean} current whether the page being rendered is in it
 */

/**
 * The sections of a Starlight sidebar, in the order it holds them, less the
 * ones the header leaves out. A group holding no links at all is left out; a
 * top-level link that is in no group is not a section and is left out too.
 *
 * @param {any[]} sidebar `Astro.locals.starlightRoute.sidebar`
 * @returns {Section[]}
 * @throws {Error} if a section the header leaves out is not in the sidebar
 */
export function sectionsOf(sidebar) {
	const sections = [];
	const omitted = new Set(OMITTED);

	for(const entry of sidebar) {
		if(entry.type !== 'group') continue;
		if(omitted.delete(entry.label)) continue;

		const links = linksIn(entry);
		if(links.length === 0) continue;

		sections.push({
			label: entry.label,
			href: links[0].href,
			current: links.some(link => link.isCurrent)
		});
	}

	if(omitted.size > 0) {
		throw new Error(
			`The header is told to leave out sections the sidebar does not have: ${[...omitted].join(', ')}`
		);
	}

	return sections;
}

/** Every link under a sidebar entry, however deeply it is grouped. */
function linksIn(entry) {
	return entry.type === 'link' ? [entry] : entry.entries.flatMap(linksIn);
}
