/*
 * The site as documents to search.
 *
 * Every page the site publishes becomes one or more documents here: the manual
 * under `docs/`, a page per REST endpoint, and the demos. A page is not one
 * document but one per section, because a reader searching a manual is looking
 * for the paragraph that answers them rather than for the page it is on - and
 * a section is the smallest piece the site can link to, since Starlight gives
 * every heading an anchor.
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
import { partsFrom } from '../src/sidebar.mjs';
import { BASE } from '../src/site.mjs';

const DOCS = new URL('../../docs/', import.meta.url);
const DOCS_INDEX = new URL('README.md', DOCS);
const OPENAPI = new URL('../public/openapi.yaml', import.meta.url);

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
		...await apiDocuments(),
		...demoDocuments()
	];

	return documents.map(document => ({ ...document, build }));
}

/* --- the manual ---------------------------------------------------------- */

/** Every section of every document under `docs/`. */
async function manualDocuments() {
	const root = fileURLToPath(DOCS);
	const parts = partOfSlug();

	const files = await documentsIn(root);

	const pages = await Promise.all(files.map(async file => {
		const slug = posix(relative(root, file)).replace(/\.md$/, '');

		return sectionsOf(slug, parts.get(slug), await readFile(file, 'utf-8'));
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
 * Every Markdown file under `docs/`, except the ones the site does not publish.
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
	let fence = null;
	let seenTitle = false;

	for(const line of body.split('\n')) {
		/*
		 * A fenced block is text, not structure: `# comment` inside a shell
		 * snippet is neither a heading nor an anchor, and reading it as one
		 * would cut a section in the middle and shift every anchor after it.
		 */
		const fenced = line.match(/^\s*(`{3,}|~{3,})/);
		if(fenced) {
			const mark = fenced[1][0];

			if(!fence) fence = mark;
			else if(fence === mark) fence = null;

			current.lines.push(line);
			continue;
		}

		const heading = fence ? null : line.match(/^(#{1,6})\s+(.+?)\s*$/);
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
	const text = plain(section.lines.join('\n'));
	if(!text) return null;

	const path = `${BASE}/${slug}/`;

	return {
		id: section.anchor ? `${slug}#${section.anchor}` : slug,
		url: section.anchor ? `${path}#${section.anchor}` : path,
		part,
		title,
		...section.heading ? { heading: section.heading } : {},
		text: text.slice(0, TEXT_LIMIT),
		excerpt: cut(text),
		lead: position === 0,
		position
	};
}

/* --- the REST API -------------------------------------------------------- */

/**
 * A document per endpoint page, read from the OpenAPI document the site
 * generates those pages from.
 *
 * The prose is written on the Java sources and reaches this file through the
 * engine build - see `tools/api-descriptions/`. A stale copy is a stale
 * section of the site as well, so nothing extra is needed to keep the two in
 * step: `mise run site:openapi` refreshes both at once.
 */
async function apiDocuments() {
	const api = parseYaml(await readFile(fileURLToPath(OPENAPI), 'utf-8'));
	const documents = [];

	for(const [path, operations] of Object.entries(api.paths ?? {})) {
		for(const [method, operation] of Object.entries(operations)) {
			if(!operation?.operationId) continue;

			const call = `${method.toUpperCase()} ${path}`;
			const text = plain([call, operation.description ?? ''].join('\n\n'));

			documents.push({
				id: `api/${operation.operationId}`,
				url: `${BASE}/api/operations/${operation.operationId}/`,
				part: 'REST API',
				title: operation.summary ?? operation.operationId,
				heading: call,
				text: text.slice(0, TEXT_LIMIT),
				excerpt: cut(text),
				lead: true,
				position: 0
			});
		}
	}

	return documents;
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

/**
 * The readable text of a piece of Markdown.
 *
 * Everything that is punctuation for a renderer is dropped and everything a
 * reader would read is kept, code included: a setting name, an error code and
 * a header are what a reader searching a manual types, and most of them appear
 * nowhere but inside a fence or a pair of backticks.
 */
function plain(markdown) {
	return markdown
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
		// Table rules are drawing; the pipes between cells are spacing
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
