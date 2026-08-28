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

/** How far the sidebar was scrolled when the swap began. */
let scrolled = 0;

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
});
