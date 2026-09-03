/*
 * Airport type-ahead.
 *
 * Every keystroke is a search. The names and the cities are searched in the
 * fields defined for `autocomplete`, so a word still being typed is looked
 * up as the prefix it is, and both forgive typing mistakes.
 *
 * The code is searched a different way. A code is one value rather than a
 * sentence of them, so it is asked with a `prefix` matcher on the whole of
 * what was typed rather than through analysis - which is what lets `ES` list
 * Sweden and `ord` find O'Hare.
 */
import { createClient, resolveConfig, explain } from '../shared/client.js';
import { readParams, writeParams } from '../shared/params.js';
import {
	debounce,
	keepingFocus,
	markFragment,
	renderValues,
	termsOf
} from '../shared/ui.js';

/*
 * The fields the typed words are looked for in. The weights live in the
 * definition, so the fields are named here and what a hit in each counts is
 * left to the index - which is what the weighting control switches away from.
 *
 * The country is searched but never highlighted: it is here so that `sweden`
 * finds Swedish airports, and marking a word in a column the hits do not show
 * would mark nothing anybody can see.
 */
const FIELDS = ['nameAhead', 'municipalityAhead', 'country'];
const HIGHLIGHT = ['nameAhead', 'municipalityAhead'];

/** Origins to measure from, for a browser that will not say where it is. */
const ORIGINS = [
	{ id: 'here', label: 'My location' },
	{ id: 'stockholm', label: 'Stockholm', lat: 59.3293, lon: 18.0686 },
	{ id: 'new-york', label: 'New York', lat: 40.7128, lon: -74.006 },
	{ id: 'singapore', label: 'Singapore', lat: 1.3521, lon: 103.8198 }
];

/** How far from the origin to look, in kilometers - the last one is no bound. */
const RADIUSES = [50, 200, 1000, null];

const client = createClient(resolveConfig({ index: 'airports' }));

const opened = readParams();

const state = {
	text: opened.get('q') || '',
	weighting: opened.get('weighting') === 'flat' ? 'flat' : 'declared',
	sizes: new Set(opened.getAll('size')),
	scheduled: opened.get('scheduled') === 'only',
	origin: originFromParam(opened.get('near')),
	radius: radiusFromParam(opened.get('radius'))
};

const el = id => document.getElementById(id);

const elements = {
	query: el('query'),
	status: el('status'),
	hits: el('hits'),
	sizes: el('sizes'),
	scheduled: el('scheduled'),
	origins: el('origins'),
	radius: el('radius'),
	originNote: el('origin-note'),
	clear: el('clear'),
	size: el('size'),
	indexName: el('index-name'),
	weights: { declared: el('weigh-declared'), flat: el('weigh-flat') }
};

/**
 * Build the search to send for the typed text.
 *
 * The text is an `or` of two ways of reading it: the words, looked for in the
 * fields that complete them, and the whole of the code, looked for as a
 * prefix. Neither is narrower than the other - `ES` completes a code while
 * finding nothing among the words, and `stockhlm` the other way around.
 */
function buildRequest() {
	// `null` counts a hit for as much as the definition says it counts
	const counts = state.weighting === 'flat' ? 1 : null;

	const request = {
		filters: filters(),
		facets: [{ field: 'size' }, { field: 'scheduled' }],
		fields: [
			'name',
			'iata',
			'municipality',
			'country',
			'size',
			'scheduled',
			'elevation',
			'location'
		],
		limit: 20,
		total: 'exact'
	};

	if(state.text) {
		const words = {
			type: 'text',
			text: state.text,
			fields: Object.fromEntries(FIELDS.map(field => [field, counts]))
		};

		/*
		 * The code clause brings documents in that the words never reach, so
		 * it is an `or` rather than a boost - and it is the whole of what was
		 * typed that has to be the start of a code, since a code is one value
		 * and not a sentence of them.
		 */
		request.query = [{
			type: 'or',
			clauses: [
				words,
				{ field: 'iata', match: { type: 'prefix', value: state.text } }
			]
		}];

		request.highlight = {
			fields: Object.fromEntries(HIGHLIGHT.map(field => [field, { fragments: 1 }]))
		};
	}

	/*
	 * Nearness is the order when there is nothing to rank by, and a bound on
	 * what may show up when there is - a search for `sto` near Stockholm is
	 * still a search for `sto`.
	 */
	if(state.origin && !state.text) {
		request.sort = [{
			type: 'distance',
			field: 'location',
			lat: state.origin.lat,
			lon: state.origin.lon
		}];
	}

	return request;
}

