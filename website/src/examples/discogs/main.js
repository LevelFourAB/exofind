/*
 * A record shop, where a product is a record and a variant is one pressing of
 * it.
 *
 * This is the demo for sub-documents. A pressing carries a format, a country,
 * a year, a label, a price and whether it is in stock, and a shopper asking
 * for a UK vinyl under €25 means one pressing that is all three. A `nested`
 * clause says so: every clause inside it has to hold within the same
 * value. Ticking the same boxes without one finds a record that has a UK
 * pressing and, separately, a cheap one, which is a different and usually
 * wrong set of records. The `Match` control sends both, so the difference is
 * on screen instead of in a paragraph.
 *
 * Two more things follow from the values being documents of their own.
 * `matched` says which pressings answered, so a card shows the three that did
 * rather than the first three there are. And `hits` moves what a row stands
 * for from the record to the pressing, which is the same catalogue counted and
 * ordered a second way.
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

/** How many rows a page holds. */
const PER_PAGE = 24;

/** How many matching pressings a card lists before it counts the rest. */
const SHOW_PRESSINGS = 3;

/* What a pressing costs, in euro. */
const PRICES = [
	{ to: 5 },
	{ from: 5, to: 10 },
	{ from: 10, to: 25 },
	{ from: 25, to: 50 },
	{ from: 50 }
];

/* When it was pressed. */
const PRESSED = [
	{ to: 1970 },
	{ from: 1970, to: 1980 },
	{ from: 1980, to: 1990 },
	{ from: 1990, to: 2000 },
	{ from: 2000, to: 2010 },
	{ from: 2010 }
];

/*
 * The value facets. A facet on a field inside `variants` counts records rather
 * than pressings: a record pressed on vinyl four times counts once under
 * Vinyl.
 */
const FACETS = [
	{ name: 'genres', field: 'genre', limit: 16 },
	{ name: 'styles', field: 'style', limit: 12 },
	{ name: 'formats', field: 'variants.format', limit: 10 },
	{ name: 'specs', field: 'variants.spec', limit: 12 },
	{ name: 'countries', field: 'variants.country', limit: 10 }
];

/** Which of those facets ask something of a pressing. */
const OF_PRESSING = new Set(['formats', 'specs', 'countries']);

/*
 * Every filter on a pressing is one entry of `filters`, so a facet on a
 * pressing has one path to leave out of its own counts. Leaving out the whole
 * entry is the only granularity there is - an exclusion drops a filter entry
 * whole - so a format count ignores the country that has been ticked as well
 * as the format. The alternative is a Format list that answers Vinyl and
 * nothing else the moment Vinyl is ticked.
 */
const PRESSING_PATH = ['variants'];

/**
 * The orders to offer.
 *
 * Everything here orders by a field inside the pressing, which works whether a
 * row is a record or a pressing. Ordering a record by its pressings takes the
 * lowest matching value going up and the highest coming down, so `cheapest` is
 * the cheapest pressing that answered rather than the cheapest there is.
 */
const ORDERS = {
	best: [],
	cheap: [{ field: 'variants.price', order: 'asc' }],
	dear: [{ field: 'variants.price', order: 'desc' }],
	old: [{ field: 'variants.year', order: 'asc' }]
};

const client = createClient(resolveConfig({ index: 'discogs' }));

const opened = readParams();

const state = {
	text: opened.get('q') || '',
	order: opened.get('order') in ORDERS ? opened.get('order') : 'best',

	/* Whether one pressing has to answer everything, or any of them may */
	within: opened.get('match') !== 'any',

	/* Whether a row is a record or one pressing of one */
	rows: opened.get('rows') === 'pressings' ? 'pressings' : 'records',

	chosen: new Map(FACETS.map(facet => [facet.name, new Set(opened.getAll(facet.field))])),
	price: rangeFromParam(opened.get('price'), PRICES),
	pressed: rangeFromParam(opened.get('pressed'), PRESSED),
	inStock: opened.get('stock') === 'only',

	position: { offset: 0 },
	shown: []
};

const el = id => document.getElementById(id);

