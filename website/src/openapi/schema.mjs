/*
 * A schema as the rows of a table.
 *
 * This is the half of the generator the pages were rebuilt for. What a reader
 * needs from a field is its name, the name of its type, what it is for, and what
 * it will accept - and the last two of those are only useful if the first one is
 * there to hang them on. So a type keeps the name the document gives it all the
 * way onto the page: `Clause`, `Matcher[]`, `Match`. A type with no name of its
 * own is the only one written as `object`.
 *
 * Names are also what makes the walk finish. Sixteen schemas in this document
 * reach themselves, and a generator that expands every reference in place never
 * stops on any of them. The chain of names a field is under is carried down, and
 * a type already in that chain is named rather than expanded again: the reader
 * has the type on the page above, and the build finishes. `DEPTH` is the second
 * stop, for a tree that is finite but deeper than a page can be read.
 *
 * A tagged union is the other thing the pages were rebuilt for. A union is shown
 * as its variants, each labelled with both halves of what identifies it - the
 * name of the type and the value of the property that selects it - so that
 * `TextMatcher` and `type: "text"` are read as one thing rather than as two
 * facts on separate pages. That is what the titles in the engine's annotations
 * were standing in for.
 */

import { document } from './spec.mjs';

/**
 * How deeply a field is expanded before the page names its type and stops.
 *
 * The chain of names already stops every walk this document can produce. This
 * is the floor under a document that grows a deeper tree without a type
 * repeating in it: past six levels a row is indented further than it is wide,
 * and the reader is better served by the type's own entry higher up the page.
 */
const DEPTH = 6;

/**
 * @typedef {object} Model
 * @property {string} type the type as the page writes it, `Clause[]` and such
 * @property {string | null} name the name the document gives the type, where it
 *   gives it one - the type a `$ref` points at, or the item type of an array of
 *   them
 * @property {boolean} repeated whether the type is already open above this row,
 *   which is why it has no fields of its own here
 * @property {string} description Markdown, which the component renders
 * @property {Note[]} notes what the type accepts: a default, the values of an
 *   enumeration, a format, a range
 * @property {unknown} example a value of this type the document states, or
 *   `undefined` where it states none
 * @property {Field[]} fields the properties of an object, or of the items of an
 *   array of objects
 * @property {Variant[]} variants the members of a tagged union, likewise
 */

/**
 * @typedef {Model} Field
 * @property {string} key the name of the property
 * @property {boolean} required whether the schema demands it
 */

/**
 * @typedef {object} Variant
 * @property {string | null} name the schema the union member is
 * @property {string | null} selector the property that selects it, such as
 *   `type`
 * @property {string | null} value what that property holds for this member
 * @property {string} description Markdown, which the component renders
 * @property {unknown} example a value of the member the document states, or
 *   `undefined` where it states none
 * @property {Field[]} fields everything the member carries but the selector
 */

/**
 * @typedef {object} Note
 * @property {string} label
 * @property {string} value already formatted for the page
 */

/**
 * Resolve a `$ref`, keeping whatever is written beside it.
 *
 * A property in this document may both point at a type and say something about
 * its use of it - a `$ref` to `Match` with the default and the description this
 * one field wants. The sibling keys are the specific ones, so they are applied
 * over the type rather than under it, and a generator that returns the target
 * alone publishes the wrong default.
 *
 * @param {object} schema
 * @returns {{ schema: object, name: string | null }}
 */
export function resolve(schema) {
	if(!schema || typeof schema !== 'object') return { schema: {}, name: null };
	if(typeof schema.$ref !== 'string') return { schema, name: null };

	const name = schema.$ref.split('/').pop();
	const target = document.components?.schemas?.[name] ?? {};
	const { $ref, ...beside } = schema;

	return { schema: { ...target, ...beside }, name };
}

/**
 * A schema as the page shows it.
 *
 * @param {object} schema
 * @param {string[]} seen the names of the types this one is nested inside
 * @returns {Model}
 */
export function modelOf(schema, seen = []) {
	const { schema: resolved, name } = resolve(schema);

	/*
	 * An array is shown as its item type, because that is the type a reader
	 * writes: `query` takes clauses, and a row that says `array` and hides
	 * `Clause` in a second row is the failure these pages were rebuilt for. The
	 * brackets in the label are what says there are several.
	 */
	const list = resolved.type === 'array' || resolved.items !== undefined;
	const { schema: subject, name: itemName } = list
		? resolve(resolved.items ?? {})
		: { schema: resolved, name };

	const stated = statedExample(resolved);
	const typeName = list ? itemName : name;
	const repeated = typeName !== null && seen.includes(typeName);
	const exhausted = seen.length >= DEPTH;
	const chain = typeName ? [...seen, typeName] : seen;

	return {
		type: labelOf(resolved, name),
		name: typeName,
		repeated,
		description: resolved.description ?? subject.description ?? '',
		notes: notesOf(resolved, subject),
		example: stated !== undefined ? stated : statedExample(subject),
		fields: repeated || exhausted ? [] : fieldsOf(subject, chain),
		variants: repeated || exhausted ? [] : variantsOf(subject, chain)
	};
}

/**
 * The properties of an object, in the order the document writes them.
 *
 * A map - an object that names no property and states what its values are
 * instead - is one row whose key stands for any key, because that is what the
 * reader writes. The alternative is a row that says `object` and nothing about
 * what may go in it.
 *
 * @param {object} schema
 * @param {string[]} seen
 * @returns {Field[]}
 */
