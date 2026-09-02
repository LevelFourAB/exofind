/*
 * `/<doc>.md` - a documentation page as the Markdown it was written as.
 *
 * `/llms.txt` links here rather than to the rendered page, so that a reader
 * fetching a document gets the document and not the header, sidebar and theme
 * control around it.
 *
 * The file is served as it lies in `docs/`, links and all. That works because
 * these URLs mirror the directory: a document that points at
 * `../reference/errors.md` is answered here at the path that relative link
 * resolves to. The one form that does not survive is a link out of `docs/`,
 * which the site rewrites to the repository when it renders a page - see
 * `../plugins/remark-docs.mjs`.
 */

import type { APIRoute, GetStaticPaths } from 'astro';
import { getCollection } from 'astro:content';

export const getStaticPaths: GetStaticPaths = async () => {
	const documents = await getCollection('docs');

	return documents.map(document => ({
		params: { doc: document.id },
		props: { body: document.body ?? '' }
	}));
};

export const GET: APIRoute = ({ props }) => {
	const { body } = props as { body: string };

	return new Response(body, {
		headers: { 'content-type': 'text/markdown; charset=utf-8' }
	});
};
