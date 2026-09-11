/*
 * The site as documents to search.
 *
 * Every page the site publishes becomes one or more documents here: the manual
 * under `docs/`, the pages written for the site alone under
 * `../src/content/pages/`, a page per REST endpoint, and the demos. A page is
 * not one document but one per section, because a reader searching a manual is
 * looking for the paragraph that answers them rather than for the page it is
 * on - and a section is the smallest piece the site can link to, since
 * Starlight gives every heading an anchor.
 *
 * The Markdown is read from the repository rather than from the built site.
 * That keeps indexing independent of a site build, and the two agree because
 * both derive the same things from the same files: the URL of a page is its
 * path under `docs/`, which is what `../src/plugins/remark-docs.mjs` rewrites
 * links to, and the anchor of a section is its heading run through the same
 * slugger Astro gives headings their ids with.
 */

import { readdir, readFile } from 'node:fs/promises';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import GithubSlugger from 'github-slugger';
import { parse as parseYaml } from 'yaml';

import { DEMOS } from '../src/examples/demos.mjs';
import { typesIn } from '../src/openapi/types.mjs';
import { partsFrom } from '../src/sidebar.mjs';
import { BASE } from '../src/site.mjs';

const DOCS = new URL('../../docs/', import.meta.url);
const DOCS_INDEX = new URL('README.md', DOCS);
const OPENAPI = new URL('../public/openapi.yaml', import.meta.url);

/*
 * The second directory of Markdown the site publishes - prose written for the
 * site rather than for the repository, which today is the comparison pages.
 * The site's loader reads both roots, so both are read here; a page indexed
 * from only one of them is a page the site publishes and cannot find.
 */
const PAGES = new URL('../src/content/pages/', import.meta.url);

/** What a hit from `PAGES` is labelled with, by the directory it is in. */
const PAGE_PARTS = { compare: 'Compare' };

/** Longest excerpt shown beside a hit that highlighted nothing, in characters. */
const EXCERPT_LIMIT = 180;

/**
 * Longest text kept for one section, in characters.
 *
 * A handful of reference sections are tables of every error code or every
 * setting there is, and the whole of one says no more about what the section
 * is than its first pages do. The engine evaluates the first 10 000 characters
 * of a value for highlighting, so anything past this is text that could match
 * without the reader being shown where.
 */
const TEXT_LIMIT = 10000;

/**
 * Build every document the site is searched by.
 *
 * @param {object} options
 * @param {string} options.build stamped on each document, so that the load can
 *   remove whatever an earlier one left behind - see `./load.mjs`
 * @returns {Promise<object[]>} the documents, in no particular order
 */
export async function documentsFor({ build }) {
	const documents = [
		...await manualDocuments(),
		...await pageDocuments(),
		...await apiDocuments(),
		...demoDocuments()
	];

	return documents.map(document => ({ ...document, build }));
}

/* --- the manual ---------------------------------------------------------- */

/** Every section of every document under `docs/`. */
async function manualDocuments() {
	const parts = partOfSlug();

	return sectionsUnder(DOCS, slug => parts.get(slug));
}

/**
 * Every section of every page written for the site alone.
 *
 * These are the same Markdown as the manual and are cut up the same way. What
 * differs is the label, which no documentation index states: it comes from the
 * directory the page is in.
 */
async function pageDocuments() {
	return sectionsUnder(PAGES, slug => PAGE_PARTS[slug.split('/')[0]]);
}

/**
 * Every section of every document under a root, labelled by what the root
 * says about each.
 *
 * A slug is the path under the root without its extension, which is also the
 * path the site serves the page at - both roots the site loads work that way.
 *
 * @param {URL} root the directory to read
 * @param {(slug: string) => string | undefined} partOf the label for a page
 */
async function sectionsUnder(root, partOf) {
	const path = fileURLToPath(root);
	const files = await documentsIn(path);

	const pages = await Promise.all(files.map(async file => {
		const slug = posix(relative(path, file)).replace(/\.md$/, '');

		return sectionsOf(slug, partOf(slug), await readFile(file, 'utf-8'));
	}));

	return pages.flat();
}

/**
 * Which part of the manual each document is in, read from the same index the
 * sidebar and the header are built from. A file that `docs/README.md` does not
 * list is still indexed, and simply carries no part.
 */
