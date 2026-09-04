/*
 * The image a link to this site unfurls into, drawn once per page at build
 * time.
 *
 * Starlight fills in every other Open Graph tag - the title, the description,
 * the canonical URL - but names no image, so a link posted to Slack, Discord,
 * Mastodon or X arrives as a line of text. `astro-opengraph-images` reads the
 * built HTML of each page, hands the tags it finds to `render` below, and
 * writes the result next to the page as a PNG. `./route-data.mjs` adds the
 * `og:image` tag that points at it.
 *
 * The image repeats what the page already says: the part of the manual, the
 * title, and the first sentences. It carries no screenshot and no diagram,
 * so nothing here goes stale when a page is rewritten.
 *
 * Satori draws the image from the objects below, so the layout is CSS with two
 * limits. A container holding more than one child needs an explicit
 * `display: 'flex'`, and there is no text wrapping control beyond a width, so
 * a long title is cut here instead.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { PARTS } from './parts.mjs';
import { BASE, PREVIEW_HEIGHT, PREVIEW_WIDTH } from './site.mjs';

/*
 * The palette of `./styles/site.css`, dark half, because a preview card is
 * shown against a conversation and never follows the reader's theme. A colour
 * changed there has to be changed here as well; nothing compares the two.
 */
const INK = '#e4e8e4';
const MUTED = '#8b969c';
const RULE = '#293237';
const PAPER = '#0f1417';
const ACCENT = '#3a5cf0';
const ACCENT_HIGH = '#b9c8ff';

/** The name the header shows, and the title the front page carries. */
const SITE_NAME = 'Exofind';

/** The host, spelled as the footer of the image spells it. */
const SITE_HOST = 'exofind.dev';

/*
 * Sections of the site that hold no document in `docs/`, under the labels the
 * sidebar gives them. `EXTRA` in `./nav.mjs` names the same two groups for the
 * header. Everything else takes its label from `docs/README.md` through
 * `PARTS`.
 */
const OUTSIDE_THE_MANUAL = {
	api: 'REST API',
	examples: 'Demos'
};

/*
 * How much of a title fits on three lines at each size. Satori wraps text but
 * reports nothing about the result, so the size comes from the length of the
 * string, and a title longer than the last entry is cut before it is drawn.
 */
const TITLE_SIZES = [
	{ upTo: 28, fontSize: 72 },
	{ upTo: 52, fontSize: 60 },
	{ upTo: 96, fontSize: 48 }
];

/** Characters of description that fit under a title, on two lines. */
const DESCRIPTION_LIMIT = 150;

/**
 * The fonts Satori draws with. It reads TrueType, OpenType and WOFF, and no
 * WOFF2, so these are the static WOFF files of both families. The site itself
 * loads the variable Archivo, which ships as WOFF2 alone.
 *
 * @type {import('astro-opengraph-images').SatoriFontOptions[]}
 */
export const FONTS = [
	font('Archivo', 400, '@fontsource/archivo/files/archivo-latin-400-normal.woff'),
	font('Archivo', 600, '@fontsource/archivo/files/archivo-latin-600-normal.woff'),
	font('IBM Plex Mono', 500, '@fontsource/ibm-plex-mono/files/ibm-plex-mono-latin-500-normal.woff')
];

/**
 * Whether a route produces a page an image can be drawn for. The site
 * publishes routes that are not HTML - `llms.txt`, a Markdown copy of every
 * document - and the integration would read each one as a document and fail on
 * the tags it does not have.
 *
 * @param {import('astro-opengraph-images').Page} page
 * @returns {boolean}
 */
export function isPage({ pathname }) {
	const last = pathname.replace(/\/+$/, '').split('/').pop() ?? '';
	return !last.includes('.');
}

/**
 * Draw one page.
 *
 * @param {import('astro-opengraph-images').RenderFunctionInput} input the Open
 *   Graph tags of the built page, and the path it is served at
 * @returns {object} the image, in the object form Satori draws from
 */
