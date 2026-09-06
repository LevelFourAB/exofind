/*
 * Searching this site with the engine this site documents.
 *
 * The dialog asks a node for the sections of the manual that answer what was
 * typed - the same index `website/search/load.mjs` writes, on the node the
 * demo pages search. Every keystroke is a search, because that is what the
 * engine is for.
 *
 * A node can be unreachable, and search is the first thing a reader who cannot
 * find something reaches for, so the dialog falls back to Pagefind: the static
 * index Starlight builds from the rendered pages, which is part of the
 * deployment and needs nothing running. The fallback is quieter - it knows the
 * page rather than the section, and it cannot forgive a typo - so the dialog
 * says which one answered.
 *
 * Pagefind is built into the site whether or not this component is what
 * searches it, so nothing has to be kept in step for the fallback to be there.
 * It is absent from a development server, which builds no such index; there,
 * an unreachable node is simply reported.
 */

import { createClient, resolveConfig } from '../examples/shared/client.js';

/**
 * What a node is asked to wrap a match in.
 *
 * Highlighted text arrives as it was written, tags and all - the manual is
 * full of prose about `<em>` and of paths spelled `{name}` - so the marks
 * around a match have to be two characters that cannot be in it. These are
 * control characters, which is what makes a fragment safe to split on and to
 * put on the page as text.
 */
const MARK_START = String.fromCharCode(1);
const MARK_END = String.fromCharCode(2);

/** Hits asked of the node - more than are shown, so that grouping has room. */
const LIMIT = 20;

/** Pages shown at once, and sections shown under one page. */
const PAGES = 6;
const SECTIONS = 4;

/** How long to wait for a pause in typing before searching. */
const DEBOUNCE_MS = 120;

/**
 * Where the reader's own recent searches are kept, and how many of them.
 *
 * They are theirs alone: written by this dialog into this browser, and read by
 * nothing else. A search is remembered once it has led somewhere - the reader
 * followed one of its results - rather than on every keystroke, which would
 * fill the list with the halves of one word.
 */
const RECENT_KEY = 'exofind:recent-searches';
const RECENT = 5;

/**
 * Counts what the dialog has had to name, so that no two names are the same.
 *
 * A drawn element is named only where something has to point at it: the result
 * the reader is on, which `aria-activedescendant` names, and the title of a
 * page, which labels the group of sections under it.
 */
let named = 0;

/** The identifier of one element, given to it the first time it is asked for. */
function identify(element) {
	return element.id ||= `site-search-${++named}`;
}

const client = createClient(resolveConfig({ index: 'docs' }));

class SiteSearch extends HTMLElement {
	constructor() {
		super();

		this.openButton = this.querySelector('button[data-open-modal]');
		this.closeButton = this.querySelector('button[data-close-modal]');
		this.dialog = this.querySelector('dialog');
		this.frame = this.querySelector('.dialog-frame');
		this.input = this.querySelector('input[type="search"]');
		this.status = this.querySelector('[data-status]');
		this.results = this.querySelector('[data-results]');
		this.empty = this.querySelector('[data-empty]');
		this.recent = this.querySelector('[data-recent]');
		this.recentList = this.querySelector('[data-recent-list]');

		/** Searches already sent are given up on rather than left to arrive. */
		this.generation = 0;
		this.running = null;
		this.timer = null;

		/** Set once a node has failed, so the fallback answers straight away. */
		this.fallenBack = false;

		/** The result the arrow keys are on, which Enter follows. */
		this.active = null;

		this.bindDialog();
		this.bindSearch();
	}

	/* --- opening and closing --------------------------------------------- */

	bindDialog() {
		const onClick = event => {
			const link = event.target instanceof Element && event.target.closest('a');

			if(link || (document.body.contains(event.target) && !this.frame.contains(event.target))) {
				this.close();
			}
		};

		this.open = event => {
			this.dialog.showModal();
			document.body.toggleAttribute('data-search-modal-open', true);
			this.input.setAttribute('aria-expanded', 'true');

			/*
			 * The text of the last search is still in the box, because the
			 * header keeps this element across a routed navigation. Selecting
			 * it leaves the reader one keystroke from a different search and
			 * one keypress from the same one again.
			 */
			this.input.focus();
			this.input.select();

			this.drawEmpty();
			event?.stopPropagation();
			window.addEventListener('click', onClick);
		};

		this.close = () => this.dialog.close();

		this.openButton.addEventListener('click', this.open);
		this.openButton.disabled = false;
		this.closeButton.addEventListener('click', this.close);

		this.dialog.addEventListener('close', () => {
			document.body.toggleAttribute('data-search-modal-open', false);
			this.input.setAttribute('aria-expanded', 'false');
			window.removeEventListener('click', onClick);
		});

		window.addEventListener('keydown', event => {
			if((event.metaKey || event.ctrlKey) && event.key === 'k') {
				this.dialog.open ? this.close() : this.open();
				event.preventDefault();
			}
		});
	}

