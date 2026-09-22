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
 *
 * The changelog is the fourth: one file, at the root of the repository,
 * written by the release process. It is a page of the site for the reader who
 * wants to know what a version changed, and it is in no sidebar - the footer
 * and the version in the header are where it is offered.
 */
export const collections = {
	docs: defineCollection({
		loader: docsFromRepository({
			roots: ['../docs', './src/content/pages'],
			files: [
				{
					id: 'changelog',
					path: '../CHANGELOG.md',
					data: {
						/*
						 * Both are stated here because the file cannot carry
						 * them: Release Please writes it, and frontmatter or a
						 * paragraph added to it is lost at the next release.
						 *
						 * The list is cut at the version headings. Every
						 * release also has a heading per kind of change, and a
						 * list holding those is a list of the word `Features`
						 * repeated once per release.
						 */
						description: 'Every released version of Exofind, and what changed in it.',
						tableOfContents: { minHeadingLevel: 2, maxHeadingLevel: 2 }
					}
				}
			],
			repoRoot: '..'
		}),
		schema: docsSchema()
	})
};