export function fieldsOf(schema, seen = []) {
	const required = new Set(schema.required ?? []);

	const properties = Object.entries(schema.properties ?? {}).map(([key, property]) => ({
		key,
		required: required.has(key),
		...modelOf(property, seen)
	}));

	const values = schema.additionalProperties;
	if(properties.length === 0 && values && typeof values === 'object') {
		return [{ key: '<key>', required: false, ...modelOf(values, seen) }];
	}

	return properties;
}

/**
 * The members of a tagged union, or nothing where the schema is not one.
 *
 * The property that selects the member is left out of the member's own fields.
 * It holds one value, the heading of the variant states that value, and a row
 * that repeats it is a row a reader has to read to learn nothing.
 *
 * @param {object} schema
 * @param {string[]} seen
 * @returns {Variant[]}
 */
export function variantsOf(schema, seen = []) {
	if(!Array.isArray(schema.oneOf)) return [];

	const selector = schema.discriminator?.propertyName ?? null;
	const mapping = schema.discriminator?.mapping ?? {};
	const values = new Map(
		Object.entries(mapping).map(([value, ref]) => [String(ref).split('/').pop(), value])
	);

	return schema.oneOf.map(member => {
		const { schema: variant, name } = resolve(member);
		const chain = name ? [...seen, name] : seen;

		return {
			name,
			selector,
			value: values.get(name) ?? valueOf(variant, selector),
			description: variant.description ?? '',
			example: statedExample(variant),
			fields: fieldsOf(variant, chain).filter(field => field.key !== selector)
		};
	});
}

/**
 * What a type is called on the page.
 *
 * The name the document gives a type wins over the JSON type it is built from,
 * for every type that has one. `string` is what is left for the types that do
 * not: a bare string, a number, a boolean.
 */
function labelOf(schema, name) {
	/*
	 * A name is answered before anything is looked at, which is what stops the
	 * walk: a type that holds itself holds it through a reference, and a
	 * reference has a name. Only a type written in place is taken apart here,
	 * and a type written in place cannot hold itself.
	 */
	if(name) return name;

	if(schema.type === 'array' || schema.items !== undefined) {
		const { schema: item, name: itemName } = resolve(schema.items ?? {});
		return `${labelOf(item, itemName)}[]`;
	}

	if(Array.isArray(schema.oneOf)) return 'one of';

	const values = schema.additionalProperties;
	if(values && typeof values === 'object') {
		const { schema: value, name: valueName } = resolve(values);
		return `map of ${labelOf(value, valueName)}`;
	}

	if(schema.properties) return 'object';

	return schema.type ?? 'any';
}

/**
 * What a field accepts, as the short entries that sit under its description.
 *
 * Only the ones a reader acts on are here. A format is one - a timestamp has to
 * be written a particular way - and so are a default, a closed set of values,
 * and a bound that a request is rejected for crossing.
 */
function notesOf(schema, subject) {
	const notes = [];
	const values = schema.enum ?? subject.enum;

	if(schema.default !== undefined) {
		notes.push({ label: 'Default', value: JSON.stringify(schema.default) });
	}

	if(values) {
		notes.push({ label: 'Values', value: values.map(value => JSON.stringify(value)).join(', ') });
	}

	const format = schema.format ?? subject.format;
	if(format) notes.push({ label: 'Format', value: format });

	const range = rangeOf(schema.minimum ?? subject.minimum, schema.maximum ?? subject.maximum);
	if(range) notes.push({ label: 'Range', value: range });

	const length = rangeOf(
		schema.minLength ?? subject.minLength,
		schema.maxLength ?? subject.maxLength
	);
	if(length) notes.push({ label: 'Length', value: length });

	const items = rangeOf(schema.minItems, schema.maxItems);
	if(items) notes.push({ label: 'Items', value: items });

	const pattern = schema.pattern ?? subject.pattern;
	if(pattern) notes.push({ label: 'Pattern', value: pattern });

	return notes;
}

/**
 * The example a schema states for itself, whichever way it states one.
 *
 * Only a stated example is answered. A default, a single-valued enumeration and
 * a format are read as examples where a whole body is built out of a schema -
 * see `./example.mjs` - because a panel with a shape in it says more than an
 * empty one. A row is not built the same way: the row already states the
 * default and the values beside it, and an example repeating them is a line the
 * reader gains nothing from.
 *
 * @param {object} schema
 * @returns {unknown} the example, or `undefined` where the schema states none
 */
export function statedExample(schema) {
	if(!schema || typeof schema !== 'object') return undefined;
	if(schema.example !== undefined) return schema.example;

	return Array.isArray(schema.examples) && schema.examples.length > 0
		? schema.examples[0]
		: undefined;
}

/** A bound written the way a reader states one, or nothing where there is none. */
function rangeOf(low, high) {
	if(low === undefined && high === undefined) return null;
	if(low !== undefined && high !== undefined) return `${low} to ${high}`;

	return low !== undefined ? `${low} or more` : `${high} or less`;
}

/**
 * The value a variant holds in its selector, read from the variant itself.
 *
 * A document that declares a discriminator states this in the mapping, and this
 * one does. It is read from the single-valued enumeration on the property as
 * well, so that a union written without a mapping still labels its variants.
 */
function valueOf(variant, selector) {
	if(!selector) return null;

	const property = variant.properties?.[selector];
	const values = property?.enum;

	return Array.isArray(values) && values.length === 1 ? String(values[0]) : null;
}
