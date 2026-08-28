/*
 * The Cleveland Museum of Art's open access collection, faceted.
 *
 * A grid of thumbnails is what makes faceting visible: ticking a department
 * changes what is on the wall, and the counts beside every other facet
 * change with it - while the counts of the department list itself stay as
 * they were, because a facet is counted sideways of the filters on its own
 * field. That is the whole reason `filters` and `query` are separate lists in
 * a request, and the page is arranged to show it.
 *
 * Paging is here twice over. Numbered pages need the whole count and a
 * position that can be numbered; keeping going needs neither, and costs the
 * same however deep it has gone. Which is worth having depends on the page
 * being built, so this one builds both from the same response.
 */
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

/** How many objects a page of the grid holds. */
const PER_PAGE = 24;

/*
 * When an object was made, in buckets. The field holds the earliest year the
 * museum dates an object to, negative before the common era, so the first
 * bucket is everything older than that.
 */
const MADE = [
	{ to: 0 },
	{ from: 0, to: 1400 },
	{ from: 1400, to: 1700 },
	{ from: 1700, to: 1850 },
	{ from: 1850, to: 1900 },
	{ from: 1900, to: 1950 },
	{ from: 1950 }
];

/* The value facets, each a field of the index and a place to draw it. */
const FACETS = [
	/* Every department the museum has, so ticking one never hides another */
	{ name: 'departments', field: 'department', limit: 25 },
	{ name: 'types', field: 'type', limit: 10 },
	{ name: 'places', field: 'place', limit: 10 }
];

/** The orders to offer, and what each one sorts by. */
const ORDERS = {
	best: [],
	old: [{ field: 'year', order: 'asc' }],
	new: [{ field: 'year', order: 'desc' }]
};

const client = createClient(resolveConfig({ index: 'cleveland' }));

const opened = readParams();

const state = {
	text: opened.get('q') || '',
	order: opened.get('order') in ORDERS ? opened.get('order') : 'best',
	paging: opened.get('paging') === 'cursor' ? 'cursor' : 'pages',
	chosen: new Map(FACETS.map(facet => [facet.name, new Set(opened.getAll(facet.field))])),
	made: rangeFromParam(opened.get('made'), MADE),
	onView: opened.get('onview') === 'only',

	/*
	 * Where the results start: an offset for the first page of any search,
	 * and after that whichever cursor was followed to get here. The two are
	 * both `position` because a request carries only one of them.
	 */
	position: { offset: 0 },

	/* What is on the wall, which in `cursor` paging is more than one page. */
	shown: []
};

const el = id => document.getElementById(id);

const elements = {
	query: el('query'),
	status: el('status'),
	hits: el('hits'),
	pager: el('pager'),
	made: el('made'),
	onView: el('on-view'),
	clear: el('clear'),
	size: el('size'),
	indexName: el('index-name'),
	facets: Object.fromEntries(FACETS.map(facet => [facet.name, el(facet.name)])),
	orders: { best: el('sort-best'), old: el('sort-old'), new: el('sort-new') },
	paging: { pages: el('paging-pages'), cursor: el('paging-cursor') }
};

/**
 * Build the search to send.
 *
 * What was typed goes in `query`, so it narrows the facet counts the way it
 * narrows the objects, while what has been ticked goes in `filters` - which
 * is what keeps a facet countable after one of its own values has been
 * picked.
 */
function buildRequest() {
	const request = {
		filters: filters(),
		facets: [
			...FACETS.map(facet => ({ field: facet.field, limit: facet.limit })),
			{ field: 'year', ranges: MADE },
			{ field: 'onView' }
		],
		sort: ORDERS[state.order],
		fields: [
			'title',
			'artist',
			'artistLine',
			'date',
			'department',
			'type',
			'technique',
			'gallery',
			'image',
			'url'
		],
		limit: PER_PAGE,
		...state.position
	};

	if(state.text) {
		request.query = [{
			type: 'text',
			text: state.text,
			fields: {
				title: null,
				artist: null,
				technique: null,
				culture: null,
				type: null
			}
		}];

		request.highlight = {
			fields: { title: {}, artist: {}, technique: { length: 90 } }
		};
	}

	/*
	 * Numbering pages needs the whole count and a position that can be
	 * counted to, which is why it is asked for only where the page offers
	 * it - going on from a cursor needs neither and stays as cheap at the
	 * end of the results as at the start.
	 */
	if(state.paging === 'pages') {
		request.pages = { max: 9 };
	}

	return request;
}

function filters() {
	const filters = [];

	for(const facet of FACETS) {
		const chosen = state.chosen.get(facet.name);
		if(chosen.size > 0) {
			filters.push({ field: facet.field, match: { type: 'in', values: [...chosen] } });
		}
	}

	if(state.made) {
		filters.push({
			field: 'year',
			match: { type: 'range', gte: state.made.from, lt: state.made.to }
		});
	}

	if(state.onView) {
		filters.push({ field: 'onView', match: { value: true } });
	}

	return filters;
}