function filters() {
	const filters = [];

	if(state.sizes.size > 0) {
		filters.push({ field: 'size', match: { type: 'in', values: [...state.sizes] } });
	}

	if(state.scheduled) {
		filters.push({ field: 'scheduled', match: { value: true } });
	}

	if(state.origin && state.radius) {
		filters.push({
			field: 'location',
			match: {
				type: 'distance',
				lat: state.origin.lat,
				lon: state.origin.lon,
				radius: state.radius * 1000
			}
		});
	}

	return filters;
}

/** Put the search that is running in the URL. */
function publish() {
	writeParams({
		q: state.text,
		weighting: state.weighting === 'declared' ? null : state.weighting,
		size: state.sizes,
		scheduled: state.scheduled ? 'only' : null,
		near: state.origin ? `${state.origin.lat},${state.origin.lon}` : null,
		radius: state.origin ? (state.radius ? String(state.radius) : 'any') : null
	});
}

let generation = 0;
let running = null;

async function run() {
	/*
	 * Anything that reaches here is what the reader is asking for now, so a
	 * keystroke still waiting to be searched for is dropped and the searches
	 * already in flight are given up on rather than left to arrive and be
	 * thrown away.
	 */
	searchSoon.cancel();
	if(running) running.abort();

	const mine = ++generation;
	const controller = new AbortController();
	running = controller;

	publish();
	elements.clear.hidden = state.sizes.size === 0 && !state.scheduled && !state.origin;

	try {
		const result = await client.search(buildRequest(), controller.signal);

		if(mine !== generation) return;

		render(result);
	} catch(error) {
		// Giving up on a search is not a search that failed
		if(controller.signal.aborted || mine !== generation) return;

		elements.status.textContent = explain(error, client.config);
		elements.status.classList.add('status--error');
	} finally {
		if(running === controller) running = null;
	}
}

function render(result) {
	elements.status.classList.remove('status--error');
	elements.status.textContent = `${result.total.count} airports · ${result.tookMs.toFixed(2)} ms`;

	renderHits(result);

	keepingFocus(() => {
		renderValues(elements.sizes, {
			facet: result.facets.size,
			chosen: state.sizes,
			name: 'size',
			onToggle: (value, ticked) => {
				ticked ? state.sizes.add(value) : state.sizes.delete(value);
				run();
			}
		});

		renderScheduled(result.facets.scheduled);
		renderNear();
	});
}

/**
 * Draw the one flag worth a checkbox of its own.
 *
 * The facet counts both values, but only one of them is worth ticking -
 * nobody looks for an airport that has no scheduled flights - so the count
 * that is drawn is the count of the value the box stands for.
 */
function renderScheduled(facet) {
	const counted = facet.values.find(value => value.value === true);

	const row = document.createElement('label');
	row.className = 'choice';
	row.htmlFor = 'facet-scheduled';

	const box = document.createElement('input');
	box.type = 'checkbox';
	box.id = 'facet-scheduled';
	box.checked = state.scheduled;
	box.addEventListener('change', () => {
		state.scheduled = box.checked;
		run();
	});

	const label = document.createElement('span');
	label.className = 'choice__label';
	label.textContent = 'scheduled flights';

	const count = document.createElement('span');
	count.className = 'choice__count';
	count.textContent = counted ? counted.count : 0;

	row.append(box, label, count);
	elements.scheduled.replaceChildren(row);
}

