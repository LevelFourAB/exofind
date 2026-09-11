import { defineCollection } from 'astro:content';
import { docsSchema } from '@astrojs/starlight/schema';

import { docsFromRepository } from './content/loader.mjs';

/*
 * Every page of documentation is a file in `docs/`, read where it lies. The
 * pages that exist only because there is a site - the front page, the demos -
 * are Astro pages under `src/pages`, so nothing written for this site ends up
 * in the documentation that the repository is read for.
 *
 * `src/content/pages` is the third case: prose written for this site alone,
 * long enough that an Astro page would be markup around paragraphs. It is
 * read as a second root rather than moved into `docs/`, so the manual stays
 * the manual. A subdirectory there is the path the pages are served under -
 * `compare/algolia.md` is `/compare/algolia/` - which is how `docs/` works
 * too.
 */
export const collections = {
	docs: defineCollection({
		loader: docsFromRepository({
			roots: ['../docs', './src/content/pages'],
			repoRoot: '..'
		}),
		schema: docsSchema()
	})
};