const elements = {
	query: el('query'),
	status: el('status'),
	hits: el('hits'),
	pager: el('pager'),
	price: el('price'),
	pressed: el('pressed'),
	inStock: el('in-stock'),
	clear: el('clear'),
	size: el('size'),
	indexName: el('index-name'),
	facets: Object.fromEntries(FACETS.map(facet => [facet.name, el(facet.name)])),
	orders: {
		best: el('sort-best'),
		cheap: el('sort-cheap'),
		dear: el('sort-dear'),
		old: el('sort-old')
	},
	within: { one: el('match-one'), any: el('match-any') },
	rows: { records: el('rows-records'), pressings: el('rows-pressings') }
};

/* --- what to ask the node ----------------------------------------------- */

/** Every condition the reader has put on a pressing. */
function pressingClauses() {
	const clauses = [];

	for(const facet of FACETS) {
		if(!OF_PRESSING.has(facet.name)) continue;

		const chosen = state.chosen.get(facet.name);
		if(chosen.size > 0) {
			clauses.push({ field: facet.field, match: { type: 'in', values: [...chosen] } });
		}
	}

	if(state.price) {
		clauses.push({
			field: 'variants.price',
			match: { type: 'range', gte: state.price.from, lt: state.price.to }
		});
	}

	if(state.pressed) {
		clauses.push({
			field: 'variants.year',
			match: { type: 'range', gte: state.pressed.from, lt: state.pressed.to }
		});
	}

	if(state.inStock) {
		clauses.push({ field: 'variants.inStock', match: { value: true } });
	}

	return clauses;
}

/**
 * The filters to send.
 *
 * A condition on the record is its own entry, so a facet on that field leaves
 * its own tick out of its counts. The conditions on a pressing are one entry
 * holding one `nested` clause, so all of them have to hold of the same
 * pressing. The `Match` control turns them into one entry each, which is the
 * same set of ticks asking a much weaker question.
 */
function filters() {
	const filters = [];

	for(const facet of FACETS) {
		if(OF_PRESSING.has(facet.name)) continue;

		const chosen = state.chosen.get(facet.name);
		if(chosen.size > 0) {
			filters.push({ field: facet.field, match: { type: 'in', values: [...chosen] } });
		}
	}

	const clauses = pressingClauses();
	if(clauses.length === 0) return filters;

	if(state.within) {
		filters.push({ type: 'nested', path: 'variants', clauses });
	} else {
		for(const clause of clauses) {
			filters.push({ type: 'nested', path: 'variants', clauses: [clause] });
		}
	}

	return filters;
}

/**
 * What was typed, against the record and against its pressings.
 *
 * A `text` clause at the top searches the fields of the record only, so the
 * label and the catalogue number - which belong to one pressing rather than to
 * the record - are searched by a second clause inside the path. `or` puts a
 * record in the results for matching either, and `score: max` scores it by its
 * best pressing.
 */
function query() {
	if(!state.text) return undefined;

	return [{
		type: 'or',
		clauses: [
			{
				type: 'text',
				text: state.text,
				fields: { title: null, artist: null, genre: null, style: null }
			},
			{
				type: 'nested',
				path: 'variants',
				score: 'max',
				clauses: [{
					type: 'text',
					text: state.text,
					fields: { 'variants.title': null, 'variants.label': null, 'variants.catno': null }
				}]
			}
		]
	}];
}

