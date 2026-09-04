/*
 * What demos there are, in one list.
 *
 * The list is read by the page that catalogues the demos, by the sidebar in
 * `astro.config.mjs` and by each demo page for its own title bar, so a demo
 * added here appears everywhere it should. What a demo *does* is its module
 * under this directory; what it searches is the dataset of the same name in
 * `examples/`, which `mise run example:<name>` loads into a node.
 */

/**
 * @typedef {object} Demo
 * @property {string} name the directory, the route and the index it searches
 * @property {string} title what the demo is called
 * @property {string} summary one sentence, for a link preview and the catalogue
 * @property {string[]} shows the capabilities the demo exists to show
 */

/** @type {Demo[]} */
export const DEMOS = [
	{
		name: 'livsmedel',
		title: 'Swedish food search',
		summary: '2 606 foods from the Swedish Food Agency. A search for '
			+ '<strong>sås</strong> finds <strong>gravlaxsås</strong>, because the '
			+ 'index splits compound words.',
		shows: [
			'compound splitting',
			'facet counts that ignore their own filter',
			'range buckets',
			'highlighting'
		]
	},
	{
		name: 'airports',
		title: 'Airport type-ahead',
		summary: 'Every airport with an IATA code, completed as you type. '
			+ '<strong>sto</strong> becomes Stockholm, <strong>stockhlm</strong> '
			+ "still finds it, and <strong>ord</strong> finds O'Hare.",
		shows: [
			'autocomplete',
			'typo tolerance',
			'prefix matchers',
			'per-field weights',
			'distance filters and sorts'
		]
	},
	{
		name: 'cleveland',
		title: 'Cleveland Museum of Art',
		summary: '30 000 objects from the open access collection. Tick a department '
			+ 'and the objects narrow, but the department counts stay as they '
			+ 'were.',
		shows: [
			'facet counts that ignore their own filter',
			'range buckets',
			'ordering',
			'numbered pages and cursors',
			'highlighting'
		]
	},
	{
		name: 'discogs',
		title: 'Record shop',
		summary: '50 000 records and every pressing of them. Ask for a '
			+ '<strong>UK vinyl under €25</strong> and one pressing has to be '
			+ 'all three, not three pressings between them.',
		shows: [
			'sub-documents',
			'conditions that hold within one value',
			'matched values',
			'a value as a hit of its own',
			'facets and ordering inside a value',
			'prices and values read out of the search box'
		]
	}
];

/** The demo of a name, for a page that has to say what it is showing. */
export function demo(name) {
	const found = DEMOS.find(candidate => candidate.name === name);
	if(!found) throw new Error(`No demo named ${name}`);

	return found;
}
