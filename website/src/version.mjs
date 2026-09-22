/*
 * The version the site publishes, read from the file the release writes.
 *
 * Release Please writes `.release-please-manifest.json` and `pom.xml` together
 * when a release lands. The manifest is what this reads: the site builds
 * without Java, and JSON needs no parser beyond the one Node has.
 *
 * Two readers need the version. `substitute` puts it into the Markdown the
 * site publishes, so a page that tells a reader to pull an image names the
 * release they can pull. `../astro.config.mjs` defines `VERSION` into the
 * bundle as `__EXOFIND_VERSION__` for the header, because a page is rendered
 * from a bundle and cannot read the repository.
 *
 * The manifest is read once per build. A release that lands while the dev
 * server runs reaches the pages after a restart.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/** The file Release Please writes the version of each package into. */
const MANIFEST = new URL('../../.release-please-manifest.json', import.meta.url);

/** The key the engine is held under, which is the root of the repository. */
const PACKAGE = '.';

/** The released version, such as `0.5.0`. */
export const VERSION = readVersion();

/** The major and minor of that version, such as `0.5`. An image tag moves with it. */
export const MINOR = VERSION.split('.').slice(0, 2).join('.');

/** The container image a deployment pulls, without a tag. */
export const IMAGE = 'ghcr.io/levelfourab/exofind';

/**
 * What each token stands for. A token the site publishes has to be a value
 * that changes with a release; anything else belongs in the page as text.
 */
const TOKENS = {
	version: VERSION,
	minor: MINOR,
	image: IMAGE
};

/**
 * Replace the `{{token}}` placeholders in a document with what they stand for.
 *
 * A page under `docs/` is read on GitHub as well as here, where nothing
 * replaces a token. Use one only where the value is decided by the release -
 * an image tag, a pinned version - and leave a value the reader chooses as
 * `<angle brackets>`, which is how the pages mark what the reader fills in.
 *
 * Every reader of a published Markdown file calls this: the loader in
 * `./content/loader.mjs` for the pages, and `../search/documents.mjs` for the
 * index the site's own search searches. A file read by one and not the other
 * is a page that says one thing and is found by another.
 *
 * @param {string} contents the Markdown as it is written
 * @param {string} source the file it was read from, for the error message
 * @returns {string} the Markdown with every token replaced
 * @throws {Error} if the file holds a token that stands for nothing
 */
export function substitute(contents, source) {
	return contents.replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (token, name) => {
		const value = TOKENS[name];

		if(value === undefined) {
			throw new Error(
				`${source} holds the placeholder ${token}, which stands for nothing. `
					+ `The placeholders are: ${Object.keys(TOKENS).map(key => `{{${key}}}`).join(', ')}`
			);
		}

		return value;
	});
}

/** The version of the engine, as the manifest states it. */
function readVersion() {
	const path = fileURLToPath(MANIFEST);
	const manifest = JSON.parse(readFileSync(path, 'utf-8'));
	const version = manifest[PACKAGE];

	if(typeof version !== 'string' || !/^\d+\.\d+\.\d+/.test(version)) {
		throw new Error(`${path} states no version for the package \`${PACKAGE}\``);
	}

	return version;
}
