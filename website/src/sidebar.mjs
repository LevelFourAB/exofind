/*
 * The sidebar, read from `docs/README.md`.
 *
 * That file already lists every document, grouped by what kind of
 * documentation it is and ordered so that a reader meets them in a useful
 * order. Reading it is what keeps the site's navigation from being a second
 * list that has to be kept in step with the first: a document added to the
 * index appears in the sidebar, and one that is not in the index is not
 * hidden away on the site while looking present in the repository.
 *
 * The same index also carries a sentence per document saying what the page
 * gets the reader. The sidebar has no room for it, so it is read here for
 * `/llms.txt` - see `./pages/llms.txt.ts`.
 */

import { readFileSync } from 'node:fs';

/** A `- [Label](path/to/doc.md): what the page gets the reader` list entry. */
const ENTRY = /^-\s+\[([^\]]+)\]\(([^)]+\.md)\)\s*:?\s*(.*)$/;

/** An indented line, which carries on the description of the entry above it. */
const CONTINUATION = /^\s+(\S.*?)\s*$/;

/** A `## Section` heading. */
const SECTION = /^##\s+(.+?)\s*$/;

/** A `### Sub-section` heading, which divides a long section. */
const SUBSECTION = /^###\s+(.+?)\s*$/;

/*
 * Sections the sidebar shows as what is in them rather than as a group of
 * their own. Both are sections a reader is in most of the time: the tutorials
 * are two documents, which cost less room than the heading over them, and the
 * how-to guides are most of the manual, so a group holding all of them is a
 * step between the reader and every page they came for. Their sub-sections
 * become the groups instead. The other sections stay whole, because each is a
 * kind of documentation a reader goes to deliberately.
 *
 * A name here that matches no section is an error rather than a section
 * quietly returning to a group of its own once it is renamed. The part of the
 * manual a page belongs to is still named in the header and over the page
 * title, and that is read from the same index - see `./parts.mjs`.
 */
const FLATTENED = ['Tutorials', 'How-to guides'];

/*
 * Sections the sidebar shows closed until the reader is in them. These are the
 * sections read a page at a time rather than worked through - a reader goes to
 * one reference topic or one explanation, and the twenty-odd lines the rest of
 * them cost push the section they came from off the screen. The how-to guides
 * are left open: they are the part of the manual a reader browses, and their
 * titles are what says which guide solves the task at hand.
 *
 * A closed group opens itself on a page inside it, whether the reader got there
 * from the header, from a link in the prose or from search, and stays open
 * afterwards. So this decides what the column looks like on arrival, not what
 * the reader can see while reading.
 *
 * A name here that matches no section is an error, as in `FLATTENED` above. The
 * sections that are not documentation - the REST API and the demos - are closed
 * where they are declared, in `../astro.config.mjs`.
 */
const CLOSED = ['Reference', 'Explanation'];

/**
 * Build the sidebar entries from a documentation index.
 *
 * A `##` heading is a group and a `###` heading under it is a group nested
 * inside that one, so a section long enough to need dividing is divided in the
 * index rather than here. A heading that lists no document is left out, and a
 * section named in `FLATTENED` is replaced by what it holds.
 *
 * Groups arrive open unless `CLOSED` names them, and Starlight remembers
 * whichever ones the reader opens or closes themselves.
 *
 * @param {URL} index the `README.md` that lists the documentation
 * @returns Starlight sidebar entries, groups and links both
 * @throws {Error} if the index lists no document at all, or if a section that
 *   is to be flattened or closed is not in it
 */
export function sidebarFrom(index) {
	const flattened = new Set(FLATTENED);
	const closed = new Set(CLOSED);

	const listing = sectionsIn(index).flatMap(section => flattened.delete(section.label)
		? section.items.map(entry => grouped(entry, closed))
		: [grouped(section, closed)]);

	if(flattened.size > 0) {
		throw new Error(
			`The sidebar is told to flatten sections the documentation index does not have: ${[...flattened].join(', ')}`
		);
	}

	if(closed.size > 0) {
		throw new Error(
			`The sidebar is told to close sections the documentation index does not have: ${[...closed].join(', ')}`
		);
	}

	if(listing.length === 0) {
		throw new Error(`No documents listed in ${index.pathname}`);
	}

	return listing;
}

/**
 * @typedef {object} Part
 * @property {string} label what the documentation index calls the part
 * @property {string[]} slugs every document in it, in the order it lists them
 */

