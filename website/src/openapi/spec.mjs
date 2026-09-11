/*
 * The OpenAPI document, as the API pages read it.
 *
 * The document is written by the engine build and copied to `public/`, so the
 * site publishes the same bytes at `/openapi.yaml` that these pages are built
 * from - the manual tells a reader to generate a client from that file, and the
 * pages have to state what the file states. `mise run site:openapi` refreshes
 * it.
 *
 * Everything here is the document flattened into the shape a page asks for: an
 * operation with its parameters already gathered, a response with its schema
 * and its example already picked out. The modules beside this one take it from
 * there - `./schema.mjs` turns a schema into the rows of a table, `./example.mjs`
 * into a body to show, and `./snippet.mjs` into a call to copy.
 *
 * One rule runs through all four: a `$ref` is never inlined. Sixteen of the
 * schemas in this document reach themselves - a clause holds clauses, an object
 * field holds fields - so a generator that follows every reference to the end
 * does not finish. The name is what is shown instead, and `./schema.mjs` cuts
 * the walk when a type is already open above it.
 */

import { parse as parseYaml } from 'yaml';

/*
 * The document arrives as text rather than as a file read at run time, because
 * a page is rendered from a bundle: the module that reads a path relative to
 * itself is by then somewhere under `dist/`, and the path leads nowhere. The
 * same import is what makes a saved document refresh the running dev server.
 */
import source from '../../public/openapi.yaml?raw';

/** The keys of a path item that are operations rather than settings. */
const METHODS = ['get', 'put', 'post', 'patch', 'delete', 'head', 'options'];

/**
 * The one media type these pages state. Every endpoint accepts and returns
 * JSON, which the document says in its own description; a second media type
 * would be a second column on every page and is not there to state.
 */
const JSON_TYPE = 'application/json';

/** The tag an operation is listed under when it carries none. */
const UNTAGGED = 'Other';

export const document = parseYaml(source);

/**
 * @typedef {object} Operation
 * @property {string} id the `operationId`, which is also the last segment of
 *   the URL the page is served at
 * @property {string} method in upper case, as it is written in a call
 * @property {string} path with its `{placeholders}` left in place
 * @property {string} summary the line the sidebar and the page title use
 * @property {string} description Markdown, which the page renders
 * @property {string} tag the section of the API the operation belongs to
 * @property {object[]} parameters path, query and header parameters together
 * @property {object | null} body the JSON request body, or `null` where the
 *   endpoint takes none
 * @property {Response[]} responses every answer the endpoint states, in status
 *   order
 * @property {boolean} secured whether the endpoint wants an API key
 */

/**
 * @typedef {object} Response
 * @property {string} status the status code, or `default`
 * @property {string} description what the status means here
 * @property {object | null} schema the body, or `null` for a status with none
 * @property {unknown} example the example the document states, if it states one
 */

/**
 * Every operation the document declares, in the order it declares them.
 *
 * Parameters are gathered from the path item as well as the operation, because
 * OpenAPI lets a parameter every operation on a path shares be written once on
 * the path. This document writes none that way today, and a page that reads
 * only the operation would silently lose one the day the engine does.
 *
 * @returns {Operation[]}
 */
export function operations() {
	const listed = [];

	for(const [path, item] of Object.entries(document.paths ?? {})) {
		for(const [method, operation] of Object.entries(item)) {
			if(!METHODS.includes(method)) continue;

			listed.push({
				id: operation.operationId,
				method: method.toUpperCase(),
				path,
				summary: operation.summary ?? operation.operationId,
				description: operation.description ?? '',
				tag: operation.tags?.[0] ?? UNTAGGED,
				parameters: [...item.parameters ?? [], ...operation.parameters ?? []],
				body: bodyOf(operation),
				responses: responsesOf(operation),
				secured: (operation.security ?? document.security ?? []).length > 0
			});
		}
	}

	return listed;
}