	/* --- searching -------------------------------------------------------- */

	bindSearch() {
		/*
		 * Enter follows the result the reader walked to, and the first one when
		 * they have walked to none - what was typed is a question, and the
		 * dialog's answer to it is at the top.
		 */
		this.querySelector('form').addEventListener('submit', event => {
			event.preventDefault();

			const going = this.active ?? this.results.querySelector('a');
			if(going) going.click();
		});

		this.input.addEventListener('input', () => {
			this.select(null);

			clearTimeout(this.timer);
			this.timer = setTimeout(() => this.search(), DEBOUNCE_MS);
		});

		/*
		 * A search that led somewhere is one worth offering again. Recorded on
		 * the way out, so that a reader who comes back to the dialog is offered
		 * the questions they have asked rather than everything they have typed.
		 */
		this.results.addEventListener('click', event => {
			if(event.target.closest('a')) remember(this.input.value.trim());
		});

		this.recentList.addEventListener('click', event => {
			const button = event.target.closest('button');
			if(!button) return;

			this.input.value = button.dataset.search;
			this.input.focus();
			this.search();
		});

		this.input.addEventListener('keydown', event => {
			if(event.key === 'ArrowDown') this.move(1);
			else if(event.key === 'ArrowUp') this.move(-1);
			else return;

			event.preventDefault();
		});
	}

	/* --- walking the results ---------------------------------------------- */

	/**
	 * The results the arrow keys walk, in the order they are drawn in: whichever
	 * of the two the dialog is showing - the results, or what it offers before a
	 * search.
	 */
	get walkable() {
		const showing = this.empty.hidden ? this.results : this.empty;
		return [...showing.querySelectorAll('a, button')];
	}

	/**
	 * Walk one result up or down, and wrap at either end.
	 *
	 * The keyboard stays in the text box the whole way, so a reader who walked
	 * past what they wanted can go on typing rather than having to get back to
	 * the box first. Which result they are on is carried by
	 * `aria-activedescendant` instead of by focus, which is what a screen reader
	 * reads the result out from.
	 */
	move(step) {
		const walkable = this.walkable;
		if(walkable.length === 0) return;

		const at = walkable.indexOf(this.active);
		const next = at === -1
			? (step === 1 ? 0 : walkable.length - 1)
			: (at + step + walkable.length) % walkable.length;

		this.select(walkable[next]);
	}

	/** Put the reader on one result, or on none when given nothing. */
	select(result) {
		if(this.active) {
			this.active.removeAttribute('data-active');
			this.active.removeAttribute('aria-selected');
		}

		this.active = result ?? null;

		if(!this.active) {
			this.input.removeAttribute('aria-activedescendant');
			return;
		}

		this.active.setAttribute('data-active', '');
		this.active.setAttribute('aria-selected', 'true');

		this.input.setAttribute('aria-activedescendant', identify(this.active));
		this.active.scrollIntoView({ block: 'nearest' });
	}

	/**
	 * Point the text box at the list it is now showing, and put the reader on
	 * none of it. Called after anything is drawn, because the result they were
	 * on is not on the page any more.
	 */
	retarget() {
		this.select(null);
		this.input.setAttribute('aria-controls', (this.empty.hidden ? this.results : this.empty).id);
	}

	async search() {
		const text = this.input.value.trim();

		if(this.running) this.running.abort();

		const mine = ++this.generation;

		if(!text) {
			this.report('');
			this.results.replaceChildren();
			this.drawEmpty();
			return;
		}

		this.empty.hidden = true;
		this.retarget();

		const controller = new AbortController();
		this.running = controller;

		try {
			const answer = this.fallenBack
				? await fromPagefind(text)
				: await this.fromNode(text, controller.signal);

			if(mine !== this.generation) return;

			this.draw(text, answer);
		} catch(error) {
			if(controller.signal.aborted || mine !== this.generation) return;

			this.report('Nothing is answering searches right now.');
			this.results.replaceChildren();
			this.retarget();
		} finally {
			if(this.running === controller) this.running = null;
		}
	}

	/**
	 * Search the node, and fall back to the static index when it will not
	 * answer. The fallback is remembered for the rest of the visit, so that a
	 * node that is down costs one failed request rather than one per keystroke.
	 */
	async fromNode(text, signal) {
		try {
			return await searchNode(text, signal);
		} catch(error) {
			if(signal.aborted) throw error;

			this.fallenBack = true;
			return fromPagefind(text);
		}
	}

