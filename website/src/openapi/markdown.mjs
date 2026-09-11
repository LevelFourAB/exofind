/*
 * The prose in the OpenAPI document, rendered.
 *
 * Descriptions in the document are Markdown. They hold links into the manual,
 * inline code, and more than one paragraph, and a page that prints the source of
 * one prints the brackets with it.
 *
 * This is a processor of its own rather than the site's, which is the opposite
 * of what `../content/loader.mjs` does and for the opposite reason. That module
 * renders whole documents, which hold code blocks, and a code block is only
 * highlighted by the processor the site's integrations have extended. A
 * description holds none - the engine writes none, and a fenced block inside a
 * YAML description would be a block in a string - so nothing is lost by
 * rendering here, and the pages stay independent of how the site is configured.
 *
 * Links to the site's own pages are made relative on the way through. The engine
 * writes them absolute because the same document is read by clients and by
 * readers who never see this site, and a page that kept them absolute would send
 * a reader of a preview build to production to read the page next to the one
 * they are on.
 */

import { createMarkdownProcessor } from '@astrojs/markdown-remark';

import { BASE, SITE } from '../site.mjs';

const processor = await createMarkdownProcessor({ gfm: true, smartypants: false });

/**
 * A description as the HTML a page puts in place.
 *
 * @param {string} markdown
 * @returns {Promise<string>} empty where there is nothing to say, so that a
 *   component can leave the element out rather than draw an empty one
 */
export async function html(markdown) {
	if(!markdown?.trim()) return '';

	const { code } = await processor.render(markdown);

	return local(code);
}

/**
 * A description as HTML with no paragraph around it, for the places that set it
 * inside a line of their own - the note under a field, the lead of a variant.
 *
 * Only a description that is one paragraph is unwrapped. One that runs to
 * several is left as paragraphs, because the alternative is two sentences run
 * together into one line.
 *
 * @param {string} markdown
 * @returns {Promise<string>}
 */
export async function inline(markdown) {
	const rendered = await html(markdown);
	const single = rendered.match(/^<p>([\s\S]*)<\/p>\s*$/);

	return single && !single[1].includes('<p>') ? single[1] : rendered;
}

/** Longest description taken from a description, in characters. */
const DESCRIPTION_LIMIT = 160;

/**
 * The first sentence of a description as plain text, for the places a page is
 * described rather than read: the meta description, a link preview, the entry
 * for a page in the site's own search.
 *
 * The markup is removed rather than rendered - a link in a meta description is
 * the brackets it is written with - and the text is cut at a word, because these
 * are read in a list of results rather than on the page.
 *
 * @param {string} markdown
 * @returns {string}
 */
export function plain(markdown) {
	const text = (markdown ?? '')
		.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
		.replace(/[`*_#>]/g, '')
		.split(/\n\s*\n/)[0]
		?.replace(/\s+/g, ' ')
		.trim() ?? '';

	if(text.length <= DESCRIPTION_LIMIT) return text;

	const cut = text.lastIndexOf(' ', DESCRIPTION_LIMIT - 1);

	return `${text.slice(0, cut > 0 ? cut : DESCRIPTION_LIMIT - 1)}…`;
}

/** Links into the site itself, as paths the build serves them at. */
function local(code) {
	return code.replaceAll(`"${SITE}/`, `"${BASE}/`).replaceAll(`"${SITE}"`, `"${BASE}/"`);
}

/**
 * One link from the document, as the build serves it.
 *
 * The document states a link to the manual absolutely, for the readers of it
 * who never see this site. A page that kept it that way would send a reader of
 * a preview build to production to read the page next to the one they are on.
 *
 * @param {string} href
 * @returns {string} the path, for a link into this site, and the link itself
 *   for anywhere else
 */
export function localHref(href) {
	if(!href?.startsWith(SITE)) return href;

	return `${BASE}${href.slice(SITE.length) || '/'}`;
}
