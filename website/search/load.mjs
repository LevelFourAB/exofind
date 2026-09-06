/*
 * Put the site into an index so that the site can search itself.
 *
 * Run it against a node with the indexer role, whether that is a node on this
 * machine or the one the published site searches:
 *
 *   node search/load.mjs                                  # localhost:8080
 *   NODE=https://indexer.example.com KEY=exok_… node search/load.mjs
 *
 * Loading writes, so `KEY` has to be granted `indexes.write`,
 * `documents.write` and `indexes.commit` over `INDEX`. A node that checks no
 * credentials, such as one in development mode, wants none at all.
 *
 * A load replaces what the last one left. Every document carries the stamp of
 * the load that wrote it, and the documents that still carry an older stamp
 * are deleted once the new ones are committed - which is how a renamed heading
 * or a deleted page leaves the index. Doing it in that order rather than by
 * emptying the index first is what lets a live node be reloaded: the index
 * answers with the previous build until the moment it answers with this one.
 */

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

import { documentsFor } from './documents.mjs';

const DEFINITION = new URL('./definition.json', import.meta.url);

/**
 * Largest body sent in one request, in bytes. A node refuses a body past a
 * limit of its own, and the sections of a reference page are long enough that
 * a batch counted in documents would be one size for the tutorials and another
 * for the search API.
 */
const BATCH_BYTES = 1_000_000;

/*
 * `NODE` is what the load script of every example takes the node in, so it is
 * taken here as well - but a package manager running this script sets `NODE`
 * to the path of the interpreter, so only a value that is a URL is read as
 * one. `NODE_URL` says it without the ambiguity.
 */
const node = urlOf(process.env.NODE_URL) ?? urlOf(process.env.NODE) ?? 'http://localhost:8080';
const index = process.env.INDEX ?? 'docs';
const key = process.env.KEY ?? '';
const dryRun = process.argv.includes('--dry-run');

/** The stamp this load writes, and the one the sweep at the end keeps. */
const build = new Date().toISOString();

async function main() {
	const documents = await documentsFor({ build });

	if(dryRun) {
		process.stdout.write(`${documents.map(document => JSON.stringify(document)).join('\n')}\n`);
		console.error(`${documents.length} documents, nothing sent`);
		return;
	}

	console.log(`Defining ${index} on ${node}`);
	await send(`/v1alpha1/admin/indexes/${encodeURIComponent(index)}`, {
		method: 'PUT',
		type: 'application/json',
		body: await readFile(fileURLToPath(DEFINITION), 'utf-8')
	});

	console.log(`Indexing ${documents.length} documents`);
	for(const batch of batched(documents)) {
		await send(`/v1alpha1/indexes/${encodeURIComponent(index)}/documents`, {
			method: 'POST',
			type: 'application/x-ndjson',
			body: batch
		});
	}

	await commit();

	/*
	 * Committed first, so that nothing is deleted until what replaces it can be
	 * searched, and committed again afterwards so that the deletions are as
	 * well.
	 */
	const { deleted } = await send(
		`/v1alpha1/indexes/${encodeURIComponent(index)}/documents/actions/delete`,
		{
			method: 'POST',
			type: 'application/json',
			body: JSON.stringify({
				query: [{
					type: 'not',
					clauses: [{ field: 'build', match: { value: build } }]
				}]
			})
		}
	);

	if(deleted > 0) {
		console.log(`Removing ${deleted} documents left by an earlier build`);
		await commit();
	}

	console.log(`Done - ${index} on ${node} holds this site`);
}

function commit() {
	console.log('Committing');

	return send(`/v1alpha1/admin/indexes/${encodeURIComponent(index)}/actions/commit`, {
		method: 'POST'
	});
}

function urlOf(value) {
	return /^https?:\/\//.test(value ?? '') ? value.replace(/\/+$/, '') : null;
}

/** One request to the node, with the credential when there is one. */
async function send(path, { method, type, body }) {
	const response = await fetch(`${node}${path}`, {
		method,
		headers: {
			...type ? { 'Content-Type': type } : {},
			...key ? { Authorization: `Bearer ${key}` } : {}
		},
		body
	});

	const answer = await response.text();

	if(!response.ok) {
		throw new Error(`${method} ${path} answered ${response.status}: ${answer}`);
	}

	return answer ? JSON.parse(answer) : {};
}

/** The documents as newline-delimited JSON, in bodies a node will accept. */
function* batched(documents) {
	let lines = [];
	let bytes = 0;

	for(const document of documents) {
		const line = JSON.stringify(document);

		if(bytes > 0 && bytes + line.length > BATCH_BYTES) {
			yield `${lines.join('\n')}\n`;
			lines = [];
			bytes = 0;
		}

		lines.push(line);
		bytes += line.length + 1;
	}

	if(lines.length > 0) yield `${lines.join('\n')}\n`;
}

main().catch(error => {
	console.error(error.message);
	process.exit(1);
});
