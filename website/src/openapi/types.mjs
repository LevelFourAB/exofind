/*
 * Which types of the OpenAPI document get a page of their own.
 *
 * `./schema.mjs` writes the name of a type onto every row that holds one, and it
 * stops the walk in two places: when a type is already open above the row, and
 * at `DEPTH`. Both leave a name with nothing under it. A reader who reaches
 * `Clause` four levels inside a search request has the name of what goes there
 * and no way to read what it holds. The type pages are where that name leads.
 *
 * Not every schema gets one. The document declares nearly two hundred and most
 * are two properties a reader has already read in full on the endpoint page they
 * came from; a page each would add a hundred and forty entries to the sidebar and
 * to the site's own search that answer nothing the endpoint page did not. Three
 * questions pick the ones worth a page, and each is a form of the same question -
 * is this type written out more than once, or written out nowhere:
 *
 *   - A union is a type whose variants are the answer to "what may I put here",
 *     and an endpoint page shows them behind a disclosure inside a row that is
 *     itself behind three more.
 *   - A recursive type is one the walk cuts by definition. `ObjectFieldDefinition`
 *     holds field definitions and `AndClause` holds clauses, so there is no depth
 *     at which an endpoint page states one in full.
 *   - A type more than one parent points at is written out once per parent today.
 *     `Locales` is ten of those, and `ErrorResponse` is every endpoint there is.
 *
 * A parent is counted once however many times it names the type, because a union
 * names each of its variants twice - in `oneOf` and again in the discriminator
 * mapping - and a variant of one union is not a shared type.
 *
 * Nothing here reads the document the pages are built from. The site loads that
 * through a bundler and `../../search/documents.mjs` reads the same file from
 * disk under plain Node, and both have to pick the same types or the site
 * publishes pages its own search cannot find. So the rule takes a parsed
 * document and answers about that one - `./spec.mjs` binds it to the site's.
 */

/** How many parents a type needs before it is treated as a shared one. */
const SHARED = 2;

/**
 * @typedef {object} Type
 * @property {string} name as the document declares it, which is also the last
 *   segment of the URL the page is served at
 * @property {object} schema a reference to it, which is what `./schema.mjs`
 *   expands - an unresolved body is never passed around
 * @property {string} description Markdown, which the page renders
 * @property {boolean} union whether the type is a tagged union
 * @property {boolean} recursive whether the type reaches itself
 * @property {string[]} operations the `operationId` of every endpoint whose
 *   request or answer can hold one, in document order
 * @property {string[]} parents the other types with pages of their own that hold
 *   one, in alphabetical order
 */

/** The keys of a path item that are operations rather than settings. */
const METHODS = ['get', 'put', 'post', 'patch', 'delete', 'head', 'options'];

/**
 * The names a schema points at directly, without following any of them.
 *
 * The walk is over the body as written, so a `$ref` is an edge rather than
 * something to expand: that is what makes this finish on the sixteen schemas
 * that reach themselves.
 *
 * @param {unknown} node any part of the document
 * @param {Set<string>} found
 * @returns {Set<string>} the names, each once
 */
function referencesIn(node, found = new Set()) {
	if(!node || typeof node !== 'object') return found;

	if(Array.isArray(node)) {
		for(const item of node) referencesIn(item, found);
		return found;
	}

	for(const [key, value] of Object.entries(node)) {
		if(key === '$ref' && typeof value === 'string') found.add(value.split('/').pop());
		else referencesIn(value, found);
	}

	return found;
}

/**
 * Every operation of a document, as the id it is known by and the schemas it
 * names.
 *
 * Read from the paths rather than from `./spec.mjs`, because this module answers
 * about a document handed to it and the indexer has no bundler to read the
 * site's with.
 *
 * @param {object} document
 * @returns {{ id: string, references: Set<string> }[]}
 */
function callsIn(document) {
	const calls = [];

	for(const item of Object.values(document.paths ?? {})) {
		for(const [method, operation] of Object.entries(item)) {
			if(!METHODS.includes(method) || !operation?.operationId) continue;

			calls.push({
				id: operation.operationId,
				references: referencesIn({
					parameters: [...item.parameters ?? [], ...operation.parameters ?? []],
					body: operation.requestBody,
					responses: operation.responses
				})
			});
		}
	}

	return calls;
}

/**
 * Everything reachable from a set of names, through however many references.
 *
 * @param {Map<string, Set<string>>} edges
 * @param {Iterable<string>} from
 * @returns {Set<string>}
 */
function reachedFrom(edges, from) {
	const reached = new Set();
	const queue = [...from];

	while(queue.length > 0) {
		const name = queue.pop();
		if(reached.has(name)) continue;

		reached.add(name);
		queue.push(...edges.get(name) ?? []);
	}

	return reached;
}

/** The types of a document, worked out once. */
function build(document) {
	const declared = document.components?.schemas ?? {};
	const calls = callsIn(document);

	const edges = new Map();
	const parents = new Map();

	const note = (name, parent) => {
		if(!parents.has(name)) parents.set(name, new Set());
		parents.get(name).add(parent);
	};

	for(const [name, schema] of Object.entries(declared)) {
		const references = referencesIn(schema);

		edges.set(name, references);
		for(const reference of references) note(reference, name);
	}

	/*
	 * An endpoint counts as a parent as well. A type one endpoint names and
	 * nothing else does is stated in full where it is used; a type two endpoints
	 * name - a request body both of them take - is written out twice.
	 */
	for(const call of calls) {
		for(const reference of call.references) note(reference, `operation:${call.id}`);
	}

	/*
	 * Which endpoints can hold each type. An endpoint reaches a type through as
	 * many references as the tree is deep - a search holds clauses, a clause holds
	 * matchers - and all of them are the answer to "where would I write one of
	 * these", which is the question the page is here to close.
	 */
	const used = new Map();
	for(const call of calls) {
		for(const name of reachedFrom(edges, call.references)) {
			if(!used.has(name)) used.set(name, []);
			used.get(name).push(call.id);
		}
	}

	const chosen = Object.keys(declared)
		.map(name => ({
			name,
			union: Array.isArray(declared[name].oneOf),
			recursive: reachedFrom(edges, edges.get(name) ?? []).has(name),
			shared: (parents.get(name)?.size ?? 0) >= SHARED
		}))
		.filter(type => type.union || type.recursive || type.shared)
		/*
		 * Alphabetical, rather than the document's order - which for schemas is
		 * whichever order the engine's generator emitted them in and says nothing
		 * a reader can use. A reader arrives with a name, so the list is something
		 * to find a name in.
		 */
		.sort((first, second) => first.name.localeCompare(second.name));

	const pages = new Set(chosen.map(type => type.name));

	return chosen.map(type => ({
		name: type.name,
		schema: { $ref: `#/components/schemas/${type.name}` },
		description: declared[type.name].description ?? '',
		union: type.union,
		recursive: type.recursive,
		operations: used.get(type.name) ?? [],
		parents: [...parents.get(type.name) ?? []]
			.filter(parent => pages.has(parent) && parent !== type.name)
			.sort((first, second) => first.localeCompare(second))
	}));
}

/**
 * Built lists, by the document each was built from.
 *
 * Every row of every page asks whether the type it names has one of these, and
 * the answer is the same for all of them.
 */
const built = new WeakMap();

/**
 * The types of a document that get a page, in alphabetical order.
 *
 * @param {object} document a parsed OpenAPI document
 * @returns {Type[]}
 */
export function typesIn(document) {
	if(!built.has(document)) built.set(document, build(document));

	return built.get(document);
}
