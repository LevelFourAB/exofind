/*
 * The one thing about the sidebar that cannot be said where the sidebar is
 * declared.
 *
 * The REST API section is generated, and the plugin that generates it has a
 * single setting for whether a group arrives closed - one that closes the
 * section and every tag group inside it together. Closed tags are a second
 * door between the reader and an endpoint, so the section is left open there
 * and closed here instead, once the groups exist. The manual's own sections
 * need none of this: they are written in `./sidebar.mjs`, which says which of
 * them arrive closed and why.
 *
 * Starlight runs the plugin's middleware after this one, so the work is done
 * after `next()` - before it, the section is still the placeholder the plugin
 * replaces. A group Starlight is told is closed still opens itself on a page
 * inside it, which is what makes this a starting state rather than a rule.
 */

import { defineRouteMiddleware } from '@astrojs/starlight/route-data';

/**
 * The generated section that arrives closed, under the label
 * `../astro.config.mjs` gives it. A label that names no group is an error
 * rather than a section quietly staying open once it is renamed - the same
 * discipline `EXTRA` in `./nav.mjs` is held to, and the same three places
 * have to agree.
 */
const CLOSED = 'REST API';

export const onRequest = defineRouteMiddleware(async (context, next) => {
	await next();

	const { sidebar } = context.locals.starlightRoute;

	/* A page rendered without a sidebar - the front page, a demo - has none. */
	if(sidebar.length === 0) return;

	const group = sidebar.find(entry => entry.type === 'group' && entry.label === CLOSED);

	if(!group) {
		throw new Error(`The sidebar has no \`${CLOSED}\` group to arrive closed`);
	}

	group.collapsed = true;
});
