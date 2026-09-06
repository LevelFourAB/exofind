/*
 * What a demo shows while it is waiting for an answer.
 *
 * Two things, because there are two different waits. The first search of a
 * page has nothing on screen to keep the reader busy, so the shape of an
 * answer is drawn in place of one: rows where the values of each facet will
 * be, and results where the results will be. Without them a demo opens as a
 * column of empty headings over a few buttons, which reads as a page that has
 * broken rather than as one that is a moment from being full.
 *
 * Every search after that already has the last answer on screen, and taking it
 * away to draw the shape of the next one would be a step backwards - so those
 * are said by a spinner in the search field instead, and the results stand
 * until they are replaced.
 *
 * The spinner waits before it appears. A node on the same machine answers in a
 * few milliseconds, and a mark that flashes on every keystroke is noise rather
 * than news; one that appears only when a search takes long enough to be
 * noticed says something worth saying.
 *
 * The look of all of it is in `./exofind.css`, under `waiting`.
 */

/** How long a search runs before it is worth saying that it is running. */
const SAY_AFTER = 250;

/**
 * How far apart in time the rows of one list pulse, in milliseconds.
 *
 * Enough that a column reads as a list of things arriving one after another,
 * and little enough that the whole of it is clearly one thing waiting.
 */
const STAGGER = 70;

/**
 * The widths a bar takes, in percent of what it is drawn in, walked in order.
 *
 * Bars all of one length read as a table that has been ruled rather than as
 * text that has not arrived, so they vary the way the values of a facet do.
 */
const WIDTHS = [86, 62, 94, 71, 55, 83, 66, 90, 58, 76];

/**
 * How much of that width a bar takes where the results are a list.
 *
 * A list of results is as wide as the page, and what arrives in it is a name
 * over a line of figures rather than a paragraph - so a bar drawn to the
 * width of the column would set the reader up for the wrong thing. A wall of
 * results is a column the width of one result and needs no such cut.
 */
const OF_A_ROW = 0.45;

/**
 * Draw the shape of an answer, and hand back what a running search is said
 * with.
 *
 * The placeholders are drawn as this is called, which is before the first
 * search is sent, and they live only until something replaces them: every
 * facet and every result is drawn with `replaceChildren`, so the first answer
 * clears them wherever it lands.
 *
 * @param {object} shape what the page is about to fill
 * @param {{into: Element, rows: number}[]} shape.facets
 *   the facet containers, and how many rows to draw in each - the buckets of a
 *   range facet exactly, and as much of a value facet as is worth showing
 * @param {{into: Element, rows: number, shape: string}} shape.hits
 *   where the results go, how many to draw, and whether they are read down a
 *   list (`row`) or seen across a wall (`tile`)
 * @returns {{searching: Function, done: Function, failed: Function}}
 */
export function createWaiting({ facets = [], hits = null } = {}) {
	const spinner = spinnerIn(document.querySelector('.ask__field'));

	for(const facet of facets) fill(facet.into, facet.rows, choiceRow);
	if(hits) fill(hits.into, hits.rows, hits.shape === 'tile' ? hitTile : hitRow);

	/*
	 * How many searches are running rather than whether one is. Starting a
	 * search gives up on the one before it, and the one that was given up on
	 * ends after the one that replaced it has begun, so a spinner switched off
	 * by whichever search ends first would go out in the middle of a run of
	 * keystrokes. Counting keeps it up until the last of them is answered.
	 */
	let running = 0;
	let appear = null;

	return {
		/** A search has been sent. */
		searching() {
			running++;

			if(spinner && appear === null) {
				appear = setTimeout(() => { spinner.hidden = false; }, SAY_AFTER);
			}
		},

		/** A search has been answered, has failed, or has been given up on. */
		done() {
			running = Math.max(running - 1, 0);
			if(running > 0) return;

			clearTimeout(appear);
			appear = null;

			if(spinner) spinner.hidden = true;
		},

		/**
		 * Take whatever placeholders are left off the page.
		 *
		 * A search that failed is answered by nothing, so the shape it would
		 * have filled is never replaced - and a page left drawing that shape
		 * under an error says the search is still running.
		 */
		failed() {
			for(const drawn of document.querySelectorAll('.placeholder')) drawn.remove();
		}
	};
}

/**
 * Put a spinner in the search field.
 *
 * It is drawn for the eye alone: what a search answered is said by the status
 * line, which is a live region, and a second thing announcing that a search
 * has started would talk over the answer to the one before it.
 */
function spinnerIn(field) {
	if(!field) return null;

	const mark = element('span', 'ask__spinner');
	mark.hidden = true;
	mark.setAttribute('aria-hidden', 'true');
	field.append(mark);

	return mark;
}

function fill(into, rows, build) {
	if(!into) return;

	into.replaceChildren(...Array.from({ length: rows }, (nothing, position) => {
		const drawn = build(position);
		drawn.style.animationDelay = `${position * STAGGER}ms`;

		return drawn;
	}));
}

/** One row of a facet: the control, the value and how many hold it. */
function choiceRow(position) {
	const row = element('div', 'placeholder placeholder--choice');

	row.append(
		element('span', 'placeholder__box'),
		bar('placeholder__label', position),
		element('span', 'placeholder__count')
	);

	return row;
}

/** One result of a list: what it is called, and the line under it. */
function hitRow(position) {
	const item = element('li', 'placeholder placeholder--row');

	item.append(
		bar('placeholder__name', position, OF_A_ROW),
		bar('placeholder__meta', position + 3, OF_A_ROW)
	);

	return item;
}

/** One result of a wall: the picture, what it is called and the line under it. */
function hitTile(position) {
	const item = element('li', 'placeholder placeholder--tile');

	item.append(
		element('span', 'placeholder__block'),
		bar('placeholder__name', position),
		bar('placeholder__meta', position + 5)
	);

	return item;
}

function bar(className, position, of = 1) {
	const drawn = element('span', className);
	drawn.style.width = `${Math.round(WIDTHS[position % WIDTHS.length] * of)}%`;

	return drawn;
}

function element(tag, className) {
	const made = document.createElement(tag);
	made.className = className;

	return made;
}
