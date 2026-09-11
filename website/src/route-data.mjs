/*
 * The one thing about a page that cannot be said where the page is declared:
 * the link preview image.
 *
 * The tag that points at it is added after `next()`, because before that the
 * head Starlight assembles is not there to add to.
 */

import { defineRouteMiddleware } from '@astrojs/starlight/route-data';
import { getImagePath } from 'astro-opengraph-images/util.js';

import { PREVIEW_HEIGHT, PREVIEW_WIDTH } from './site.mjs';

export const onRequest = defineRouteMiddleware(async (context, next) => {
	await next();

	const route = context.locals.starlightRoute;

	route.head.push(...previewTags(context, altTextFor(route)));
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
