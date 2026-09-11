/*
 * The REST API section of the sidebar, from the same document the pages are.
 *
 * A tag is a group and an endpoint is a link under it, labelled with what the
 * endpoint does and badged with the method it answers. The order is the
 * document's: the engine writes its tags in one order and its operations in
 * another, and both are meant. A last group lists the types that have pages of
 * their own - see `./types.mjs` for which ones do.
 *
 * The section arrives closed, because it is a section a reader goes to
 * deliberately and thirty-one endpoints under the manual push the manual off the
 * screen. The groups inside it are open: a closed tag is a second door between
 * the reader and the endpoint they came for. A closed group still opens itself
 * on a page inside it, so this is the state on arrival rather than a state the
 * reader is held in.
 */

import { sections, types } from './spec.mjs';

/** Where the generated pages are served, without a trailing slash. */
export const API_BASE = '/api';

/** The path of an endpoint's page. */
export function pathOf(operation) {
	return `${API_BASE}/operations/${operation.id}/`;
}

/**
 * The path of a type's page, by the name the document declares it under.
 *
 * The name is the address for the same reason an `operationId` is: it is the one
 * name for the type that the engine, a generated client and these pages all
 * use, so a row that states a type already holds the address of its page.
 */
export function typePathOf(name) {
	return `${API_BASE}/types/${name}/`;
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
			})),
			/*
			 * The types close the section rather than opening it, and closed. A
			 * reader looking for an endpoint is looking for a verb and a path, and
			 * fifty type names above the tags is fifty lines between them and it.
			 * The way in to a type is the row that names it - see
			 * `../components/api/Fields.astro` - and this is the second way in, for
			 * a reader who has the name and not the row.
			 */
			{
				label: 'Types',
				collapsed: true,
				items: types().map(type => ({
					label: type.name,
					link: typePathOf(type.name)
				}))
			}
		]
	};
}
