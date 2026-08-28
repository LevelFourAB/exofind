/*
 * k6 load test for the searches the livsmedel example page sends.
 *
 *   k6 run tools/loadtest/search.js
 *   k6 run --vus 100 --duration 30s tools/loadtest/search.js
 *   SHAPE=text k6 run tools/loadtest/search.js
 *
 * An iteration sends one shape and the shapes are cycled through, so a single
 * run compares what they cost against one another. Three metrics are reported
 * per shape: `http_req_duration` is the whole round trip, `search_took_ms` is
 * what the node reports it spent answering, and `search_overhead_ms` is the
 * difference - the network, the request read and the response written.
 *
 * BASE_URL, INDEX and SHAPE override what is searched.
 */
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const URL = `${__ENV.BASE_URL || 'https://ef-api.protopaper.dev'}`
	+ `/v1alpha1/indexes/${__ENV.INDEX || 'livsmedel'}/search`;

/** Buckets the example page counts into, and the filters it offers. */
const ENERGY_RANGES = [
	{ to: 100 },
	{ from: 100, to: 250 },
	{ from: 250, to: 500 },
	{ from: 500 }
];

const PROTEIN_RANGES = [
	{ to: 5 },
	{ from: 5, to: 15 },
	{ from: 15, to: 30 },
	{ from: 30 }
];

/**
 * The search the page runs, built the way `examples/livsmedel/main.js` builds
 * it. Typed text goes in `query` so it narrows the facet counts, what the
 * reader ticks goes in `filters` so a facet stays countable after one of its
 * own values is picked.
 */
function page(text, filters) {
	const request = {
		locale: 'sv',
		filters: filters || [],
		facets: [
			{ field: 'group', limit: 12 },
			{ field: 'energy', ranges: ENERGY_RANGES },
			{ field: 'protein', ranges: PROTEIN_RANGES }
		],
		fields: ['name', 'group', 'energy', 'protein', 'fat', 'carbohydrates'],
		limit: 25,
		total: 'exact'
	};

	if(text) {
		request.query = [{ type: 'text', text, fields: { name: 1 } }];
		request.highlight = { fields: { name: { fragments: 1, length: 120 } } };
	}

	return request;
}

/**
 * The shapes, ordered by what the node has to do for each. Every one of them
 * is a request the page actually sends, and they separate the paths a search
 * can take: counts answered per reader, a collection of every match, a
 * collection per filtered facet, and a word let go of.
 */
const SHAPES = [
	// No hits and no facets, so the answer is a count and nothing else
	{ name: 'count', body: { limit: 0, total: 'exact' } },

	// Nothing narrows the facets, so their counts are the ones kept per reader
	{ name: 'open', body: page(null, null) },

	// Every match is collected once, for the facets, and 25 hits are highlighted
	{ name: 'text', body: page('sås', null) },

	// `group` is filtered, so its facet is counted over a scope of its own
	{
		name: 'filtered',
		body: page('sås', [
			{ field: 'group', match: { type: 'in', values: ['Rätter'] } }
		])
	},

	// One word matches nothing, which is what makes the search run again without it
	{ name: 'relaxed', body: page('sås zzqx', null) }
];

const chosen = __ENV.SHAPE
	? SHAPES.filter(shape => shape.name === __ENV.SHAPE)
	: SHAPES;

if(chosen.length === 0) {
	throw new Error(
		`SHAPE=${__ENV.SHAPE} is not one of: ${SHAPES.map(s => s.name).join(', ')}`
	);
}

const REQUESTS = chosen.map(shape => ({
	name: shape.name,
	body: JSON.stringify(shape.body)
}));

const tookMs = new Trend('search_took_ms');
const overheadMs = new Trend('search_overhead_ms');

/*
 * k6 only prints a sub-metric for a tag it has a threshold for, so each shape
 * gets one per metric. The bounds are loose - they are what makes the
 * breakdown appear, not what the run is judged on.
 */
const thresholds = {
	http_req_failed: ['rate<0.01'],
	http_req_duration: ['p(95)<500']
};

for(const request of REQUESTS) {
	thresholds[`http_req_duration{shape:${request.name}}`] = ['p(95)<500'];
	thresholds[`search_took_ms{shape:${request.name}}`] = ['p(95)<500'];
	thresholds[`search_overhead_ms{shape:${request.name}}`] = ['p(95)<500'];
}

export const options = {
	vus: 10,
	duration: '30s',
	thresholds
};

/**
 * Read what the node reported it spent, without parsing the response.
 *
 * A page of 25 hits is tens of kilobytes, and parsing one per iteration makes
 * k6 itself the slowest part of the loop at a hundred virtual users.
 */
const TOOK = /"tookMs":(\d+(?:\.\d+)?)/;

export default function () {
	const request = REQUESTS[__ITER % REQUESTS.length];

	const response = http.post(URL, request.body, {
		headers: {
			'Content-Type': 'application/json',
			Accept: '*/*',
			Origin: 'https://exofind.pages.dev',
			Referer: 'https://exofind.pages.dev/'
		},
		tags: { shape: request.name }
	});

	const took = response.status === 200 ? TOOK.exec(response.body) : null;

	if(took) {
		const server = Number(took[1]);

		tookMs.add(server, { shape: request.name });

		overheadMs.add(response.timings.duration - server, { shape: request.name });
	}

	check(response, {
		'status is 200': r => r.status === 200,
		'answered a search': r => took !== null && r.body.indexOf('"total"') !== -1
	});
}
