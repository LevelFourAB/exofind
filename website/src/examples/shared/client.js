/*
 * Talking to a node over the search API, and working out which node that is.
 *
 * A demo page is a static file that can be opened from a dev server, from a
 * deployment next to a node, or from a deployment somewhere else entirely, so
 * where to search is decided by the build - `PUBLIC_EXOFIND_NODE`, or
 * localhost for a page nobody has given one. A deployment points at a node by
 * being built for it, and never by anything a reader or a link can change.
 */

/** Thrown when a node answers a search with something other than a result. */
export class SearchError extends Error {
	constructor(message, status) {
		super(message);
		this.name = 'SearchError';
		this.status = status;
	}
}

/**
 * Work out which node and index to search.
 *
 * @param {{index: string}} defaults the index this example loads into
 */
export function resolveConfig(defaults) {
	return {
		node: trimSlash(import.meta.env.PUBLIC_EXOFIND_NODE || 'http://localhost:8080'),
		index: defaults.index
	};
}

function trimSlash(url) {
	return url.replace(/\/+$/, '');
}

/** A client bound to one node and index. */
export function createClient(config) {
	return {
		config,

		/**
		 * Run a search. Passing a signal lets a search that has been
		 * superseded be given up on rather than merely ignored, which matters
		 * when every keystroke starts one.
		 */
		async search(request, signal) {
			const response = await fetch(
				`${config.node}/v1alpha1/indexes/${encodeURIComponent(config.index)}/search`,
				{
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify(request),
					signal
				}
			);

			const body = await response.json().catch(() => null);

			if(!response.ok) {
				throw new SearchError(
					(body && body.message) || `The node answered ${response.status}`,
					response.status
				);
			}

			return body;
		},

		/** How many documents the index holds, whatever is being searched for. */
		async count() {
			const result = await this.search({ limit: 0, total: 'exact' });
			return result.total.count;
		}
	};
}

/**
 * Turn a fetch failure into something worth reading. A page opened against a
 * node that is not running, or one that does not allow this origin, both come
 * back as the same opaque `TypeError`, so say what to check rather than
 * repeating what the browser said.
 */
export function explain(error, config) {
	if(error instanceof SearchError) {
		return error.status === 404
			? `No index named ${config.index} on ${config.node} - load it first.`
			: error.message;
	}

	return `Could not reach ${config.node}. Check that a node is running there and allows this origin.`;
}