/** Put the search that is running in the URL. */
function publish() {
	writeParams({
		q: state.text,
		order: state.order === 'best' ? null : state.order,
		paging: state.paging === 'pages' ? null : state.paging,
		...Object.fromEntries(
			FACETS.map(facet => [facet.field, state.chosen.get(facet.name)])
		),
		made: rangeToParam(state.made),
		onview: state.onView ? 'only' : null
	});
}

let generation = 0;
let running = null;

/**
 * Search and draw the answer.
 *
 * Changing the search starts the results over, because a position means
 * nothing once what is being paged through has changed - only paging says
 * where to start, and only paging onward keeps what is already on the wall.
 *
 * @param {{at: Object, append: boolean}} where
 *   `at` is the position to search from, an offset or a cursor, and defaults
 *   to the beginning; `append` keeps the objects already shown and puts the
 *   new ones after them, which is what going on from a cursor does
 */
async function run({ at = null, append = false } = {}) {
	searchSoon.cancel();
	if(running) running.abort();

	state.position = at || { offset: 0 };
	if(!append) state.shown = [];

	const mine = ++generation;
	const controller = new AbortController();
	running = controller;

	publish();
	elements.clear.hidden = !narrowed();

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

function narrowed() {
	return FACETS.some(facet => state.chosen.get(facet.name).size > 0)
		|| Boolean(state.made)
		|| state.onView;
}

function render(result) {
	elements.status.classList.remove('status--error');
	elements.status.textContent =
		`${result.total.count} objects · ${result.tookMs.toFixed(2)} ms`;

	state.shown = state.shown.concat(result.hits);
	renderHits();
	renderPager(result);

	keepingFocus(() => {
		for(const facet of FACETS) {
			const counts = result.facets[facet.field];

			renderValues(elements.facets[facet.name], {
				facet: counts,
				chosen: state.chosen.get(facet.name),
				name: facet.field,
				onToggle: (value, ticked) => {
					const chosen = state.chosen.get(facet.name);
					ticked ? chosen.add(value) : chosen.delete(value);
					run();
				}
			});

			/*
			 * A field the matches hold no value of has nothing to offer, and a
			 * heading over an empty list reads as something having gone wrong.
			 */
			elements.facets[facet.name].parentElement.hidden =
				counts.values.length === 0 && state.chosen.get(facet.name).size === 0;
		}

		renderRanges(elements.made, {
			facet: result.facets.year,
			ranges: MADE,
			chosen: state.made,
			name: 'made',
			describe: describeYears,
			onPick: range => {
				state.made = range;
				run();
			}
		});

		renderOnView(result.facets.onView);
	});
}

/** A bucket of years, said the way a label under a picture would say it. */
function describeYears(bucket) {
	if(bucket.to === 0) return 'before the common era';
	if(bucket.from === undefined || bucket.from === null) return `up to ${bucket.to}`;
	if(bucket.to === undefined || bucket.to === null) return `${bucket.from} and later`;

	return `${bucket.from}–${bucket.to}`;
}

/**
 * The one flag worth a checkbox of its own: whether the object is out in the
 * galleries rather than in storage.
 *
 * The facet counts both values, but only one of them is worth ticking, so the
 * count drawn is the count of the value the box stands for.
 */
function renderOnView(facet) {
	const counted = facet.values.find(value => value.value === true);

	const row = document.createElement('label');
	row.className = 'choice';
	row.htmlFor = 'facet-on-view';

	const box = document.createElement('input');
	box.type = 'checkbox';
	box.id = 'facet-on-view';
	box.checked = state.onView;
	box.addEventListener('change', () => {
		state.onView = box.checked;
		run();
	});

	const label = document.createElement('span');
	label.className = 'choice__label';
	label.textContent = 'on view now';

	const count = document.createElement('span');
	count.className = 'choice__count';
	count.textContent = counted ? counted.count : 0;

	row.append(box, label, count);
	elements.onView.replaceChildren(row);
}

/* --- the wall ----------------------------------------------------------- */

function renderHits() {
	if(state.shown.length === 0) {
		const nothing = document.createElement('li');
		nothing.className = 'empty';
		nothing.textContent = state.text
			? `Nothing matches ${state.text} here. Try another word, or clear a filter.`
			: 'Nothing matches these filters.';

		elements.hits.replaceChildren(nothing);
		return;
	}

	const terms = termsOf(state.text);
	elements.hits.replaceChildren(...state.shown.map(hit => renderHit(hit, terms)));
}

function renderHit(hit, terms) {
	const object = hit.document;

	const item = document.createElement('li');
	item.className = 'piece';

	const link = document.createElement('a');
	link.className = 'piece__frame';
	link.href = object.url;
	link.target = '_blank';
	link.rel = 'noreferrer';

	const image = document.createElement('img');
	image.className = 'piece__image';
	image.src = object.image;
	image.alt = object.title || '';
	image.loading = 'lazy';
	image.decoding = 'async';

	const title = document.createElement('h3');
	title.className = 'piece__title';
	title.append(fragment(hit, 'title', object.title, terms));

	link.append(image);
	item.append(link, title);

	if(object.artist) {
		const artist = document.createElement('p');
		artist.className = 'piece__artist';
		artist.title = object.artistLine || '';
		artist.append(fragment(hit, 'artist', object.artist, terms));
		item.append(artist);
	}

	const meta = document.createElement('p');
	meta.className = 'piece__meta';
	meta.textContent = [object.date, object.department].filter(Boolean).join(' · ');
	item.append(meta);

	/*
	 * What it is made of is only worth the room when it is why the object is
	 * here - a search for `gold` is answered by half of these saying "gold".
	 */
	if(hit.highlights && hit.highlights.technique) {
		const technique = document.createElement('p');
		technique.className = 'piece__technique';
		technique.append(markFragment(hit.highlights.technique[0], terms));
		item.append(technique);
	}

	return item;
}

/**
 * The text of one field, marked where what was searched for sits inside it.
 *
 * A hit only carries fragments for the fields its text actually matched, so
 * a field left out of the highlights is shown as the document holds it.
 */
function fragment(hit, field, fallback, terms) {
	const highlighted = hit.highlights && hit.highlights[field];

	return highlighted && highlighted.length > 0
		? markFragment(highlighted[0], terms)
		: document.createTextNode(fallback || '');
}

/* --- getting to the rest ------------------------------------------------ */

/**
 * Draw the way onward, which is a different thing in each paging.
 *
 * Numbered pages come back already worked out: `start`, `middle` and `end`
 * are runs of page numbers with the gaps between them exactly where an
 * ellipsis belongs, and every entry carries the cursor that fetches it.
 * Going on from a cursor has one page onward and no numbers at all.
 */
function renderPager(result) {
	const page = result.page;

	if(state.paging === 'cursor') {
		if(!page.next) {
			elements.pager.replaceChildren();
			return;
		}

		const more = document.createElement('button');
		more.type = 'button';
		more.className = 'more';
		more.textContent = 'Show more';
		more.addEventListener('click', () => run({ at: { after: page.next }, append: true }));

		elements.pager.replaceChildren(more);
		return;
	}

	const pages = page.pages;
	if(!pages || pages.count <= 1) {
		elements.pager.replaceChildren();
		return;
	}

	const drawn = [];
	for(const stretch of [pages.start, pages.middle, pages.end]) {
		if(!stretch || stretch.length === 0) continue;
		if(drawn.length > 0) drawn.push(gap());

		drawn.push(...stretch.map(entry => pageButton(entry)));
	}

	elements.pager.replaceChildren(...drawn);
}

function pageButton(entry) {
	const button = document.createElement('button');
	button.type = 'button';
	button.className = 'page-number';
	button.textContent = entry.number;
	button.setAttribute('aria-pressed', String(Boolean(entry.current)));
	button.addEventListener('click', () => {
		if(entry.current) return;

		/*
		 * A page's cursor is a count rather than a position among the hits,
		 * so it is sent as `after` and still numbers the pages around it.
		 */
		run({ at: { after: entry.cursor } });
		window.scrollTo({ top: 0, behavior: 'smooth' });
	});

	return button;
}

function gap() {
	const span = document.createElement('span');
	span.className = 'page-gap';
	span.textContent = '…';

	return span;
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

for(const [order, button] of Object.entries(elements.orders)) {
	button.addEventListener('click', () => {
		if(state.order === order) return;

		state.order = order;
		press(elements.orders, order);
		run();
	});
}

for(const [paging, button] of Object.entries(elements.paging)) {
	button.addEventListener('click', () => {
		if(state.paging === paging) return;

		state.paging = paging;
		press(elements.paging, paging);
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
	elements.query.focus();
	run();
});

elements.clear.addEventListener('click', () => {
	for(const facet of FACETS) state.chosen.get(facet.name).clear();
	state.made = null;
	state.onView = false;
	run();
});

async function showSize() {
	try {
		elements.size.textContent = `${await client.count()} objects indexed`;
	} catch(error) {
		elements.size.textContent = '';
	}
}

/* Show what the URL asked for before the first search answers. */
elements.query.value = state.text;
press(elements.orders, state.order);
press(elements.paging, state.paging);

elements.indexName.textContent = client.config.index;
showSize();
run();
