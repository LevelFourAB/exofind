/*
 * What a routed navigation has to carry across for itself.
 *
 * The client router replaces the root attributes, the head and the body with
 * the ones the next document was built with. Starlight settles two things in
 * the head of the document being replaced, so both are lost by the swap that
 * is meant to be invisible - this module hands them over instead.
 *
 * The listeners are on `document`, which the router keeps, so the module is
 * evaluated once per full page load however many routed navigations follow.
 * Nothing here runs on a page without the router: the events are the router's.
 */

/** The sidebar's scroller, and the only element this needs by name. */
const SIDEBAR = 'starlight__sidebar';

/** Compare two paths without letting a trailing slash decide it. */
const samePath = (a, b) => a.replace(/\/+$/, '') === b.replace(/\/+$/, '');

/** The class that draws the bar saying a page is on its way. */
const NAVIGATING = 'is-navigating';

/** How far the sidebar was scrolled when the swap began. */
let scrolled = 0;

/** How much of the list to keep on either side of the current entry. */
const EDGE = 32;

/**
 * Scroll the sidebar to where the reader is, when they are not already looking
 * at it.
 *
 * A link followed from the text, or from the row of parts at the top, lands on
 * a page whose entry can be anywhere in a sidebar that was left where the
 * previous page had it - including past either end of it, which reads as a
 * manual with no marked place in it. An entry that is not on screen is centred
 * rather than brought just inside the frame, because what the reader wants from
 * it is the pages around it.
 *
 * An entry that is on screen is where the reader put it, and every navigation
 * from the sidebar itself is one of those, so it is moved as little as the
 * margin allows: enough to show the entries next to it, and never enough to
 * read as the list jumping.
 */
function reveal(sidebar, link) {
	const frame = sidebar.getBoundingClientRect();
	const entry = link.getBoundingClientRect();

	/* A sidebar folded into the menu is not a scroller and has no height. */
	if(frame.height === 0) return;

	const above = entry.top - frame.top;
	const below = frame.bottom - entry.bottom;
	if(above >= EDGE && below >= EDGE) return;

	sidebar.scrollTop += above < 0 || below < 0
		? above - (frame.height - entry.height) / 2
		: (above < EDGE ? above - EDGE : EDGE - below);
}

/*
 * A click on a link paints nothing until the next page has been fetched, and
 * on a slow connection that is a page that looks like it ignored the click.
 * The class is the whole indicator - what it draws, and the wait before it
 * draws anything, are in `./styles/site.css`.
 *
 * Preparation is the fetch, so the class is on for exactly as long as the
 * reader is waiting on the network. `page-load` clears it as well, because a
 * navigation that fails or is abandoned ends the fetch without ending it the
 * way the pair below expects.
 */
document.addEventListener('astro:before-preparation', () => {
	document.documentElement.classList.add(NAVIGATING);
});

for(const event of ['astro:after-preparation', 'astro:page-load']) {
	document.addEventListener(event, () => {
		document.documentElement.classList.remove(NAVIGATING);
	});
}

document.addEventListener('astro:before-swap', event => {
	const arriving = event.newDocument.documentElement;

	/*
	 * Starlight picks the theme in an inline script, so a built document
	 * carries the default rather than what the reader chose, and the swap
	 * copies every root attribute across. Handing the live theme over is what
	 * keeps a routed navigation from flashing the other one.
	 */
	arriving.dataset.theme = document.documentElement.dataset.theme;

	/*
	 * The sidebar is scrolled and opened by the reader, and Starlight restores
	 * neither after a swap: it reads them from session storage in an inline
	 * script, which the router will not run twice. Marking the two sidebars as
	 * the same element makes the swap keep the live one, along with its scroll
	 * position, the groups the reader opened and the listeners Starlight
	 * attached to it. The directive that would say this at build time sits on a
	 * layout this site does not render itself.
	 */
	const live = document.getElementById(SIDEBAR);
	const next = event.newDocument.getElementById(SIDEBAR);
	if(live && next) {
		live.setAttribute('data-astro-transition-persist', SIDEBAR);
		next.setAttribute('data-astro-transition-persist', SIDEBAR);
		scrolled = live.scrollTop;
	}
});

document.addEventListener('astro:after-swap', () => {
	/*
	 * A scroller taken out of the document and put back is a scroller at the
	 * top, and the browser then moves it far enough to show whatever still
	 * has focus - which is the link the reader clicked, not where they were
	 * reading. Putting the recorded position back is what makes keeping the
	 * element worth anything. It happens here rather than on `page-load`,
	 * because the transition has not been painted yet.
	 */
	const sidebar = document.getElementById(SIDEBAR);
	if(sidebar) sidebar.scrollTop = scrolled;
});

document.addEventListener('astro:page-load', () => {
	const sidebar = document.getElementById(SIDEBAR);
	if(!sidebar) return;

	/*
	 * Keeping the live sidebar keeps the previous page marked as the current
	 * one, because which link is current is the one thing the arriving markup
	 * knew that this one does not. It is worked out again from the address.
	 */
	let current = null;
	for(const link of sidebar.querySelectorAll('a[href]')) {
		const here = samePath(new URL(link.href).pathname, location.pathname);
		link.setAttribute('aria-current', here ? 'page' : 'false');
		if(here) current = link;
	}

	/* A page reached from a group the reader had closed opens its group. */
	for(
		let group = current?.closest('details');
		group;
		group = group.parentElement?.closest('details')
	) {
		group.open = true;
	}

	if(current) reveal(sidebar, current);
});
