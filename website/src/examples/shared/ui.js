/*
 * The controls an example page draws from a result: facets, highlighted
 * names, marked compound seams. Everything here takes the shapes the search
 * API answers with and gives back DOM, so a page is left with its own
 * arrangement of them and nothing else.
 */

/**
 * Wait for a pause in typing before searching.
 *
 * The returned function can be cancelled, because anything the reader does
 * deliberately - ticking a filter, picking a suggestion - should search for
 * what they have already typed rather than leaving a keystroke to arrive
 * behind it.
 */
export function debounce(fn, ms = 120) {
	let timer;

	const later = (...args) => {
		clearTimeout(timer);
		timer = setTimeout(() => fn(...args), ms);
	};

	later.cancel = () => clearTimeout(timer);

	return later;
}

/**
 * Draw a value facet as a list of checkboxes.
 *
 * Values that have been ticked are kept in the list whether or not the counts
 * hold them, because dropping a ticked filter out of sight leaves no way to
 * untick it. A value the counts do not mention is drawn without one rather
 * than as zero - a facet answers its most common values up to its limit, so a
 * value missing from them has not been counted rather than counted as none.
 */
export function renderValues(container, { facet, chosen, name, onToggle }) {
	const counted = facet.values.map(value => value.value);
	const all = [...new Set([...chosen, ...counted])];

	container.replaceChildren(...all.map((value, position) => {
		const counts = facet.values.find(candidate => candidate.value === value);

		return choice({
			type: 'checkbox',
			id: `facet-${name}-${position}`,
			label: value,
			count: counts ? counts.count : null,
			checked: chosen.has(value),
			onChange: box => onToggle(value, box.checked)
		});
	}));
}

/**
 * Draw a range facet as a list of radios, one per bucket the search asked to
 * be counted into, led by an `Any` that stands for no range at all.
 *
 * `Any` is what takes a range back, and it is drawn whether or not one is
 * picked, so the list says it holds one range at a time before anything is
 * clicked. Picking the bucket that is already picked clears it as well, for a
 * reader who reaches for the tick they just made rather than for the top of
 * the list; that happens on the click, because a radio that is already checked
 * has nothing to change, while picking happens on the change so the arrow keys
 * work.
 *
 * A bucket is labelled by its bounds unless the page passes a `describe` of
 * its own - the bounds are numbers of whatever the field holds, and only the
 * page knows whether they are prices, grams or years before the common era.
 */
export function renderRanges(
	container,
	{ facet, ranges, chosen, name, onPick, describe = describeBucket }
) {
	/*
	 * No count on `Any`: the buckets are the page's to choose and need neither
	 * cover the field nor be disjoint, so no sum of them is the number of
	 * documents picking it would leave.
	 */
	const any = choice({
		type: 'radio',
		id: `facet-${name}-any`,
		group: name,
		label: 'Any',
		count: false,
		checked: chosen === null || chosen === undefined,
		onChange: () => onPick(null)
	});

	any.classList.add('choice--any');

	container.replaceChildren(any, ...facet.buckets.map((bucket, position) => {
		const range = ranges[position];
		const picked = chosen === range;

		const control = choice({
			type: 'radio',
			id: `facet-${name}-${position}`,
			group: name,
			label: describe(bucket),
			count: bucket.count,
			checked: picked,
			onChange: () => onPick(range)
		});

		if(picked) {
			const box = control.querySelector('input');
			control.title = 'Pick again to clear';
			box.addEventListener('click', () => onPick(null));
			box.addEventListener('keydown', event => {
				if(event.key === ' ' || event.key === 'Backspace') {
					event.preventDefault();
					onPick(null);
				}
			});
		}

		return control;
	}));
}