/** Draw where to measure from, and how far out to look from there. */
function renderNear() {
	elements.origins.replaceChildren(...ORIGINS.map(origin => {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'origin';
		button.id = `origin-${origin.id}`;
		button.textContent = origin.label;
		button.setAttribute('aria-pressed', String(isPicked(origin)));
		button.addEventListener('click', () => pick(origin));

		return button;
	}));

	elements.radius.replaceChildren(...RADIUSES.map((radius, position) => {
		const row = document.createElement('label');
		row.className = state.origin ? 'choice' : 'choice choice--empty';
		row.htmlFor = `radius-${position}`;

		const box = document.createElement('input');
		box.type = 'radio';
		box.name = 'radius';
		box.id = `radius-${position}`;
		box.checked = Boolean(state.origin) && state.radius === radius;
		box.disabled = !state.origin;
		box.addEventListener('change', () => {
			state.radius = radius;
			run();
		});

		const label = document.createElement('span');
		label.className = 'choice__label';
		label.textContent = radius ? `within ${radius} km` : 'any distance';

		row.append(box, label);
		return row;
	}));
}

function isPicked(origin) {
	return Boolean(
		state.origin
			&& (origin.id === state.origin.id
				|| (origin.lat === state.origin.lat && origin.lon === state.origin.lon))
	);
}

/**
 * Measure from somewhere else, asking the browser where that is when it is
 * here.
 *
 * A browser can refuse, and a page that then simply did nothing would look
 * broken, so the refusal is said out loud and the presets are left to pick
 * from.
 */
function pick(origin) {
	if(isPicked(origin)) {
		state.origin = null;
		noteOrigin();
		run();
		return;
	}

	if(origin.id !== 'here') {
		state.origin = { id: origin.id, lat: origin.lat, lon: origin.lon };
		noteOrigin();
		run();
		return;
	}

	if(!navigator.geolocation) {
		elements.originNote.textContent =
			'This browser does not say where it is - pick a city instead.';
		return;
	}

	elements.originNote.textContent = 'Asking the browser where it is…';
	navigator.geolocation.getCurrentPosition(
		position => {
			state.origin = {
				id: 'here',
				lat: round(position.coords.latitude),
				lon: round(position.coords.longitude)
			};
			noteOrigin();
			run();
		},
		() => {
			elements.originNote.textContent =
				'The browser would not say where it is - pick a city instead.';
			renderNear();
		}
	);
}

/**
 * Say where distances are being measured from - by name for a city, by
 * coordinates for wherever the browser said it is.
 */
function noteOrigin() {
	if(!state.origin) {
		elements.originNote.textContent = '';
		return;
	}

	const preset = ORIGINS.find(origin => origin.id === state.origin.id && origin.lat);

	elements.originNote.textContent = preset
		? `Measuring from ${preset.label}.`
		: `Measuring from ${state.origin.lat}, ${state.origin.lon}.`;
}

/*
 * Two decimals is a few hundred meters, which is as near as an airport needs
 * to be measured and as much as belongs in a URL that can be shared.
 */
function round(degrees) {
	return Math.round(degrees * 100) / 100;
}

/**
 * How far out to look, as it is written in a URL - a number of kilometers or
 * `any` for no bound at all. Anything else, including nothing, takes the
 * default.
 */
function radiusFromParam(text) {
	if(text === 'any') return null;

	return RADIUSES.includes(Number(text)) ? Number(text) : 200;
}

function originFromParam(text) {
	if(!text) return null;

	const [lat, lon] = text.split(',').map(Number);
	if(!Number.isFinite(lat) || !Number.isFinite(lon)) return null;

	const preset = ORIGINS.find(origin => origin.lat === lat && origin.lon === lon);

	return { id: preset ? preset.id : 'here', lat, lon };
}

/* --- the results -------------------------------------------------------- */

