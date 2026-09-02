/*
 * `/llms-full.txt` - the pages a client author needs, in one fetch.
 *
 * The whole manual is around 83,000 words, which is more than most readers of
 * this file can hold at once, and most of it answers questions a client author
 * does not have: how to run nodes, what to monitor, when to reindex. The
 * documents below are the ones needed to call a node correctly, and they come
 * to around 34,000 words together.
 *
 * `/llms.txt` lists the rest, a document at a time.
 */

import type { APIRoute } from 'astro';
import { getCollection } from 'astro:content';

import { PREAMBLE, pageUrl } from '../llms.mjs';

/*
 * What a client author reads, in the order it is useful: the guides for the
 * task at hand, then the reference for what is being sent and what comes back.
 * The guides come first because a reader that caps what it fetches keeps the
 * start of the file, and a guide says how the endpoints are used together
 * where a reference page states one of them; `/llms.txt` links every
 * reference page on its own for a reader that lost the end. The tutorial is
 * not here: its three requests open the file, and the rest of it runs a
 * container.
 *
 * A slug here that is no longer a document fails the build. The alternative is
 * a file that quietly loses a page when one is renamed, which nothing else
 * would report.
 */
const DOCUMENTS = [
	'how-to/generate-a-client',
	'how-to/define-an-index',
	'how-to/index-documents',
	'how-to/make-writes-visible',
	'how-to/search-an-index',
	'how-to/paginate-search-results',
	'how-to/handle-api-errors',
	'reference/api-conventions',
	'reference/auth',
	'reference/admin-api',
	'reference/documents-api',
	'reference/search-api',
	'reference/field-types',
	'reference/errors'
];

export const GET: APIRoute = async () => {
	const bodies = new Map((await getCollection('docs'))
		.map(document => [document.id, document.body ?? '']));

	const missing = DOCUMENTS.filter(slug => !bodies.has(slug));
	if(missing.length > 0) {
		throw new Error(`llms-full.txt lists documents that are not in docs/: ${missing.join(', ')}`);
	}

	const documents = DOCUMENTS.map(slug => [
		'---',
		'',
		`Source: ${pageUrl(slug)}`,
		'',
		bodies.get(slug)?.trim()
	].join('\n'));

	return new Response([PREAMBLE, ...documents, ''].join('\n\n'), {
		headers: { 'content-type': 'text/plain; charset=utf-8' }
	});
};
