/*
 * Turning the Markdown under `docs/` into pages without changing it.
 *
 * The files in `docs/` are written to be read on GitHub as much as on this
 * site: they open with an `# H1` instead of a frontmatter title, and they
 * point at each other by relative path - `../reference/errors.md#codes`. Both
 * are right for a repository and wrong for a rendered site, where the layout
 * prints the title itself and the page lives at `/reference/errors/`.
 *
 * These two plugins reconcile that at render time, so the source files stay
 * the plain Markdown that the repository documents them as being.
 */

import { existsSync } from 'node:fs';
import { dirname, join, resolve, relative, sep } from 'node:path';

import { visit } from 'unist-util-visit';

import { BASE, REPO } from '../site.mjs';

/**
 * Remove the leading `# H1`.
 *
 * The layout renders the title from the entry's data, which for these files
 * was taken from this very heading, so leaving it would print the title
 * twice and put it in the table of contents as well.
 *
 * Only a heading that opens the document is removed. A document that starts
 * with anything else keeps every heading it has.
 */
export function remarkStripTitle() {
	return (tree, file) => {
		if(!docsPathOf(file)) return;

		const first = tree.children[0];
		if(first?.type === 'heading' && first.depth === 1) {
			tree.children.shift();
		}
	};
}

/**
 * Rewrite links between documents to the URLs those documents are served at.
 *
 * A link is rewritten only when it points at a Markdown file by relative
 * path, which is the one form that means "another document" - anything
 * absolute, external or already a fragment is left as it was written.
 *
 * A target that is not there is reported rather than quietly rewritten into a
 * link that answers 404, because a renamed document otherwise breaks every
 * page that pointed at it without failing anything.
 */
export function remarkRewriteLinks({ docsRoot }) {
	return (tree, file) => {
		const docsPath = docsPathOf(file);
		if(!docsPath) return;

		visit(tree, ['link', 'definition'], node => {
			const rewritten = rewrite(node.url, docsPath, docsRoot);
			if(rewritten) node.url = rewritten;
		});
	};
}

/**
 * The path of the source file relative to `docs/`, as the loader recorded it.
 *
 * Markdown rendered outside the docs collection has none, and is left alone
 * by both plugins.
 */
function docsPathOf(file) {
	return file.data?.astro?.frontmatter?.docsPath;
}

function rewrite(url, docsPath, docsRoot) {
	if(!url || /^[a-z][a-z0-9+.-]*:/i.test(url) || url.startsWith('/') || url.startsWith('#')) {
		return null;
	}

	const [path, fragment = ''] = splitFragment(url);
	if(!path.endsWith('.md')) return null;

	const hash = fragment ? `#${fragment}` : '';

	/*
	 * Resolved against the linking document, then expressed relative to
	 * `docs/` again - which is what tells a link within the documentation
	 * from one that reaches out of it, because only the second comes back
	 * with a leading `..`.
	 */
	const target = relative(docsRoot, resolve(docsRoot, dirname(docsPath), path))
		.split(sep)
		.join('/');

	if(target.startsWith('../')) {
		return outsideDocs(target.replace(/^(\.\.\/)+/, ''), hash);
	}

	if(!existsSync(join(docsRoot, target))) {
		console.warn(`[docs] ${docsPath} links to ${path}, which does not exist`);
	}

	return `${BASE}/${target.replace(/\.md$/, '')}/${hash}`;
}

/**
 * Where a link that leaves `docs/` goes.
 *
 * The example pages are part of this site, so a link to what an example is
 * goes to the page that shows it. Everything else is a file that only the
 * repository holds, and is linked there.
 */
function outsideDocs(path, hash) {
	const example = path.match(/^examples\/(?:([^/]+)\/)?README\.md$/);
	if(example) {
		return example[1]
			? `${BASE}/examples/${example[1]}/${hash}`
			: `${BASE}/examples/${hash}`;
	}

	return `${REPO}/blob/main/${path}${hash}`;
}

function splitFragment(url) {
	const at = url.indexOf('#');
	return at === -1 ? [url] : [url.slice(0, at), url.slice(at + 1)];
}