	/* --- drawing ---------------------------------------------------------- */

	/*
	 * The status line carries what the results cannot say themselves. A search
	 * the node answered says nothing: how many sections matched and how long it
	 * took are the engine's business, and the reader is here to read a page.
	 */
	draw(text, { pages, source }) {
		if(pages.length === 0) {
			this.report(`Nothing in the manual matches ${text}.`);
			this.results.replaceChildren();
			this.retarget();
			return;
		}

		this.report(source === 'node'
			? ''
			: 'No node answered - searching the index built with the site');

		this.results.replaceChildren(...pages.map(page => drawPage(page)));
		this.retarget();
	}

	/**
	 * Draw what the dialog holds when nothing has been typed: the searches the
	 * reader has made before, over the pages everybody starts at.
	 *
	 * The pages are in the markup already. Only the searches are drawn here,
	 * and their heading goes with them when there are none, so a reader who has
	 * never searched is not shown an empty list of their own history.
	 */
	drawEmpty() {
		const searches = recalled();

		this.recent.hidden = searches.length === 0;
		this.recentList.replaceChildren(...searches.map(search => {
			const item = document.createElement('li');
			item.setAttribute('role', 'none');

			const button = document.createElement('button');
			button.type = 'button';
			button.setAttribute('role', 'option');
			button.dataset.search = search;
			button.textContent = search;

			item.append(button);
			return item;
		}));

		this.empty.hidden = Boolean(this.input.value.trim());
		this.retarget();
	}

	report(message) {
		this.status.textContent = message;
	}
}

/* --- searches the reader has made before --------------------------------- */

/**
 * The recent searches, newest first.
 *
 * Reading them can throw rather than answer nothing - a browser told to keep
 * no site data refuses the property itself - so a dialog that cannot reach
 * them offers the pages alone.
 */
function recalled() {
	try {
		const stored = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]');
		return Array.isArray(stored) ? stored.filter(entry => typeof entry === 'string') : [];
	} catch(error) {
		return [];
	}
}

/** Put a search at the top of that list, and drop the oldest past the limit. */
function remember(search) {
	if(!search) return;

	try {
		const kept = [search, ...recalled().filter(entry => entry !== search)].slice(0, RECENT);
		localStorage.setItem(RECENT_KEY, JSON.stringify(kept));
	} catch(error) {
		// A browser that keeps no site data simply offers no history
	}
}

/*
 * The rows of the list the text box is walked through, so a page is a group of
 * sections labelled by its title. The list items themselves carry no role: a
 * list inside a list of results is how the sections are laid out, not something
 * a reader is told about.
 */
function drawPage(page) {
	const item = document.createElement('li');
	item.className = 'result';
	item.setAttribute('role', 'group');

	const head = document.createElement('p');
	head.className = 'result__page';
	head.append(marked(page.title));

	item.setAttribute('aria-labelledby', identify(head));

	if(page.part) {
		const part = document.createElement('span');
		part.className = 'result__part';
		part.textContent = page.part;
		head.append(part);
	}

	const sections = document.createElement('ul');
	sections.className = 'result__sections';
	sections.setAttribute('role', 'none');
	sections.append(...page.sections.map(section => drawSection(section)));

	item.append(head, sections);
	return item;
}

function drawSection(section) {
	const item = document.createElement('li');
	item.setAttribute('role', 'none');

	const link = document.createElement('a');
	link.href = section.url;
	link.className = 'section';
	link.setAttribute('role', 'option');

	const heading = document.createElement('span');
	heading.className = 'section__heading';
	heading.append(marked(section.heading));

	const snippet = document.createElement('span');
	snippet.className = 'section__snippet';
	snippet.append(marked(section.snippet));

	link.append(heading, snippet);
	item.append(link);

	return item;
}

/**
 * Text with the marks around what matched turned into elements.
 *
 * Built out of text nodes rather than assigned as HTML, so that nothing a node
 * or an index answers with reaches the page as markup.
 *
 * @param {string} text marked with the two control characters above
 */
function marked(text) {
	const out = document.createDocumentFragment();

	for(const [position, part] of String(text ?? '').split(MARK_START).entries()) {
		if(position === 0) {
			out.append(part);
			continue;
		}

		const [hit, rest = ''] = part.split(MARK_END);

		const mark = document.createElement('mark');
		mark.textContent = hit;

		out.append(mark, rest);
	}

	return out;
}

/* --- the node ------------------------------------------------------------ */

