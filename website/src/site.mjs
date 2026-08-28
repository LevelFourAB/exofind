/*
 * Where the site is served from, in one place.
 *
 * `base` is needed outside `astro.config.mjs` as well - the remark plugin that
 * rewrites links between documents builds URLs before Astro sees them, and
 * Astro prefixes nothing it did not generate itself. Importing the same
 * constant is what keeps the two from disagreeing.
 */

/** Origin the site is served from. */
export const SITE = 'https://levelfourab.github.io';

/**
 * Path the site is rooted at. GitHub Pages serves a project site under the
 * repository name, so every absolute URL on the site starts with this.
 */
export const BASE = '/exofind';

/** The repository, for edit links and for links to files the site does not hold. */
export const REPO = 'https://github.com/LevelFourAB/exofind';

/** Join a site-absolute path onto the base, with exactly one slash between. */
export function withBase(path) {
	return `${BASE}/${path.replace(/^\/+/, '')}`;
}