function renderHits(result) {
	if(result.hits.length === 0) {
		const nothing = document.createElement('li');
		nothing.className = 'empty';
		nothing.textContent = state.text
			? `Nothing matches ${state.text}. Try fewer letters, or clear a filter.`
			: 'Nothing matches these filters.';

		elements.hits.replaceChildren(nothing);
		return;
	}

	const terms = termsOf(state.text);
	elements.hits.replaceChildren(...result.hits.map(hit => renderHit(hit, terms)));
}

function renderHit(hit, terms) {
	const airport = hit.document;

	const item = document.createElement('li');
	item.className = 'hit hit--airport';

	const code = document.createElement('span');
	code.className = 'code';
	code.append(fragment(hit, 'iata', airport.iata, terms));

	const name = document.createElement('h3');
	name.className = 'hit__name';
	name.append(fragment(hit, 'nameAhead', airport.name, terms));

	const meta = document.createElement('p');
	meta.className = 'hit__meta';
	if(airport.municipality) {
		meta.append(fragment(hit, 'municipalityAhead', airport.municipality, terms), ' · ');
	}
	meta.append(`${airport.country} · ${airport.size}`);
	if(!airport.scheduled) meta.append(' · no scheduled flights');

	const figures = document.createElement('p');
	figures.className = 'hit__figures';
	figures.textContent = [
		state.origin ? `${away(airport.location)} km` : null,
		airport.elevation === undefined ? null : `${airport.elevation} ft`
	].filter(Boolean).join('  ·  ');

	item.append(code, name, meta, figures);
	return item;
}

/**
 * The text of one field, marked where what was typed sits inside it.
 *
 * A hit only carries fragments for the fields the text actually matched, so
 * a field left out of the highlights is shown as the document holds it.
 */
function fragment(hit, field, fallback, terms) {
	const highlighted = hit.highlights && hit.highlights[field];

	return highlighted && highlighted.length > 0
		? markFragment(highlighted[0], terms)
		: document.createTextNode(fallback || '');
}

/** How far a point is from the origin, in kilometers - the crow's route. */
function away(point) {
	const radians = degrees => degrees * Math.PI / 180;
	const R = 6371;

	const dLat = radians(point.latitude - state.origin.lat);
	const dLon = radians(point.longitude - state.origin.lon);

	const a = Math.sin(dLat / 2) ** 2
		+ Math.cos(radians(state.origin.lat))
			* Math.cos(radians(point.latitude))
			* Math.sin(dLon / 2) ** 2;

	return Math.round(2 * R * Math.asin(Math.sqrt(a)));
}

/* --- what the reader does ----------------------------------------------- */

const searchSoon = debounce(run);

elements.query.addEventListener('input', () => {
	const text = elements.query.value.trim();

	// Typing a space, or taking one back, is not a different search
	if(text === state.text) return;

	state.text = text;
	searchSoon();
});

for(const [weighting, button] of Object.entries(elements.weights)) {
	button.addEventListener('click', () => {
		if(state.weighting === weighting) return;

		state.weighting = weighting;
		press(elements.weights, weighting);
		run();
	});
}

function press(buttons, picked) {
	for(const [name, button] of Object.entries(buttons)) {
		button.setAttribute('aria-pressed', String(name === picked));
	}
}

el('suggestions').addEventListener('click', event => {
	const button = event.target.closest('button');
	if(!button) return;

	elements.query.value = button.textContent;
	state.text = button.textContent;

	// A sample query stands on its own; filters left from the last search
	// would hide most of what it is there to show
	clearFilters();
	elements.query.focus();
	run();
});

elements.clear.addEventListener('click', () => {
	clearFilters();
	run();
});

function clearFilters() {
	state.sizes.clear();
	state.scheduled = false;
	state.origin = null;
	noteOrigin();
}

async function showSize() {
	try {
		elements.size.textContent = `${await client.count()} airports indexed`;
	} catch(error) {
		elements.size.textContent = '';
	}
}

/* Show what the URL asked for before the first search answers. */
elements.query.value = state.text;
press(elements.weights, state.weighting);
noteOrigin();

elements.indexName.textContent = client.config.index;
showSize();
run();
