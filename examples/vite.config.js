import { defineConfig } from 'vite';
import { readdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));

/* Directories that are here for the build rather than being an example. */
const NOT_EXAMPLES = new Set(['node_modules', 'dist', 'shared']);

/*
 * Every other directory holding an index.html is an example, so adding one is
 * adding the directory - there is no list here to keep in step with it.
 */
function examples() {
	return Object.fromEntries(
		readdirSync(here, { withFileTypes: true })
			.filter(entry =>
				entry.isDirectory()
					&& !entry.name.startsWith('.')
					&& !NOT_EXAMPLES.has(entry.name))
			.map(entry => [entry.name, resolve(here, entry.name, 'index.html')])
			.filter(([, page]) => existsSync(page))
	);
}

export default defineConfig({
	/*
	 * Relative asset URLs, so the built site works served from the root of a
	 * domain and from a subdirectory alike.
	 */
	base: './',

	build: {
		outDir: 'dist',
		emptyOutDir: true,
		rollupOptions: {
			input: {
				index: resolve(here, 'index.html'),
				...examples()
			}
		}
	}
});