function buildRequest() {
	const request = {
		filters: filters(),
		facets: [
			...FACETS.map(facet => ({
				field: facet.field,
				limit: facet.limit,
				...(OF_PRESSING.has(facet.name) ? { excludeFilters: PRESSING_PATH } : {})
			})),
			{ field: 'variants.price', ranges: PRICES, excludeFilters: PRESSING_PATH },
			{ field: 'variants.year', ranges: PRESSED, excludeFilters: PRESSING_PATH },
			{ field: 'variants.inStock', excludeFilters: PRESSING_PATH }
		],
		sort: ORDERS[state.order],
		fields: [
			'title', 'artist', 'artistLine', 'year', 'genre', 'style',
			'pressings', 'mainFormat', 'url'
		],
		limit: PER_PAGE,
		pages: { max: 7 },
		...state.position
	};

	const clauses = query();
	if(clauses) request.query = clauses;

	/*
	 * `matched` and `hits` are the two answers to the same question and cannot
	 * both be asked. A row that is a record carries the pressings that
	 * answered; a row that is a pressing is one of them.
	 *
	 * Only the record hits are highlighted. Fragments of an inner field come
	 * from the values a top-level `nested` clause picked out, and the text
	 * clause here sits inside an `or` so that a record found by its title
	 * comes back with all of its pressings. Clauses inside an `or` do not
	 * restrict which values take part, so there are no fragments to ask for.
	 */
	if(state.rows === 'pressings') {
		request.hits = { path: 'variants' };
	} else {
		request.matched = { fields: { variants: { limit: SHOW_PRESSINGS } } };

		if(state.text) {
			request.highlight = { fields: { title: {}, artist: {} } };
		}
	}

	return request;
}

/** Put the search that is running in the URL. */
function publish() {
	writeParams({
		q: state.text,
		order: state.order === 'best' ? null : state.order,
		match: state.within ? null : 'any',
		rows: state.rows === 'records' ? null : 'pressings',
		...Object.fromEntries(
			FACETS.map(facet => [facet.field, state.chosen.get(facet.name)])
		),
		price: rangeToParam(state.price),
		pressed: rangeToParam(state.pressed),
		stock: state.inStock ? 'only' : null
	});
}

/* --- running it --------------------------------------------------------- */

let generation = 0;
let running = null;

async function run({ at = null } = {}) {
	searchSoon.cancel();
	if(running) running.abort();

	state.position = at || { offset: 0 };

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
		|| Boolean(state.price)
		|| Boolean(state.pressed)
		|| state.inStock;
}

function render(result) {
	elements.status.classList.remove('status--error');
	elements.status.textContent = [
		`${result.total.count.toLocaleString('en')} ${state.rows}`,
		`${result.tookMs.toFixed(2)} ms`
	].join(' · ');

	state.shown = result.hits;
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

			/* A heading over an empty list reads as something having gone wrong. */
			elements.facets[facet.name].parentElement.hidden =
				counts.values.length === 0 && state.chosen.get(facet.name).size === 0;
		}

		renderRanges(elements.price, {
			facet: result.facets['variants.price'],
			ranges: PRICES,
			chosen: state.price,
			name: 'price',
			describe: describePrice,
			onPick: range => {
				state.price = range;
				run();
			}
		});

		renderRanges(elements.pressed, {
			facet: result.facets['variants.year'],
			ranges: PRESSED,
			chosen: state.pressed,
			name: 'pressed',
			describe: describePressed,
			onPick: range => {
				state.pressed = range;
				run();
			}
		});

		renderInStock(result.facets['variants.inStock']);
	});
}

/**
 * A price, with the cents left off a round one.
 *
 * Prices land on the half euro, so writing every one of them to two places
 * would put a column of `.00` beside the four fifths that are whole.
 */
function money(amount) {
	return Number.isInteger(amount) ? `€${amount}` : `€${amount.toFixed(2)}`;
}

function describePrice(bucket) {
	if(bucket.from === undefined || bucket.from === null) return `under ${money(bucket.to)}`;
	if(bucket.to === undefined || bucket.to === null) return `${money(bucket.from)} and up`;

	return `${money(bucket.from)}–${money(bucket.to)}`;
}

function describePressed(bucket) {
	if(bucket.from === undefined || bucket.from === null) return `before ${bucket.to}`;
	if(bucket.to === undefined || bucket.to === null) return `${bucket.from}s and later`;

	return `${bucket.from}s`;
}

/** The one flag worth a box of its own, drawn with the count of the value it ticks. */
function renderInStock(facet) {
	const counted = facet.values.find(value => value.value === true);

	const row = document.createElement('label');
	row.className = 'choice';
	row.htmlFor = 'facet-in-stock';

	const box = document.createElement('input');
	box.type = 'checkbox';
	box.id = 'facet-in-stock';
	box.checked = state.inStock;
	box.addEventListener('change', () => {
		state.inStock = box.checked;
		run();
	});

	const label = document.createElement('span');
	label.className = 'choice__label';
	label.textContent = 'in stock';

	const count = document.createElement('span');
	count.className = 'choice__count';
	count.textContent = counted ? counted.count : 0;

	row.append(box, label, count);
	elements.inStock.replaceChildren(row);
}

