/*
 * The sidebar, read from `docs/README.md`.
 *
 * That file already lists every document, grouped by what kind of
 * documentation it is and ordered so that a reader meets them in a useful
 * order. Reading it is what keeps the site's navigation from being a second
 * list that has to be kept in step with the first: a document added to the
 * index appears in the sidebar, and one that is not in the index is not
 * hidden away on the site while looking present in the repository.
 */

import { readFileSync } from 'node:fs';

/** A `- [Label](path/to/doc.md)` list entry. */
const ENTRY = /^-\s+\[([^\]]+)\]\(([^)]+\.md)\)/;

/** A `## Section` heading. */
const SECTION = /^##\s+(.+?)\s*$/;

/** A `### Sub-section` heading, which divides a long section. */
const SUBSECTION = /^###\s+(.+?)\s*$/;

/**
 * Build the sidebar groups from a documentation index.
 *
 * A `##` heading is a group and a `###` heading under it is a group nested
 * inside that one, so a section long enough to need dividing is divided in the
 * index rather than here. A heading that lists no document is left out.
 *
 * @param {URL} index the `README.md` that lists the documentation
 * @returns Starlight sidebar groups, one per section that lists documents
 * @throws {Error} if the index lists no document at all
 */
export function sidebarFrom(index) {
	const groups = [];
	let group = null;
	let subgroup = null;

	/*
	 * A list entry can wrap over several lines, so the pattern is anchored to
	 * the start of one - the description that follows the link is indented
	 * and cannot be mistaken for the next entry.
	 */
	for(const line of readFileSync(index, 'utf-8').split('\n')) {
		/*
		 * Sub-sections are matched first: `SECTION` wants whitespace after the
		 * two hashes and so does not match a `###` line, but the order says
		 * which heading is the more specific one without relying on that.
		 */
		const subsection = line.match(SUBSECTION);
		if(subsection && group) {
			subgroup = { label: subsection[1], items: [] };
			group.items.push(subgroup);
			continue;
		}

		const section = line.match(SECTION);
		if(section) {
			group = { label: section[1], items: [] };
			subgroup = null;
			groups.push(group);
			continue;
		}

		const entry = line.match(ENTRY);
		if(entry && group) {
			(subgroup ?? group).items.push({
				label: entry[1],
				slug: entry[2].replace(/\.md$/, '')
			});
		}
	}

	const listing = groups.map(pruned).filter(candidate => candidate !== null);

	if(listing.length === 0) {
		throw new Error(`No documents listed in ${index.pathname}`);
	}

	return listing;
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