/**
 * What was typed, searched for over the title of a page, the heading of a
 * section and the text under it.
 *
 * The three are one clause rather than three, so that a word in the title and
 * a word in the text still count as one document matching both - which is what
 * the weights in `website/search/definition.json` are relative to.
 *
 * Every word is optional, and a section that holds more of them ranks above
 * one that holds fewer. A manual is searched by describing a problem rather
 * than by naming a document, so requiring every word answers a question with
 * nothing at all - and answers it with the wrong page when some page happens
 * to hold all of the words. The words that make a question are dropped by the
 * index rather than here; the stopword list is in the definition.
 *
 * The lead of a page is boosted because a page is often searched for by name,
 * and what answers "facets" is the top of the page about facets rather than
 * whichever of its sections happens to say the word most often.
 */
function requestFor(text) {
	const marks = { pre: MARK_START, post: MARK_END };

	return {
		query: [
			{
				type: 'text',
				text,
				match: 'any',
				fields: { title: null, heading: null, text: null }
			},
			{
				type: 'boost',
				weight: 1.5,
				clauses: [{ field: 'lead', match: { value: true } }]
			}
		],
		fields: ['url', 'title', 'heading', 'part', 'excerpt'],
		highlight: {
			fields: {
				title: { fragments: 1, ...marks },
				heading: { fragments: 1, ...marks },
				text: { fragments: 1, length: 160, ...marks }
			}
		},
		limit: LIMIT
	};
}

async function searchNode(text, signal) {
	const result = await client.search(requestFor(text), signal);

	const hits = result.hits.map(hit => ({
		url: hit.document.url,
		title: fragmentOf(hit, 'title') ?? hit.document.title,
		part: hit.document.part,
		heading: fragmentOf(hit, 'heading') ?? hit.document.heading ?? 'Overview',
		snippet: fragmentOf(hit, 'text') ?? hit.document.excerpt ?? ''
	}));

	return { pages: grouped(hits), source: 'node' };
}

/** The first highlighted fragment of one field, when the search marked it. */
function fragmentOf(hit, field) {
	const fragments = hit.highlights && hit.highlights[field];
	return fragments && fragments.length > 0 ? fragments[0] : null;
}

/**
 * Sections gathered under the page they are on.
 *
 * The order is the order the sections came back in, so the best section still
 * decides where its page sits. A page that answers in several places is worth
 * showing several times over; a page that answers in a dozen is a page, and
 * showing all of it would push every other page out of the dialog.
 */
function grouped(hits) {
	const pages = new Map();

	for(const hit of hits) {
		const key = hit.url.split('#')[0];
		const page = pages.get(key);

		if(!page) {
			pages.set(key, { title: hit.title, part: hit.part, sections: [hit] });
			continue;
		}

		if(page.sections.length < SECTIONS) page.sections.push(hit);
	}

	return [...pages.values()].slice(0, PAGES);
}

/* --- the static index ---------------------------------------------------- */

let pagefind = null;

/**
 * The index Starlight builds from the rendered pages.
 *
 * It is loaded the first time it is needed rather than with the page, because
 * on a visit where the node answers it is never needed at all.
 */
function loadPagefind() {
	const base = import.meta.env.BASE_URL.replace(/\/$/, '');

	pagefind ??= import(/* @vite-ignore */ `${base}/pagefind/pagefind.js`)
		.then(async module => {
			await module.options({ baseUrl: `${base}/` });
			await module.init();

			return module;
		});

	return pagefind;
}

async function fromPagefind(text) {
	const index = await loadPagefind();
	const found = await index.search(text);

	const pages = await Promise.all(found.results.slice(0, PAGES).map(result => result.data()));

	return {
		pages: pages
			.map(page => ({
				title: page.meta?.title ?? page.url,
				part: null,
				sections: (page.sub_results ?? []).slice(0, SECTIONS).map(section => ({
					url: section.url,
					heading: section.title ?? 'Overview',
					snippet: fromPagefindHtml(section.excerpt ?? '')
				}))
			}))
			.filter(page => page.sections.length > 0),
		source: 'pagefind'
	};
}

/**
 * A Pagefind excerpt as text marked the way a node marks one.
 *
 * Pagefind answers with HTML that wraps each match in `<mark>`, so it is read
 * as a document and written back out with the two control characters around
 * the same words. Everything the dialog draws then takes one shape, whichever
 * index answered.
 */
function fromPagefindHtml(html) {
	const source = document.createElement('template');
	source.innerHTML = html;

	let text = '';

	for(const node of source.content.childNodes) {
		text += node.nodeType === Node.TEXT_NODE
			? node.textContent
			: `${MARK_START}${node.textContent}${MARK_END}`;
	}

	return text;
}

customElements.define('site-search', SiteSearch);
