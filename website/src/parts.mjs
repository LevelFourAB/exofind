/*
 * The parts of the manual, read from the documentation index.
 *
 * The sidebar shows some sections as their contents rather than as a group -
 * see `./sidebar.mjs` - so the sidebar alone no longer says which kind of
 * documentation a page is. The header and the label over a page title need
 * that, and reading the same index is what keeps them from being a second list
 * to keep in step: a section added, renamed or reordered in `docs/README.md`
 * arrives in all three on its own.
 *
 * This module is read while the site is configured, and never from a page: a
 * page is rendered from a bundle, where a path relative to a source file no
 * longer leads to the repository. `astro.config.mjs` defines `PARTS` into the
 * bundle instead, and `./nav.mjs` is what reads it there.
 */

import { partsFrom } from './sidebar.mjs';

/** The documentation index, which the navigation is built from. */
export const DOCS_INDEX = new URL('../../docs/README.md', import.meta.url);

/** @type {import('./sidebar.mjs').Part[]} */
export const PARTS = partsFrom(DOCS_INDEX);
