/*
 * Where the site is served from, in one place.
 *
 * `base` is needed outside `astro.config.mjs` as well - the remark plugin that
 * rewrites links between documents builds URLs before Astro sees them, and
 * Astro prefixes nothing it did not generate itself. Importing the same
 * constant is what keeps the two from disagreeing.
 */

/** Origin the site is served from. */
export const SITE = 'https://exofind.dev';

/**
 * Path the site is rooted at, without a trailing slash. Empty, because the
 * custom domain serves the site from its root rather than under a repository
 * name. `astro.config.mjs` passes `/` in its place, which is how Astro spells
 * the same thing.
 */
export const BASE = '';

/** The repository, for edit links and for links to files the site does not hold. */
export const REPO = 'https://github.com/LevelFourAB/exofind';

/** Join a site-absolute path onto the base, with exactly one slash between. */
export function withBase(path) {
	return `${BASE}/${path.replace(/^\/+/, '')}`;
}