/**
 * One row of a facet: a control, a label and how many documents it holds.
 *
 * `count` is that number, `null` for a value the counts never mentioned - it
 * is drawn as a dot, because a facet answers its most common values up to its
 * limit and a value missing from them has not been counted rather than counted
 * as none - and `false` for a row there is no number to draw for at all, which
 * is also the one kind of row that is never dimmed as empty.
 */
function choice({ type, id, group, label, count, checked, onChange }) {
	const empty = count !== false && !(count > 0) && !checked;

	const row = document.createElement('label');
	row.className = empty ? 'choice choice--empty' : 'choice';
	row.htmlFor = id;

	const box = document.createElement('input');
	box.type = type;
	box.id = id;
	if(group) box.name = group;
	box.checked = checked;
	box.addEventListener('change', () => onChange(box));

	const text = document.createElement('span');
	text.className = 'choice__label';
	text.textContent = label;

	// Drawn even when it is blank, so the labels of a list stay one column wide
	const number = document.createElement('span');
	number.className = 'choice__count';
	number.textContent = count === false ? '' : count ?? '·';

	row.append(box, text, number);
	return row;
}

export function describeBucket(bucket) {
	if(bucket.from === undefined || bucket.from === null) return `under ${bucket.to}`;
	if(bucket.to === undefined || bucket.to === null) return `${bucket.from} and up`;
	return `${bucket.from}–${bucket.to}`;
}

/**
 * Run a render that replaces controls, and hand the keyboard back afterwards.
 *
 * Facets are drawn again from the counts that came back, which throws away
 * the input that was being used. Each one is drawn under the same id as
 * before, so whatever had the focus can be given it back and ticking a filter
 * does not end a run through the list.
 */
export function keepingFocus(render) {
	const focused = document.activeElement && document.activeElement.id;

	render();

	if(focused) {
		const again = document.getElementById(focused);
		if(again) again.focus();
	}
}

/**
 * Render a highlighted fragment, marking where inside a matched word the text
 * that was searched for sits.
 *
 * A search that splits compounds highlights `gravlaxsås` whole for a hit on
 * `sås`, because what matched is a part of the word. Finding that part again
 * is what lets the mark sit under the letters that were typed rather than
 * under the whole word - and a word the index matched some other way, through
 * stemming or a typo, has no such part and is marked whole.
 *
 * The fragment is rebuilt from its text rather than assigned as HTML, so
 * nothing a node answers with reaches the page as markup.
 *
 * @param {string} fragment a highlighted fragment, `<em>` around what matched
 * @param {string[]} terms the words that were searched for
 */
export function markFragment(fragment, terms) {
	const source = document.createElement('template');
	source.innerHTML = fragment;

	const out = document.createDocumentFragment();

	for(const node of source.content.childNodes) {
		if(node.nodeType === Node.TEXT_NODE) {
			out.append(node.textContent);
		} else {
			out.append(markWord(node.textContent, terms));
		}
	}

	return out;
}

function markWord(word, terms) {
	const mark = document.createElement('span');
	mark.className = 'mark';

	const folded = word.toLowerCase();
	const found = terms
		.map(term => ({ term, at: folded.indexOf(term.toLowerCase()) }))
		.filter(hit => hit.at >= 0 && hit.term.length < word.length)
		.sort((a, b) => a.at - b.at)[0];

	if(!found) {
		mark.append(part(word));
		return mark;
	}

	const end = found.at + found.term.length;

	if(found.at > 0) mark.append(rest(word.slice(0, found.at)));
	mark.append(part(word.slice(found.at, end)));
	if(end < word.length) mark.append(rest(word.slice(end)));

	return mark;
}

function part(text) {
	const span = document.createElement('span');
	span.className = 'mark__part';
	span.textContent = text;
	return span;
}

function rest(text) {
	const span = document.createElement('span');
	span.className = 'mark__rest';
	span.textContent = text;
	return span;
}

/** The words a search was for, as the seam marking looks for them. */
export function termsOf(text) {
	return text.split(/\s+/).filter(Boolean);
}