/* --- the shelf ---------------------------------------------------------- */

function renderHits() {
	if(state.shown.length === 0) {
		const nothing = document.createElement('li');
		nothing.className = 'empty';
		nothing.textContent = state.text
			? `Nothing matches ${state.text} here. Try another word, or clear a filter.`
			: 'Nothing matches these filters. Try `any pressing`, which asks less of one.';

		elements.hits.replaceChildren(nothing);
		return;
	}

	const terms = termsOf(state.text);
	elements.hits.replaceChildren(...state.shown.map(hit => renderHit(hit, terms)));
}

/**
 * What the card is a picture of.
 *
 * A row that is a pressing is drawn as that pressing. A row that is a record
 * is drawn as what the record mostly is, which holds still while the filters
 * move - a shelf of records is a shelf of records, and the cheapest pressing
 * of almost anything is a download.
 */
function shownFormat(hit) {
	return hit.value ? hit.value.format : hit.document.mainFormat;
}

function renderHit(hit, terms) {
	const record = hit.document;

	const item = document.createElement('li');
	item.className = 'record';
	item.append(sleeve(record, shownFormat(hit)));

	const title = document.createElement('h3');
	title.className = 'record__title';
	title.append(fragment(hit, 'title', record.title, terms));

	const artist = document.createElement('p');
	artist.className = 'record__artist';
	artist.append(fragment(hit, 'artist', record.artistLine || first(record.artist), terms));

	item.append(title, artist);

	const meta = document.createElement('p');
	meta.className = 'record__meta';
	meta.textContent = [
		record.year,
		first(record.style) || first(record.genre),
		`${record.pressings} pressings`
	].filter(Boolean).join(' · ');
	item.append(meta);

	item.append(hit.value ? onePressing(hit) : matchedPressings(hit));

	return item;
}

/**
 * The pressings that answered, and how many more of them there were.
 *
 * `matched` holds only the values the `nested` clauses matched, so a search
 * narrowed to UK vinyl lists UK vinyl. `totalValues` counts all that matched,
 * of which this lists the first few - a record holds its pressings cheapest
 * first, so those are the cheapest that matched. The record's own `pressings`
 * counts every
 * pressing Discogs knows of, including the ones the catalogue left out, and is
 * shown on the line above.
 */
function matchedPressings(hit) {
	const matched = (hit.matched && hit.matched.variants) || { values: [], totalValues: 0 };

	const list = document.createElement('ul');
	list.className = 'pressings';

	/*
	 * Nothing under `any pressing` means the record answered across several
	 * pressings and no single one of them answered everything - which is the
	 * whole difference between the two ways of asking, so the row says it.
	 */
	if(matched.values.length === 0) {
		const none = document.createElement('li');
		none.className = 'pressings__none';
		none.textContent = 'no one pressing matches all of these';
		list.append(none);

		return list;
	}

	list.append(...matched.values.map(value => pressingRow(value)));

	const rest = matched.totalValues - matched.values.length;
	if(rest > 0) {
		const more = document.createElement('li');
		more.className = 'pressings__more';
		more.textContent = `and ${rest} more`;
		list.append(more);
	}

	return list;
}

/** A row where the hit is itself one pressing. */
function onePressing(hit) {
	const list = document.createElement('ul');
	list.className = 'pressings';
	list.append(pressingRow(hit.value));

	return list;
}

function pressingRow(value) {
	const row = document.createElement('li');
	row.className = value.inStock ? 'pressing' : 'pressing pressing--out';

	const what = document.createElement('span');
	what.className = 'pressing__what';
	what.textContent = [
		value.format,
		value.year,
		value.country,
		value.inStock ? null : 'sold out'
	].filter(Boolean).join(' · ');

	const where = document.createElement('span');
	where.className = 'pressing__where';
	where.textContent = [value.label, value.catno].filter(Boolean).join(' ');

	/*
	 * Shown whether or not it is in stock, because the price is what the rows
	 * are ordered by and a row without one reads as a gap in the ordering.
	 */
	const price = document.createElement('span');
	price.className = 'pressing__price';
	price.textContent = money(value.price);

	row.append(what, where, price);

	return row;
}

