/*
 * The two things about a page that cannot be said where the page is declared:
 * the state the generated part of the sidebar arrives in, and the link preview
 * image.
 *
 * The REST API section is generated, and the plugin that generates it has a
 * single setting for whether a group arrives closed - one that closes the
 * section and every tag group inside it together. Closed tags are a second
 * door between the reader and an endpoint, so the section is left open there
 * and closed here instead, once the groups exist. The manual's own sections
 * need none of this: they are written in `./sidebar.mjs`, which says which of
 * them arrive closed and why.
 *
 * Starlight runs the plugin's middleware after this one, so both jobs are done
 * after `next()`. Before it the API section is still the placeholder the
 * plugin replaces, and the head Starlight assembles is not there to add to. A
 * group Starlight is told is closed still opens itself on a page inside it, so
 * `collapsed` sets a starting state and does not hold the group shut.
 */

import { defineRouteMiddleware } from '@astrojs/starlight/route-data';
import { getImagePath } from 'astro-opengraph-images/util.js';

import { PREVIEW_HEIGHT, PREVIEW_WIDTH } from './site.mjs';

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

	const route = context.locals.starlightRoute;

	route.head.push(...previewTags(context, altTextFor(route)));
	closeGeneratedSection(route.sidebar);
});

/**
 * What the preview image says, for a reader whose client reads a card out
 * loud. The image carries the title of the page and the site name; the front
 * page is titled with the site name and would otherwise say it twice.
 */
function altTextFor({ entry, siteTitle }) {
	const title = entry.data.title;
	return title === siteTitle ? siteTitle : `${title} - ${siteTitle}`;
}

/**
 * The Open Graph tags for the image `./opengraph.mjs` draws for this page.
 *
 * Starlight writes every other tag a link preview reads and names no image.
 * These entries are appended, so none of them replaces a tag Starlight already
 * wrote, and the merge `getHead` runs over the head sources does not apply.
 *
 * The path comes from `getImagePath` and is not spelled out here. The
 * integration compares that path against the file it wrote and fails the build
 * where the two disagree, so a second spelling would be a second thing to keep
 * in step.
 */
function previewTags({ site, url }, alt) {
	/* Without `site` there is no absolute URL to point at, and no image. */
	if(!site) return [];

	const image = getImagePath({ url, site });

	return [
		{ tag: 'meta', attrs: { property: 'og:image', content: image } },
		{ tag: 'meta', attrs: { property: 'og:image:alt', content: alt } },
		{ tag: 'meta', attrs: { property: 'og:image:type', content: 'image/png' } },
		{ tag: 'meta', attrs: { property: 'og:image:width', content: String(PREVIEW_WIDTH) } },
		{ tag: 'meta', attrs: { property: 'og:image:height', content: String(PREVIEW_HEIGHT) } },
		{ tag: 'meta', attrs: { name: 'twitter:image', content: image } }
	];
}

/** Close the API section, once the plugin has put the groups in place. */
function closeGeneratedSection(sidebar) {
	/* A page rendered without a sidebar - the front page, a demo - has none. */
	if(sidebar.length === 0) return;

	const group = sidebar.find(entry => entry.type === 'group' && entry.label === CLOSED);

	if(!group) {
		throw new Error(`The sidebar has no \`${CLOSED}\` group to arrive closed`);
	}

	group.collapsed = true;
}
