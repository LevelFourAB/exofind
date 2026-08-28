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

/**
 * Build the sidebar groups from a documentation index.
 *
 * @param {URL} index the `README.md` that lists the documentation
 * @returns Starlight sidebar groups, one per section that lists documents
 */
export function sidebarFrom(index) {
	const groups = [];
	let group = null;

	/*
	 * A list entry can wrap over several lines, so the pattern is anchored to
	 * the start of one - the description that follows the link is indented
	 * and cannot be mistaken for the next entry.
	 */
	for(const line of readFileSync(index, 'utf-8').split('\n')) {
		const section = line.match(SECTION);
		if(section) {
			group = { label: section[1], items: [] };
			groups.push(group);
			continue;
		}

		const entry = line.match(ENTRY);
		if(entry && group) {
			group.items.push({
				label: entry[1],
				slug: entry[2].replace(/\.md$/, '')
			});
		}
	}

	const listing = groups.filter(candidate => candidate.items.length > 0);

	if(listing.length === 0) {
		throw new Error(`No documents listed in ${index.pathname}`);
	}

	return listing;
}
