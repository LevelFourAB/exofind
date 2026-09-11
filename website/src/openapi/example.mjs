/*
 * The body shown beside an endpoint.
 *
 * An example the document states always wins. The engine annotates the calls a
 * reader arrives for - a search, the answer to that search - and those examples
 * were written to be read together: the response is the answer to the request on
 * the same page. Nothing generated here would improve on that.
 *
 * The rest are built from the schema, because a panel with nothing in it says
 * less than a panel with the shape in it. A built example is a shape rather than
 * a recommendation: every property is there, whether or not a caller would send
 * it, and the values are the defaults and formats the document states.
 *
 * The walk is cut the way `./schema.mjs` cuts its own - a type already open
 * above is not opened again - because the same sixteen schemas reach themselves
 * here. `DEPTH` is lower than it is there: a reader skims an example and reads a
 * table.
 */

import { resolve, statedExample } from './schema.mjs';

/** How deeply a built example nests before it stops. */
const DEPTH = 4;

/** What a string of each format is shown as, where the document names one. */
const FORMATS = {
	'date-time': '2026-01-01T12:00:00Z',
	date: '2026-01-01',
	uuid: '00000000-0000-0000-0000-000000000000',
	uri: 'https://example.com',
	binary: '<binary>',
	byte: 'ZXhvZmluZA=='
};

/**
 * The request body to show, or `null` where the endpoint takes none.
 *
 * @param {import('./spec.mjs').Operation} operation
 * @returns {unknown}
 */
export function requestExample(operation) {
	if(!operation.body) return null;
	if(operation.body.example !== undefined) return operation.body.example;

	return exampleOf(operation.body.schema);
}

/**
 * The answer to show: the first success the endpoint states.
 *
 * A success with no body of its own - a `204`, a `202` that only reports that
 * the work was accepted - has nothing to show, and the panel says the status
 * alone.
 *
 * @param {import('./spec.mjs').Operation} operation
 * @returns {{ status: string, description: string, body: unknown } | null}
 */
export function responseExample(operation) {
	const success = operation.responses.find(response => /^2\d\d$/.test(response.status));
	if(!success) return null;

	return {
		status: success.status,
		description: success.description,
		body: success.example !== undefined
			? success.example
			: success.schema && exampleOf(success.schema)
	};
}

/**
 * A schema as a value of the shape it describes.
 *
 * @param {object} schema
 * @param {string[]} seen the names of the types this value is nested inside
 * @returns {unknown}
 */
export function exampleOf(schema, seen = []) {
	const { schema: resolved, name } = resolve(schema);

	const stated = statedIn(resolved);
	if(stated !== undefined) return stated;

	if(name && seen.includes(name)) return null;
	if(seen.length >= DEPTH) return null;

	const chain = name ? [...seen, name] : seen;

	/*
	 * A union is shown as one of its members rather than as a choice, because
	 * an example is a call a reader can make. The first is taken: the document
	 * lists the members of every union in it with the plainest one first.
	 */
	if(Array.isArray(resolved.oneOf)) {
		return exampleOf(resolved.oneOf[0] ?? {}, chain);
	}

	if(resolved.type === 'array' || resolved.items !== undefined) {
		const item = exampleOf(resolved.items ?? {}, chain);
		return item === null ? [] : [item];
	}

	if(resolved.properties) {
		return Object.fromEntries(
			Object.entries(resolved.properties)
				.map(([key, property]) => [key, exampleOf(property, chain)])
				.filter(([, value]) => value !== null)
		);
	}

	const values = resolved.additionalProperties;
	if(values && typeof values === 'object') {
		const value = exampleOf(values, chain);
		return value === null ? {} : { key: value };
	}

	return scalarOf(resolved);
}

/** The example, default or single value a schema states for itself. */
function statedIn(schema) {
	const example = statedExample(schema);
	if(example !== undefined) return example;

	if(schema.default !== undefined) return schema.default;
	if(Array.isArray(schema.enum) && schema.enum.length > 0) return schema.enum[0];

	return undefined;
}

/** A value for a schema that holds one thing rather than a structure. */
function scalarOf(schema) {
	switch(schema.type) {
		case 'integer': return 0;
		case 'number': return 0;
		case 'boolean': return true;
		case 'null': return null;
		case 'string': return FORMATS[schema.format] ?? 'string';
		default: return schema.type === undefined ? null : 'string';
	}
}