export function render({ title, description, pathname }) {
	const clean = tidy(description);

	/*
	 * The front page is titled with the site name, which the row above the
	 * title already carries. It leads with the sentence that says what the
	 * engine does instead.
	 */
	const front = title === SITE_NAME;
	const headline = front ? clean : title;
	const sub = front ? '' : clean;

	return column({ width: PREVIEW_WIDTH, height: PREVIEW_HEIGHT, backgroundColor: PAPER, color: INK }, [
		box({ height: 8, backgroundColor: ACCENT }),
		column({ flexGrow: 1, padding: '64px 80px', justifyContent: 'space-between' }, [
			eyebrow(sectionOf(pathname)),
			column({}, [
				text(cut(headline, 96), {
					fontFamily: 'Archivo',
					fontWeight: 600,
					fontSize: sizeFor(headline),
					lineHeight: 1.14,
					letterSpacing: '-0.02em'
				}),
				box({ width: 72, height: 3, margin: '32px 0 0', backgroundColor: ACCENT }),
				sub
					? text(cut(sub, DESCRIPTION_LIMIT), {
						marginTop: 32,
						color: MUTED,
						fontFamily: 'Archivo',
						fontSize: 28,
						lineHeight: 1.45
					})
					: box({})
			]),
			text(SITE_HOST, {
				alignSelf: 'flex-end',
				color: MUTED,
				fontFamily: 'IBM Plex Mono',
				fontWeight: 500,
				fontSize: 22,
				letterSpacing: '0.04em'
			})
		])
	]);
}

/** The site name, and the section the page sits in where it has one. */
function eyebrow(section) {
	const label = {
		fontFamily: 'IBM Plex Mono',
		fontWeight: 500,
		fontSize: 22,
		letterSpacing: '0.18em'
	};

	return row({ alignItems: 'center' }, [
		text(SITE_NAME.toUpperCase(), label),
		...(section
			? [
				text('/', { ...label, margin: '0 16px', color: RULE }),
				text(section.toUpperCase(), { ...label, color: ACCENT_HIGH })
			]
			: [])
	]);
}

/**
 * The section a path belongs to, or an empty string for the front page and for
 * anything the manual does not list.
 */
function sectionOf(pathname) {
	const path = BASE && pathname.startsWith(BASE) ? pathname.slice(BASE.length) : pathname;
	const slug = path.replace(/^\/+/, '').replace(/\/+$/, '');
	if(slug === '') return '';

	const part = PARTS.find(entry => entry.slugs.includes(slug));
	if(part) return part.label;

	return OUTSIDE_THE_MANUAL[slug.split('/')[0]] ?? '';
}

/** The size a title of this length keeps to three lines, or the smallest. */
function sizeFor(title) {
	const size = TITLE_SIZES.find(entry => title.length <= entry.upTo);
	return (size ?? TITLE_SIZES[TITLE_SIZES.length - 1]).fontSize;
}

/**
 * A description as prose. Starlight takes the description of a document from
 * its opening paragraph where the file states none, so the string arrives with
 * the Markdown of that paragraph still in it.
 *
 * The backticks and asterisks come out and the underscores stay: a setting
 * named `EXOFIND_STORAGE_MODE` is the kind of thing these paragraphs open
 * with, and stripping its underscores as emphasis renames it. An ellipsis
 * Starlight left at the end stays as well, to say the sentence was cut.
 */
function tidy(description) {
	return (description ?? '')
		.replace(/[`*]/g, '')
		.replace(/\s+/g, ' ')
		.trim();
}

/** A string of at most `limit` characters, ended at a word. */
function cut(value, limit) {
	if(value.length <= limit) return value;

	const head = value.slice(0, limit);
	const lastSpace = head.lastIndexOf(' ');
	const ended = lastSpace > limit / 2 ? head.slice(0, lastSpace) : head;
	return `${ended.replace(/[,;:.…]+$/, '')}…`;
}

/** One font, resolved through the package that ships it. */
function font(name, weight, specifier) {
	return {
		name,
		weight,
		style: 'normal',
		data: readFileSync(fileURLToPath(import.meta.resolve(specifier)))
	};
}

function box(style) {
	return { type: 'div', props: { style: { display: 'flex', ...style } } };
}

function row(style, children) {
	return { type: 'div', props: { style: { display: 'flex', ...style }, children } };
}

function column(style, children) {
	return { type: 'div', props: { style: { display: 'flex', flexDirection: 'column', ...style }, children } };
}

function text(content, style) {
	return { type: 'div', props: { style: { display: 'flex', ...style }, children: content } };
}
