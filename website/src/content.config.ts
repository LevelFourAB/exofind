import { defineCollection } from 'astro:content';
import { docsSchema } from '@astrojs/starlight/schema';

import { docsFromRepository } from './content/loader.mjs';

/*
 * Every page of documentation is a file in `docs/`, read where it lies. The
 * pages that exist only because there is a site - the front page, the demos -
 * are Astro pages under `src/pages`, so nothing written for this site ends up
 * in the documentation that the repository is read for.
 */
export const collections = {
	docs: defineCollection({
		loader: docsFromRepository({
			roots: ['../docs'],
			repoRoot: '..'
		}),
		schema: docsSchema()
	})
};
