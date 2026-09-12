/*
 * Making the diagrams `astro-d2` draws belong to this site.
 *
 * D2 is told what to draw by the code block, and everything else - where the
 * picture is written and what it is drawn in - by the source it is handed.
 * These two plugins supply that, so a document holds the diagram and nothing
 * about the site it is published on.
 *
 * Both run before `astro-d2`, which adds itself to the same list of remark
 * plugins when `astro.config.mjs` sets the integration up.
 */

import { sep } from 'node:path';

import { visit } from 'unist-util-visit';

import { docsPathOf } from './remark-docs.mjs';

/**
 * The greys a diagram is drawn in, light theme first.
 *
 * They are the grey ramp of `../styles/site.css`, copied rather than imported,
 * because D2 is given colours as text on its way in and reads no stylesheet.
 * A change to the palette is made in both files.
 *
 * The ramp is used twice over. `N1`-`N7` are what D2 draws text, rules and
 * paper with, and `B1`-`B6` are what it fills a shape with; the `A` pairs are
 * the accents it reserves for the parts of a class or a table. Every one of
 * them is a grey here, so a diagram carries no colour of its own: the page it
 * sits on spends its one accent on the links, and a drawing that answers a
 * question in blue is a drawing that argues with them.
 *
 * Both ramps start at the grey the prose is set in rather than at the grey a
 * heading is set in, and end at the paper. A diagram is read at the same
 * distance as the sentence above it, and a drawing ruled in the darkest step
 * of the palette carries more weight on the page than the words it explains.
 * The steps in between fall away to the hairline grey, which is what a shape
 * is filled with.
 *
 * Every key D2 has is named. A key left out keeps the colour of the theme
 * underneath, which is neither grey nor this site's.
 */
const PALETTE = {
	light: {
		N1: '#3e474e', N2: '#5c666e', N3: '#a9b2ac', N4: '#ccd2cd',
		N5: '#e7eae6', N6: '#eff1ee', N7: '#f7f8f6',
		B1: '#3e474e', B2: '#5c666e', B3: '#a9b2ac', B4: '#ccd2cd',
		B5: '#e7eae6', B6: '#eff1ee',
		AA2: '#5c666e', AA4: '#ccd2cd', AA5: '#e7eae6',
		AB4: '#ccd2cd', AB5: '#e7eae6'
	},
	dark: {
		N1: '#a6b0b5', N2: '#8b969c', N3: '#414c52', N4: '#293237',
		N5: '#161c20', N6: '#161c20', N7: '#0f1417',
		B1: '#a6b0b5', B2: '#8b969c', B3: '#414c52', B4: '#293237',
		B5: '#161c20', B6: '#161c20',
		AA2: '#8b969c', AA4: '#293237', AA5: '#161c20',
		AB4: '#293237', AB5: '#161c20'
	}
};

/**
 * What is put in front of every diagram written on this site.
 *
 * `style.fill` is the paper the diagram is drawn on, and `transparent` is what
 * leaves it out: the picture is an image in the middle of a page, and any
 * paper of its own is a pale rectangle on a page of a different shade - wrong
 * in one theme whichever shade is picked, and wrong in both where a reader
 * overrides the theme the diagram follows.
 *
 * It goes in front rather than behind so that a diagram can still say
 * something else. D2 keeps the last value of a key, so a block that writes its
 * own `style.fill` or its own colour overrides this.
 */
const PREAMBLE = [
	'vars: {',
	'  d2-config: {',
	`    theme-overrides: {${overrides(PALETTE.light)}}`,
	`    dark-theme-overrides: {${overrides(PALETTE.dark)}}`,
	'  }',
	'}',
	'style.fill: transparent',
	''
].join('\n');

function overrides(palette) {
	return Object.entries(palette)
		.map(([key, colour]) => `${key}: "${colour}"`)
		.join('; ');
}

/**
 * Draw every diagram in this site's greys, on no paper of its own.
 *
 * D2 takes both as part of the source rather than as options, so the only way
 * to state them once for the whole site is to put them in front of what the
 * block says.
 *
 * A diagram whose source is in a file of its own - `src=./name.d2` on the
 * block - is read from that file and keeps D2's own theme. State the colours
 * there where one is used.
 */
export function remarkDiagramStyle() {
	return tree => {
		visit(tree, 'code', node => {
			if(node.lang === 'd2') node.value = `${PREAMBLE}\n${node.value}`;
		});
	};
}

/**
 * Point `astro-d2` at a diagram path that mirrors the URL of the page.
 *
 * `astro-d2` names the SVG it draws for a diagram after the path of the
 * Markdown file relative to the working directory, and writes it under
 * `public/`. A file in `docs/` is outside this project, so that path starts
 * with `..`: the image would be written beside the repository's documentation
 * and asked for at a URL the site does not serve.
 *
 * Moving the working directory of the file to the root it was loaded from
 * settles both. `docs/explanation/synchronization.md` is then
 * `explanation/synchronization.md`, so its first diagram is written to
 * `public/images/diagrams/explanation/synchronization-0.svg` and asked for at
 * `/images/diagrams/explanation/synchronization-0.svg` - the path of the page
 * with the diagram's number on the end.
 *
 * Only the working directory is moved. The file keeps its real path, which is
 * what a `src=` on a diagram resolves an external `.d2` file against, and what
 * Starlight's own plugins read.
 */
export function remarkDiagramPaths() {
	return (tree, file) => {
		const docsPath = docsPathOf(file);
		if(!docsPath || !file.path) return;

		const suffix = docsPath.split('/').join(sep);
		if(file.path.endsWith(suffix)) {
			file.cwd = file.path.slice(0, -suffix.length);
		}
	};
}