/*
 * The thing itself, drawn flat.
 *
 * A shop sells objects, and the objects here have silhouettes that tell them
 * apart across a room: a record has grooves and a small label, a CD has a
 * hole you could put a finger through, a cassette has two hubs behind a
 * window. Drawing the object rather than a stand-in for one is what makes a
 * wall of pressings readable, and it is the field the row is sorted and
 * filtered by that decides which is drawn.
 *
 * Each is a path set in a 100 by 100 box, painted in three tones: `ink` for
 * the body, `lit` for what catches the light, and `hole` for what you see
 * through. Nothing a node answers with is ever put in here - a format picks
 * one of these, and the markup is only ever one of these strings.
 */
const MARKS = {
	groove: `
		<circle class="ink" cx="50" cy="50" r="46"/>
		<g class="rings" fill="none">
			<circle cx="50" cy="50" r="42"/><circle cx="50" cy="50" r="38"/>
			<circle cx="50" cy="50" r="34"/><circle cx="50" cy="50" r="30"/>
			<circle cx="50" cy="50" r="26"/><circle cx="50" cy="50" r="22"/>
		</g>
		<circle class="lit" cx="50" cy="50" r="14"/>
		<circle class="hole" cx="50" cy="50" r="3"/>`,

	optical: `
		<circle class="ink" cx="50" cy="50" r="46"/>
		<g class="rings" fill="none">
			<circle cx="50" cy="50" r="43"/><circle cx="50" cy="50" r="24"/>
		</g>
		<circle class="lit" cx="50" cy="50" r="19"/>
		<circle class="hole" cx="50" cy="50" r="9"/>`,

	tape: `
		<rect class="ink" x="3" y="17" width="94" height="66" rx="5"/>
		<rect class="lit" x="20" y="28" width="60" height="27" rx="3"/>
		<circle class="hole" cx="37" cy="41" r="8"/>
		<circle class="hole" cx="63" cy="41" r="8"/>
		<rect class="lit" x="18" y="64" width="64" height="7" rx="3"/>`,

	reel: `
		<circle class="ink" cx="50" cy="50" r="46"/>
		<g class="rings" fill="none"><circle cx="50" cy="50" r="40"/></g>
		<circle class="hole" cx="50" cy="50" r="11"/>
		<circle class="hole" cx="50" cy="26" r="8"/>
		<circle class="hole" cx="29" cy="62" r="8"/>
		<circle class="hole" cx="71" cy="62" r="8"/>`,

	wave: `
		<g class="ink">
			<rect x="9" y="44" width="7" height="13" rx="3.5"/>
			<rect x="22" y="33" width="7" height="35" rx="3.5"/>
			<rect x="35" y="22" width="7" height="57" rx="3.5"/>
			<rect x="48" y="37" width="7" height="27" rx="3.5"/>
			<rect x="61" y="16" width="7" height="69" rx="3.5"/>
			<rect x="74" y="39" width="7" height="23" rx="3.5"/>
			<rect x="87" y="30" width="7" height="41" rx="3.5"/>
		</g>`,

	box: `
		<rect class="ink" x="13" y="15" width="21" height="70" rx="2"/>
		<rect class="ink" x="39" y="21" width="21" height="64" rx="2"/>
		<rect class="ink" x="65" y="17" width="21" height="68" rx="2"/>
		<g class="lit">
			<rect x="16" y="29" width="15" height="4"/>
			<rect x="42" y="35" width="15" height="4"/>
			<rect x="68" y="31" width="15" height="4"/>
		</g>`
};