/**
 * The operation served at a URL, by its `operationId`.
 *
 * @param {string} id
 * @returns {Operation | undefined}
 */
export function operationById(id) {
	return operations().find(operation => operation.id === id);
}

/**
 * @typedef {object} Section
 * @property {string} name the tag, which is what the sidebar group is labelled
 * @property {string} description what the tag says the endpoints under it do
 * @property {{ href: string, label: string } | null} reference the page of the
 *   manual that explains them, where the tag names one
 * @property {Operation[]} operations under it, in document order
 */

/**
 * The operations grouped by tag, in the order the document lists its tags.
 *
 * The document's own order is kept rather than sorting the groups, because it
 * is the order the engine writes them in and the one place the two agree. A tag
 * that no operation carries is left out, and an operation whose tag the document
 * does not declare gets a group of its own at the end rather than disappearing.
 *
 * @returns {Section[]}
 */
export function sections() {
	const listed = operations();
	const declared = (document.tags ?? []).map(tag => tag.name);
	const carried = [...new Set(listed.map(operation => operation.tag))];

	const order = [
		...declared.filter(name => carried.includes(name)),
		...carried.filter(name => !declared.includes(name))
	];

	return order.map(name => {
		const tag = (document.tags ?? []).find(candidate => candidate.name === name);

		return {
			name,
			description: tag?.description ?? '',
			reference: tag?.externalDocs
				? { href: tag.externalDocs.url, label: tag.externalDocs.description ?? 'Reference' }
				: null,
			operations: listed.filter(operation => operation.tag === name)
		};
	});
}

/**
 * The origin a call in the examples is made against.
 *
 * The document states the origin as a server variable, so that a deployment can
 * be told to read its own address there. A page has to print something, and what
 * it prints is the default the document gives - the address the tutorials and
 * the published images use.
 */
export function serverUrl() {
	const server = document.servers?.[0];
	if(!server) return '';

	return Object.entries(server.variables ?? {}).reduce(
		(url, [name, variable]) => url.replaceAll(`{${name}}`, variable.default ?? ''),
		server.url
	).replace(/\/$/, '');
}

/** How a reader authenticates, for the overview page and the request panels. */
export function authentication() {
	const [name, scheme] = Object.entries(document.components?.securitySchemes ?? {})[0] ?? [];
	if(!scheme) return null;

	return { name, description: scheme.description ?? '', scheme: scheme.scheme ?? '' };
}

/** The JSON request body of an operation, or `null` where it takes none. */
function bodyOf(operation) {
	const content = operation.requestBody?.content?.[JSON_TYPE];
	if(!content) return null;

	return {
		required: operation.requestBody.required === true,
		description: operation.requestBody.description ?? '',
		schema: content.schema ?? {},
		example: exampleIn(content)
	};
}

/** Every answer an operation states, in status order with `default` last. */
function responsesOf(operation) {
	return Object.entries(operation.responses ?? {})
		.map(([status, response]) => {
			const content = response.content?.[JSON_TYPE];

			return {
				status,
				description: response.description ?? '',
				schema: content?.schema ?? null,
				example: content ? exampleIn(content) : undefined
			};
		})
		.sort((first, second) => Number(first.status || 1000) - Number(second.status || 1000));
}

/**
 * The example a media type states, whichever way it states one.
 *
 * OpenAPI has three spellings and this document uses two of them: a named
 * `examples` map on an endpoint, and a bare `example` elsewhere. A named map
 * that holds several is read for its first, because the panel beside an endpoint
 * shows one call rather than a set to choose between.
 */
export function exampleIn(carrier) {
	if(!carrier) return undefined;
	if(carrier.example !== undefined) return carrier.example;

	const named = Object.values(carrier.examples ?? {})[0];
	if(named && typeof named === 'object' && 'value' in named) return named.value;

	return Array.isArray(carrier.examples) ? carrier.examples[0] : named;
}
