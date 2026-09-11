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
		summary: '2 606 foods from Livsmedelsverket, the Swedish Food Agency. '
			+ 'Shows compounding splitting of words, type <strong>sås</strong> and the results include '
			+ '<strong>gravlaxsås</strong>.',
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
		summary: 'Every airport with an IATA code, from OurAirports. Type '
			+ '<strong>sto</strong> and Stockholm appears at once. Type the typo '
			+ '<strong>stockhlm</strong> and Stockholm is still there.',
		shows: [
			'typo tolerance',
			'prefix matchers',
			'per-field weights',
			'distance filters and sorts'
		]
	},
	{
		name: 'cleveland',
		title: 'Cleveland Museum of Art',
		summary: '30 000 objects from the open access collection with a rich ability to filter them.',
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
		summary: '300 000 records from Discogs, and every pressing of them.',
		shows: [
			'sub-documents',
			'facets and ordering inside a value',
			'matched values',
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
