/*
 * Swedish food search.
 *
 * Swedish writes compounds as one word, so `name` is analyzed the way the
 * `sv` locale asks for and every name is indexed as its parts as well as
 * whole. That is what lets `sås` find `gravlaxsås`, and what the mark under a
 * hit is drawn from: it sits under the letters that were typed, so where it
 * stops is where the index cut the word.
 */
import '../shared/exofind.css';

import { createClient, resolveConfig, explain } from '../shared/client.js';
import {
	readParams,
	writeParams,
	rangeFromParam,
	rangeToParam
} from '../shared/params.js';
import {
	debounce,
	keepingFocus,
	markFragment,
	renderRanges,
	renderValues,
	termsOf
} from '../shared/ui.js';

/** Buckets to count into, which double as the filters the page offers. */
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

/** The field the names are searched in, and the one hits are marked from. */
const NAME = 'name';

/** The locale the names are written in, and the one they are searched in. */
const LOCALE = 'sv';

/**
 * Read a name out of a returned document.
 *
 * `name` is locale specific, so it comes back as its variants keyed by locale
 * tag rather than as a string - the same shape it was indexed as. A highlight
 * is already the one variant that was searched, but a hit that has none falls
 * back to the document, and that is where the locale has to be picked.
 */
function nameOf(document) {
	const name = document[NAME];
	if(name === undefined || name === null) return '';

	return typeof name === 'string' ? name : (name[LOCALE] ?? '');
}

const client = createClient(resolveConfig({ index: 'livsmedel' }));

/*
 * The search starts as whatever the URL says it is, so a page that is shared
 * or reloaded opens on what was on screen. Every part of it is optional, and
 * anything the URL asks for that the page does not offer - a group, a range -
 * falls back to the default rather than being refused.
 */
const opened = readParams();

const state = {
	text: opened.get('q') || '',
	groups: new Set(opened.getAll('group')),
	energy: rangeFromParam(opened.get('energy'), ENERGY_RANGES),
	protein: rangeFromParam(opened.get('protein'), PROTEIN_RANGES)
};

const el = id => document.getElementById(id);

const elements = {
	query: el('query'),
	status: el('status'),
	hits: el('hits'),
	groups: el('groups'),
	energy: el('energy'),
	protein: el('protein'),
	clear: el('clear'),
	size: el('size'),
	indexName: el('index-name')
};

/**
 * Build the search to send.
 *
 * The typed text goes in `query`, so it narrows the facet counts the way it
 * narrows the hits, while what the reader ticks goes in `filters` - which is
 * what keeps a facet countable after one of its own values has been picked.
 */
function buildRequest() {
	const filters = [];

	if(state.groups.size > 0) {
		filters.push({
			field: 'group',
			match: { type: 'in', values: [...state.groups] }
		});
	}

	for(const name of ['energy', 'protein']) {
		if(state[name]) {
			filters.push({
				field: name,
				match: { type: 'range', gte: state[name].from, lt: state[name].to }
			});
		}
	}

	const request = {
		locale: LOCALE,
		filters,
		facets: [
			{ field: 'group', limit: 12 },
			{ field: 'energy', ranges: ENERGY_RANGES },
			{ field: 'protein', ranges: PROTEIN_RANGES }
		],
		fields: ['name', 'group', 'energy', 'protein', 'fat', 'carbohydrates'],
		limit: 25,
		total: 'exact'
	};

	if(state.text) {
		request.query = [{ type: 'text', text: state.text, fields: { [NAME]: 1 } }];
		request.highlight = { fields: { [NAME]: { fragments: 1, length: 120 } } };
	}

	return request;
}

/** Put the search that is running in the URL. */
function publish() {
	writeParams({
		q: state.text,
		group: state.groups,
		energy: rangeToParam(state.energy),
		protein: rangeToParam(state.protein)
	});
}

let generation = 0;
let running = null;

async function run() {
	/*
	 * Anything that reaches here is what the reader is asking for now, so a
	 * keystroke still waiting to be searched for is dropped and the search
	 * already in flight is given up on rather than left to arrive and be
	 * thrown away.
	 */
	searchSoon.cancel();
	if(running) running.abort();

	const mine = ++generation;
	const controller = new AbortController();
	running = controller;

	publish();
	elements.clear.hidden = state.groups.size === 0 && !state.energy && !state.protein;

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
	elements.status.textContent = `${result.total.count} foods · ${result.tookMs.toFixed(2)} ms`;

	renderHits(result);

	keepingFocus(() => {
		renderValues(elements.groups, {
			facet: result.facets.group,
			chosen: state.groups,
			name: 'group',
			onToggle: (value, ticked) => {
				ticked ? state.groups.add(value) : state.groups.delete(value);
				run();
			}
		});

		for(const [name, ranges] of [['energy', ENERGY_RANGES], ['protein', PROTEIN_RANGES]]) {
			renderRanges(elements[name], {
				facet: result.facets[name],
				ranges,
				chosen: state[name],
				name,
				onPick: range => {
					state[name] = range;
					run();
				}
			});
		}
	});
}

function renderHits(result) {
	if(result.hits.length === 0) {
		const nothing = document.createElement('li');
		nothing.className = 'empty';
		nothing.textContent = state.text
			? `Nothing matches ${state.text}. Try another word, or clear a filter.`
			: 'Nothing matches these filters.';

		elements.hits.replaceChildren(nothing);
		return;
	}

	const terms = termsOf(state.text);
	elements.hits.replaceChildren(...result.hits.map(hit => renderHit(hit, terms)));
}

function renderHit(hit, terms) {
	const item = document.createElement('li');
	item.className = 'hit';

	const name = document.createElement('h3');
	name.className = 'hit__name';

	const highlighted = hit.highlights && hit.highlights[NAME];
	if(highlighted && highlighted.length > 0) {
		name.append(markFragment(highlighted[0], terms));
	} else {
		name.textContent = nameOf(hit.document);
	}

	const group = document.createElement('p');
	group.className = 'hit__meta';
	group.textContent = hit.document.group || '';

	const figures = document.createElement('p');
	figures.className = 'hit__figures';
	figures.textContent = [
		figure(hit.document.energy, 'kcal'),
		figure(hit.document.protein, 'g protein'),
		figure(hit.document.fat, 'g fat'),
		figure(hit.document.carbohydrates, 'g carbs')
	].filter(Boolean).join('  ·  ');

	item.append(name, group, figures);
	return item;
}

function figure(value, unit) {
	return value === undefined || value === null
		? null
		: `${Math.round(value * 10) / 10} ${unit}`;
}

/* --- what the reader does ---------------------------------------------- */

const searchSoon = debounce(run);

elements.query.addEventListener('input', () => {
	const text = elements.query.value.trim();

	// Typing a space, or taking one back, is not a different search
	if(text === state.text) return;

	state.text = text;
	searchSoon();
});

el('suggestions').addEventListener('click', event => {
	const button = event.target.closest('button');
	if(!button) return;

	elements.query.value = button.textContent;
	state.text = button.textContent;
	elements.query.focus();
	run();
});

elements.clear.addEventListener('click', () => {
	state.groups.clear();
	state.energy = null;
	state.protein = null;
	run();
});

async function showSize() {
	try {
		elements.size.textContent = `${await client.count()} foods indexed`;
	} catch(error) {
		elements.size.textContent = '';
	}
}

/* Show what the URL asked for before the first search answers. */
elements.query.value = state.text;

elements.indexName.textContent = client.config.index;
showSize();
run();