/**
 * The parts of the manual - what the `##` headings of the documentation index
 * are, and which documents are under each. This is what the header and the
 * label over a page title are built from: the sidebar shows some sections as
 * their contents, and a reader still has to be told which kind of
 * documentation they have landed in.
 *
 * @param {URL} index the `README.md` that lists the documentation
 * @returns {Part[]} one per section that lists documents, in index order
 */
export function partsFrom(index) {
	return sectionsIn(index).map(section => ({
		label: section.label,
		slugs: slugsIn(section)
	}));
}

/**
 * @typedef {object} Listing
 * @property {string} label what to head the list with
 * @property {{ label: string, slug: string, description: string }[]} items
 *   every document under it, in the order the index lists them
 */

/**
 * The documentation index as a flat run of headed lists, each document keeping
 * the sentence the index describes it with.
 *
 * A `###` sub-section becomes a list of its own, headed with the section it is
 * under - `How-to guides: Searching`. Nothing is nested, because the file this
 * builds is read by a machine choosing which page to fetch, and a heading that
 * names both the kind of documentation and the subject says more to that
 * reader than a heading it has to remember the parent of.
 *
 * @param {URL} index the `README.md` that lists the documentation
 * @returns {Listing[]} in index order
 */
export function catalogueFrom(index) {
	return sectionsIn(index).flatMap(section => {
		const documents = section.items.filter(item => !item.items);
		const subgroups = section.items.filter(item => item.items);

		return [
			...documents.length > 0 ? [{ label: section.label, items: documents }] : [],
			...subgroups.map(group => ({
				label: `${section.label}: ${group.label}`,
				items: group.items
			}))
		];
	});
}

/**
 * The `##` sections of a documentation index, each holding links and the
 * sub-groups its `###` headings make, and none of them empty.
 */
function sectionsIn(index) {
	const sections = [];
	let section = null;
	let subgroup = null;
	let entry = null;

	for(const line of readFileSync(index, 'utf-8').split('\n')) {
		/*
		 * A description wraps over several lines, and the index indents the
		 * lines it carries on to. Gathering them first is what lets every
		 * other pattern be anchored to the start of a line: an indented line
		 * cannot then be mistaken for the entry or the heading below it.
		 */
		const carried = line.match(CONTINUATION);
		if(carried && entry) {
			entry.description = `${entry.description} ${carried[1]}`.trim();
			continue;
		}

		entry = null;

		/*
		 * Sub-sections are matched first: `SECTION` wants whitespace after the
		 * two hashes and so does not match a `###` line, but the order says
		 * which heading is the more specific one without relying on that.
		 */
		const subsection = line.match(SUBSECTION);
		if(subsection && section) {
			subgroup = { label: subsection[1], items: [] };
			section.items.push(subgroup);
			continue;
		}

		const heading = line.match(SECTION);
		if(heading) {
			section = { label: heading[1], items: [] };
			subgroup = null;
			sections.push(section);
			continue;
		}

		const listed = line.match(ENTRY);
		if(listed && section) {
			entry = {
				label: listed[1],
				slug: listed[2].replace(/\.md$/, ''),
				description: listed[3].trim()
			};

			(subgroup ?? section).items.push(entry);
		}
	}

	return sections.map(pruned).filter(candidate => candidate !== null);
}

/**
 * A group without the sub-groups that list no document, or `null` when it is
 * left listing nothing itself. The prose between a heading and its list is not
 * navigation, so a heading that carries only prose is not a group.
 */
function pruned(group) {
	const items = group.items
		.map(item => item.items ? pruned(item) : item)
		.filter(item => item !== null);

	return items.length > 0 ? { ...group, items } : null;
}

/**
 * A group as Starlight takes it, closed if `CLOSED` names it and open
 * otherwise, and a link as the label and slug alone.
 *
 * The description read from the index is dropped here. Starlight validates a
 * sidebar entry against a strict schema and refuses a property it does not
 * know, so an entry keeps only what a sidebar is built from.
 */
function grouped(item, closed) {
	if(!item.items) return { label: item.label, slug: item.slug };

	return {
		...item,
		collapsed: closed.delete(item.label),
		items: item.items.map(child => grouped(child, closed))
	};
}

/** Every document under a group, however deeply it is grouped. */
function slugsIn(item) {
	return item.items ? item.items.flatMap(slugsIn) : [item.slug];
}
