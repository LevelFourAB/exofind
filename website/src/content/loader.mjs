/*
 * Loading the documentation from `docs/` without moving or changing it.
 *
 * `docs/` is the documentation, and it is read on GitHub as often as it is
 * read here. Starlight's own loader can only read `src/content/docs`, so this
 * one reads any directory instead, and fills in what a file written for a
 * repository does not carry:
 *
 * - The title, taken from the `# H1` the document opens with. A file that
 *   states its own title in frontmatter keeps it; frontmatter always wins.
 * - The description, taken from the paragraph after that heading.
 * - `docsPath`, which is how the remark plugins in `../plugins/remark-docs.mjs`
 *   know which document they are rendering and can resolve the relative links
 *   between documents into URLs.
 *
 * Rendering happens here rather than through the loader context's
 * `renderMarkdown`, because that renders a string and cannot be told which
 * file the string came from - and without that a relative link has nothing to
 * resolve against.
 */

import { existsSync } from 'node:fs';
import { readdir, readFile } from 'node:fs/promises';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { createMarkdownProcessor } from '@astrojs/markdown-remark';
import { parse as parseYaml } from 'yaml';

/** Longest description taken from a document, in characters. */
const DESCRIPTION_LIMIT = 160;

/**
 * A loader for Markdown documentation held outside the site.
 *
 * @param {object} options
 * @param {string[]} options.roots directories to read, relative to the
 *   Astro project - a document found in more than one wins from the last
 * @param {string} options.repoRoot the repository root, relative to the Astro
 *   project, which recorded paths are expressed against so that edit links
 *   point at the file in the repository
 */
export function docsFromRepository({ roots, repoRoot }) {
	return {
		name: 'exofind-docs',
		load: async context => {
			const { config, store, logger, parseData, generateDigest, watcher } = context;

			const projectRoot = fileURLToPath(config.root);
			const repoPath = join(projectRoot, repoRoot);
			const processor = await createMarkdownProcessor(config.markdown);

			const stale = new Set(store.keys());

			async function sync(rootPath, filePath) {
				const contents = await readFile(filePath, 'utf-8');
				const digest = generateDigest(contents);
				const { frontmatter, body } = splitFrontmatter(contents);

				const id = idFor(relative(rootPath, filePath));
				const docsPath = posix(relative(rootPath, filePath));
				const repoRelative = posix(relative(repoPath, filePath));

				stale.delete(id);

				const existing = store.get(id);
				if(existing?.digest === digest) return;

				const data = await parseData({
					id,
					filePath: repoRelative,
					data: {
						title: titleOf(body) ?? id,
						...describe(body),
						...frontmatter
					}
				});

				/*
				 * `docsPath` rides along as frontmatter because that is the
				 * only channel the processor offers into a remark plugin. It
				 * is not part of the entry's data and never reaches a page.
				 */
				const { code, metadata } = await processor.render(body, {
					frontmatter: { ...data, docsPath }
				});

				store.set({
					id,
					data,
					body,
					filePath: repoRelative,
					digest,
					rendered: { html: code, metadata }
				});
			}

			for(const root of roots) {
				const rootPath = join(projectRoot, root);

				if(!existsSync(rootPath)) {
					logger.warn(`No such directory: ${rootPath}`);
					continue;
				}

				const files = await documentsIn(rootPath);
				await Promise.all(files.map(file => sync(rootPath, file)));

				if(watcher) watch(watcher, rootPath, file => sync(rootPath, file), store, logger);
			}

			for(const id of stale) store.delete(id);
		}
	};
}

/**
 * Every Markdown file under a directory, except the READMEs.
 *
 * A README is what a reader of the repository opens to find their way
 * around, so it lists what is in the directory it sits in. On the site that
 * job belongs to the sidebar, which is built from the same file - see
 * `../sidebar.mjs`.
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

/** The id of a document, which is also the path it is served at. */
function idFor(relativePath) {
	return posix(relativePath).replace(/\.md$/, '');
}

function posix(path) {
	return path.split(sep).join('/');
}

/** The text of the `# H1` a document opens with, if it opens with one. */
function titleOf(body) {
	const match = body.match(/^\s*#\s+(.+?)\s*$/m);
	return match ? match[1] : null;
}

/**
 * The paragraph after the title, as the page description.
 *
 * Long paragraphs are cut at a word, because a description is read in a
 * search result and in a link preview rather than on the page.
 */
function describe(body) {
	const afterTitle = body.replace(/^\s*#\s+.+?$/m, '');
	const paragraph = afterTitle.trim().split(/\n\s*\n/)[0];

	if(!paragraph || /^[#>|\-*\s]/.test(paragraph)) return {};

	const text = paragraph.replace(/\s+/g, ' ').trim();
	if(text.length <= DESCRIPTION_LIMIT) return { description: text };

	const cut = text.lastIndexOf(' ', DESCRIPTION_LIMIT - 1);
	return { description: `${text.slice(0, cut > 0 ? cut : DESCRIPTION_LIMIT - 1)}…` };
}

/** Split leading YAML frontmatter from the body, if there is any. */
function splitFrontmatter(contents) {
	const match = contents.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?/);
	if(!match) return { frontmatter: {}, body: contents };

	return {
		frontmatter: parseYaml(match[1]) ?? {},
		body: contents.slice(match[0].length)
	};
}

/**
 * Reload a document when it changes on disk.
 *
 * The store is live, so writing to it from here is what makes a saved
 * document appear in the running dev server.
 */
function watch(watcher, rootPath, sync, store, logger) {
	const within = path => !relative(rootPath, path).startsWith('..');

	watcher.add(rootPath);

	const reload = async path => {
		if(!within(path) || !path.endsWith('.md')) return;

		await sync(path);
		logger.info(`Reloaded ${posix(relative(rootPath, path))}`);
	};

	watcher.on('add', reload);
	watcher.on('change', reload);
	watcher.on('unlink', path => {
		if(!within(path) || !path.endsWith('.md')) return;

		store.delete(idFor(relative(rootPath, path)));
	});
}