function partOfSlug() {
	const parts = new Map();

	for(const part of partsFrom(DOCS_INDEX)) {
		for(const slug of part.slugs) parts.set(slug, part.label);
	}

	return parts;
}

/**
 * Every Markdown file under a root, except the ones the site does not publish.
 *
 * The same files the site's own loader reads - see
 * `../src/content/loader.mjs`. A README is the index of the directory it sits
 * in rather than a page, and the sidebar is what the site makes of it.
 */
async function documentsIn(root) {
	const entries = await readdir(root, { withFileTypes: true, recursive: true });

	return entries
		.filter(entry => entry.isFile()
			&& entry.name.endsWith('.md')
			&& entry.name !== 'README.md'
			&& !entry.name.startsWith('_'))
		.map(entry => join(entry.parentPath, entry.name));
}

/**
 * Cut one document into the sections a reader can be sent to.
 *
 * A `##` or `###` heading starts a section; anything deeper stays in the
 * section it is under, because it divides an explanation rather than naming one
 * a reader would search for. The text before the first heading is the lead,
 * which is what a search for the page's own name should land on.
 */
function sectionsOf(slug, part, contents) {
	const body = withoutFrontmatter(contents);
	const title = titleOf(body) ?? slug;

	const slugger = new GithubSlugger();
	const sections = [];

	let current = { anchor: null, heading: null, lines: [] };
	let seenTitle = false;

	for(const { line, fenced } of linesOf(body)) {
		if(fenced) {
			current.lines.push(line);
			continue;
		}

		const heading = line.match(/^(#{1,6})\s+(.+?)\s*$/);
		if(!heading) {
			current.lines.push(line);
			continue;
		}

		const depth = heading[1].length;
		const text = plain(heading[2]);

		/*
		 * The opening `# H1` is the page title and the site removes it before
		 * rendering, so it never becomes an anchor. Every other heading is
		 * given one here in document order, because the slugger numbers a
		 * repeated heading by how many it has already seen - and an anchor that
		 * disagrees with the page sends the reader to the top of it.
		 */
		if(depth === 1 && !seenTitle) {
			seenTitle = true;
			continue;
		}

		const anchor = slugger.slug(text);

		if(depth > 3) {
			current.lines.push(line);
			continue;
		}

		sections.push(current);
		current = { anchor, heading: text, lines: [] };
	}

	sections.push(current);

	return sections
		.map((section, position) => documentFor({ slug, part, title, section, position }))
		.filter(document => document !== null);
}

/**
 * One section as a document, or nothing when it holds no text of its own - a
 * `##` heading whose page divides it into `###` sections immediately has
 * nothing under it that a reader could be shown.
 */
function documentFor({ slug, part, title, section, position }) {
	const markdown = section.lines.join('\n');
	const text = plain(markdown);
	if(!text) return null;

	const path = `${BASE}/${slug}/`;

	return {
		id: section.anchor ? `${slug}#${section.anchor}` : slug,
		url: section.anchor ? `${path}#${section.anchor}` : path,
		part,
		title,
		...section.heading ? { heading: section.heading } : {},
		text: text.slice(0, TEXT_LIMIT),
		excerpt: excerptOf(markdown, text),
		lead: position === 0,
		position
	};
}

/* --- the REST API -------------------------------------------------------- */

/**
 * A document per endpoint page and per type page, read from the OpenAPI
 * document the site generates those pages from.
 *
 * The prose is written on the Java sources and reaches this file through the
 * engine build - see `tools/api-descriptions/`. A stale copy is a stale
 * section of the site as well, so nothing extra is needed to keep the two in
 * step: `mise run site:openapi` refreshes both at once.
 */
async function apiDocuments() {
	const api = parseYaml(await readFile(fileURLToPath(OPENAPI), 'utf-8'));
	const documents = [...typeDocuments(api)];

	for(const [path, operations] of Object.entries(api.paths ?? {})) {
		for(const [method, operation] of Object.entries(operations)) {
			if(!operation?.operationId) continue;

			const call = `${method.toUpperCase()} ${path}`;
			const markdown = [call, operation.description ?? ''].join('\n\n');
			const text = plain(markdown);

			documents.push({
				id: `api/${operation.operationId}`,
				url: `${BASE}/api/operations/${operation.operationId}/`,
				part: 'REST API',
				title: operation.summary ?? operation.operationId,
				heading: call,
				text: text.slice(0, TEXT_LIMIT),
				excerpt: excerptOf(markdown, text),
				lead: true,
				position: 0
			});
		}
	}

	return documents;
}

/**
 * A document per type page.
 *
 * Which types have pages is decided by `../src/openapi/types.mjs`, the module
 * the site builds the pages from, so the two cannot disagree: a page the site
 * publishes is a page its search can find, and a type that loses its page here
 * loses it there in the same build.
 *
 * The text is the description of the type and of each of its variants. The
 * properties are not in it - a reader searching for `locales` wants the guide
 * that explains locales, not every type that carries a property of that name.
 *
 * @param {object} api the parsed OpenAPI document
 * @returns {object[]}
 */
function typeDocuments(api) {
	const schemas = api.components?.schemas ?? {};

	return typesIn(api).map(type => {
		const schema = schemas[type.name] ?? {};

		const variants = (schema.oneOf ?? [])
			.map(member => schemas[String(member.$ref ?? '').split('/').pop()])
			.filter(Boolean)
			.map(member => member.description ?? '');

		const markdown = [type.description, ...variants].filter(Boolean).join('\n\n');
		const text = plain(markdown);

		return {
			id: `api/types/${type.name}`,
			url: `${BASE}/api/types/${type.name}/`,
			part: 'REST API',
			title: type.name,
			heading: 'Type',
			text: text.slice(0, TEXT_LIMIT),
			excerpt: excerptOf(markdown, text),
			lead: true,
			position: 0
		};
	});
}

/* --- the demos ----------------------------------------------------------- */

/** A document per demo page, from the list the pages themselves are built from. */
function demoDocuments() {
	const catalogue = {
		id: 'examples',
		url: `${BASE}/examples/`,
		part: 'Demos',
		title: 'Demos',
		text: plain(`Pages that search a live node, one per dataset: ${
			DEMOS.map(demo => demo.title).join(', ')}.`),
		excerpt: 'Pages that search a live node, one per dataset.',
		lead: true,
		position: 0
	};

	return [catalogue, ...DEMOS.map(demo => {
		const text = plain(`${demo.summary} Shows ${demo.shows.join(', ')}.`);

		return {
			id: `examples/${demo.name}`,
			url: `${BASE}/examples/${demo.name}/`,
			part: 'Demos',
			title: demo.title,
			text,
			excerpt: cut(text),
			lead: true,
			position: 0
		};
	})];
}

/* --- turning Markdown into text ------------------------------------------ */

/** The rule drawn under the header row of a table. */
const TABLE_RULE = /^[ \t]*\|?[\s:|-]*\|[\s:|-]*$/;

/**
 * The lines of a piece of Markdown, each with whether it sits inside a fenced
 * block.
 *
 * A fenced block holds text, not structure: `# comment` in a shell snippet is
 * not a heading, and a row of `|` in a diagram is not a table. Reading either
 * as structure cuts a section in the middle and shifts every anchor after it.
 * A fence marker counts as inside the block it opens or closes.
 */
function* linesOf(markdown) {
	let fence = null;

	for(const line of markdown.split('\n')) {
		const marker = line.match(/^\s*(`{3,}|~{3,})/);

		if(marker) {
			const mark = marker[1][0];

			if(!fence) fence = mark;
			else if(fence === mark) fence = null;

			yield { line, fenced: true };
			continue;
		}

		yield { line, fenced: fence !== null };
	}
}

/**
 * The readable text of a piece of Markdown.
 *
 * Everything that is punctuation for a renderer is dropped and everything a
 * reader would read is kept, code included: a setting name, an error code and
 * a header are what a reader searching a manual types, and most of them appear
 * nowhere but inside a fence or a pair of backticks.
 */
function plain(markdown) {
	return withEndedRows(markdown)
		// Comments and directive markers say nothing to a reader
		.replace(/<!--[\s\S]*?-->/g, ' ')
		.replace(/^\s*:::[a-z]*(\[[^\]]*\])?\s*$/gm, ' ')
		// A fence keeps what is inside it and loses the fence and its language
		.replace(/^\s*(?:`{3,}|~{3,}).*$/gm, ' ')
		// An image is a file; its alt text describes a picture nobody searched for
		.replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
		// A link reads as its label
		.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
		.replace(/^\s*\[[^\]]+\]:\s*\S+.*$/gm, ' ')
		// Autolinks and bare URLs are addresses rather than words
		.replace(/<https?:\/\/[^>]*>/g, ' ')
		.replace(/https?:\/\/\S+/g, ' ')
		// A rule is drawing, and a pipe left inside a fence is spacing
		.replace(/^\s*\|?[\s:|-]*\|[\s:|-]*$/gm, ' ')
		.replace(/\|/g, ' ')
		// What is left of the syntax: headings, quotes, list markers, emphasis
		.replace(/^\s{0,3}#{1,6}\s+/gm, ' ')
		.replace(/^\s*>\s?/gm, ' ')
		.replace(/^\s*(?:[-*+]|\d+\.)\s+/gm, ' ')
		.replace(/`/g, '')
		/*
		 * Emphasis is taken off the words it wraps rather than being stripped a
		 * character at a time, because an underscore is part of far more words
		 * in this manual than it wraps: every setting, every error code and
		 * every field type is spelled with one, and `geo_point` reduced to
		 * `geopoint` is a heading whose anchor no longer leads anywhere.
		 */
		.replace(/~~([^~]+)~~/g, '$1')
		.replace(/\*\*([^*]+)\*\*/g, '$1')
		.replace(/(?<![\w*])\*([^*\n]+)\*(?![\w*])/g, '$1')
		.replace(/(?<![\w_])__([^_\n]+)__(?![\w_])/g, '$1')
		.replace(/(?<![\w_])_([^_\n]+)_(?![\w_])/g, '$1')
		.replace(/\s+/g, ' ')
		.trim();
}

/**
 * Every table row ended as a sentence of its own.
 *
 * A search cuts a fragment on a sentence, and a table joined by spaces alone is
 * a single sentence of several thousand characters. The fragment cut from one
 * is the whole table. Drawn whole, it fills the dialog and pushes every other
 * result out of it. A row states one thing about one thing - a setting, an
 * error code, a locale - and a fragment should hold a row.
 *
 * A stop ends a sentence only when an upper-case word follows it. This bounds
 * the tables whose rows open with one, such as the configuration variables. A
 * table whose rows open with a language tag or a status code is still read as
 * one sentence, and the search bounds that; see the `length` option under
 * Highlighting in `docs/reference/search-api.md`.
 */
function withEndedRows(markdown) {
	const lines = [];

	for(const { line, fenced } of linesOf(markdown)) {
		if(fenced || TABLE_RULE.test(line) || !/^[ \t]*\|/.test(line)) {
			lines.push(line);
			continue;
		}

		const row = line.replace(/\|/g, ' ').trim();

		lines.push(/[.:!?]$/.test(row) ? row : `${row}.`);
	}

	return lines.join('\n');
}

/**
 * What a section is about, cut to the length an excerpt is shown at.
 *
 * Only the prose is read. A section often opens with the request that performs
 * it, or with a table of every setting the engine has, and the first 180
 * characters of either name nothing a reader was looking for: `curl -i -X PUT
 * -H Authorization: Bearer ...` says only that the section holds a request. The
 * sentences around the block describe the section.
 *
 * Every word of the block stays in `text`, where a search still finds it and
 * highlights it. This decides what the dialog shows, not what it finds.
 *
 * A section holding nothing but a block falls back to its own text: an awkward
 * excerpt says more than an empty one.
 */
function excerptOf(markdown, text) {
	const prose = [];

	for(const { line, fenced } of linesOf(markdown)) {
		if(!fenced && !/^[ \t]*\|/.test(line)) prose.push(line);
	}

	return cut(plain(prose.join('\n')) || text);
}

/** Text cut to the length an excerpt is shown at, on a word. */
function cut(text) {
	if(text.length <= EXCERPT_LIMIT) return text;

	const at = text.lastIndexOf(' ', EXCERPT_LIMIT - 1);

	return `${text.slice(0, at > 0 ? at : EXCERPT_LIMIT - 1)}…`;
}

/** The text of the `# H1` a document opens with, or its path when it has none. */
function titleOf(body) {
	const match = body.match(/^\s*#\s+(.+?)\s*$/m);
	return match ? plain(match[1]) : null;
}

function withoutFrontmatter(contents) {
	const match = contents.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?/);
	return match ? contents.slice(match[0].length) : contents;
}

function posix(path) {
	return path.split(sep).join('/');
}