/** Which mark stands for a format. Anything unlisted is a thing in a box. */
const MARK_FOR = {
	'Vinyl': 'groove', 'Shellac': 'groove', 'Acetate': 'groove',
	'Lathe Cut': 'groove', 'Flexi-disc': 'groove',
	'CD': 'optical', 'CDr': 'optical', 'SACD': 'optical', 'DVD': 'optical',
	'DVDr': 'optical', 'Blu-ray': 'optical', 'Blu-ray-R': 'optical',
	'Laserdisc': 'optical', 'Minidisc': 'optical', 'HD DVD': 'optical',
	'Cassette': 'tape', '8-Track Cartridge': 'tape', 'DAT': 'tape',
	'VHS': 'tape', 'Betacam SP': 'tape', 'Microcassette': 'tape',
	'Reel-To-Reel': 'reel',
	'File': 'wave',
	'Box Set': 'box'
};

/*
 * A sleeve, drawn rather than fetched.
 *
 * Discogs publishes no cover images under CC0, so there is none to show. The
 * colours come from the artist, which gives a shelf the run of one artist's
 * records in one palette, and the angle comes from the record, so two by the
 * same artist are still told apart. The frame links to the record on Discogs,
 * where the sleeve it actually has can be seen.
 *
 * @param {string} format what the pressing on the card is, which the mark draws
 */
function sleeve(record, format) {
	const frame = document.createElement('a');
	frame.className = 'sleeve';
	frame.href = record.url;
	frame.target = '_blank';
	frame.rel = 'noreferrer';
	frame.setAttribute('aria-label', `${record.title} on Discogs`);

	const hue = hash(record.artistLine || record.title) % 360;
	frame.style.setProperty('--sleeve-hue', hue);
	frame.style.setProperty('--sleeve-turn', `${hash(record.id || record.title) % 90 - 45}deg`);

	const mark = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
	mark.setAttribute('class', 'sleeve__mark');
	mark.setAttribute('viewBox', '0 0 100 100');
	mark.setAttribute('aria-hidden', 'true');
	mark.innerHTML = MARKS[MARK_FOR[format]] || MARKS.box;

	const type = document.createElement('span');
	type.className = 'sleeve__type';
	type.textContent = initials(record.artistLine || record.title);

	frame.append(mark, type);

	return frame;
}

/** A small stable number for a string. */
function hash(text) {
	let value = 0;
	for(let position = 0; position < (text || '').length; position++) {
		value = (value * 31 + text.charCodeAt(position)) | 0;
	}

	return Math.abs(value);
}

function initials(text) {
	return (text || '?')
		.split(/\s+/)
		.filter(Boolean)
		.slice(0, 2)
		.map(word => word[0].toUpperCase())
		.join('');
}

/**
 * The first of a field that can hold several values.
 *
 * A field declared `multiple` comes back as a bare value where the document
 * holds one of it and as an array where it holds more, so reaching for
 * position zero without looking takes the first letter of a string.
 */
function first(value) {
	return Array.isArray(value) ? value[0] : value;
}

function fragment(hit, field, fallback, terms) {
	const highlighted = hit.highlights && hit.highlights[field];

	return highlighted && highlighted.length > 0
		? markFragment(highlighted[0], terms)
		: document.createTextNode(fallback || '');
}

/* --- getting to the rest ------------------------------------------------ */

function renderPager(result) {
	const pages = result.page && result.page.pages;

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

for(const [name, button] of Object.entries(elements.within)) {
	button.addEventListener('click', () => {
		const within = name === 'one';
		if(state.within === within) return;

		state.within = within;
		press(elements.within, name);
		run();
	});
}

for(const [rows, button] of Object.entries(elements.rows)) {
	button.addEventListener('click', () => {
		if(state.rows === rows) return;

		state.rows = rows;
		press(elements.rows, rows);
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
	state.price = null;
	state.pressed = null;
	state.inStock = false;
	run();
});

async function showSize() {
	try {
		elements.size.textContent = `${(await client.count()).toLocaleString('en')} records indexed`;
	} catch(error) {
		elements.size.textContent = '';
	}
}

/* Show what the URL asked for before the first search answers. */
elements.query.value = state.text;
press(elements.orders, state.order);
press(elements.within, state.within ? 'one' : 'any');
press(elements.rows, state.rows);

elements.indexName.textContent = client.config.index;
showSize();
run();
