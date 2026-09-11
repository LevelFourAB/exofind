/*
 * The REST API section of the sidebar, from the same document the pages are.
 *
 * A tag is a group and an endpoint is a link under it, labelled with what the
 * endpoint does and badged with the method it answers. The order is the
 * document's: the engine writes its tags in one order and its operations in
 * another, and both are meant.
 *
 * The section arrives closed, because it is a section a reader goes to
 * deliberately and thirty-one endpoints under the manual push the manual off the
 * screen. The groups inside it are open: a closed tag is a second door between
 * the reader and the endpoint they came for. A closed group still opens itself
 * on a page inside it, so this is the state on arrival rather than a state the
 * reader is held in.
 */

import { sections } from './spec.mjs';

/** Where the generated pages are served, without a trailing slash. */
export const API_BASE = '/api';

/** The path of an endpoint's page. */
export function pathOf(operation) {
	return `${API_BASE}/operations/${operation.id}/`;
}

/**
 * The REST API section, as one Starlight sidebar group.
 *
 * @returns {object} a group entry for `astro.config.mjs`
 */
export function apiSidebarGroup() {
	return {
		label: 'REST API',
		collapsed: true,
		items: [
			{ label: 'Overview', link: `${API_BASE}/` },
			...sections().map(section => ({
				label: section.name,
				items: section.operations.map(operation => ({
					label: operation.summary,
					link: pathOf(operation),
					badge: {
						text: operation.method,
						/*
						 * The colour says what the endpoint does to an index
						 * rather than which verb it spells that with, and it is
						 * given in `../styles/site.css` beside the one the page
						 * itself draws. Starlight passes the class through to
						 * the badge element.
						 */
						class: `method method-${operation.method.toLowerCase()}`
					}
				}))
			}))
		]
	};
}
