/*
 * Opening and closing the facet column, and saying how much of it is in use.
 *
 * Only the width the column folds at is the stylesheet's; everything here is
 * the same on every screen. An open column is one class on the wrapper, which
 * a wide screen has no rule for, so a reader who folds the facets away and
 * then turns the phone finds them where they belong rather than gone.
 *
 * The component that draws the markup is `./Filters.astro`.
 */

/**
 * What counts as a filter in use.
 *
 * A ticked box, a bucket that is not `Any`, and a button that is pressed -
 * which is how the demos draw values, ranges and the places to measure from.
 * A radio that is disabled belongs to a group that is waiting for something
 * else to be picked first, and is not a filter until it is.
 */
const IN_USE = [
	'input[type="checkbox"]:checked',
	'.choice:not(.choice--any) > input[type="radio"]:checked:not(:disabled)',
	'[aria-pressed="true"]'
].join(', ');

/** Scroll the way the reader asked to be moved, or not moved. */
const behavior = window.matchMedia('(prefers-reduced-motion: reduce)').matches
	? 'auto'
	: 'smooth';

for(const root of document.querySelectorAll('.filters')) {
	wire(root);
}

function wire(root) {
	const toggle = root.querySelector('.filters__toggle');
	const panel = root.querySelector('.filters__panel');
	const on = root.querySelector('.filters__on');
	const done = root.querySelector('.filters__done');

	if(!toggle || !panel) return;

	toggle.addEventListener('click', () => {
		open(root, toggle, !root.classList.contains('filters--open'));
	});

	/*
	 * The way back to the results, for a list of facets long enough that the
	 * line which opened it has been scrolled off. Closing takes the panel out
	 * from under whatever had the focus, so the focus is handed back to the
	 * line first and the results are scrolled to after.
	 */
	done.addEventListener('click', () => {
		open(root, toggle, false);
		toggle.focus({ preventScroll: true });

		const results = root.closest('.split')?.querySelector('.results');
		if(results) results.scrollIntoView({ block: 'start', behavior });
	});

	/*
	 * How many groups are in use is read back off the controls rather than
	 * tracked, because every answer draws the facets again and the page that
	 * did the ticking is the only one that knows what it means. Replacing the
	 * controls is what the observer sees; a tick that has not been answered
	 * yet is what the change event is for.
	 */
	const count = () => {
		const groups = panel.querySelectorAll('.filters__group');
		const used = [...groups].filter(group => group.querySelector(IN_USE)).length;

		on.textContent = `${used} on`;
		on.hidden = used === 0;
	};

	new MutationObserver(count).observe(panel, {
		subtree: true,
		childList: true,
		attributes: true,
		attributeFilter: ['aria-pressed', 'disabled']
	});

	panel.addEventListener('change', count);
	count();
}

function open(root, toggle, opened) {
	root.classList.toggle('filters--open', opened);
	toggle.setAttribute('aria-expanded', String(opened));
}
