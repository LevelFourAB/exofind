/*
 * Keeping the search in the URL.
 *
 * What is on screen is worth being able to send to someone, and worth
 * surviving a reload, so the text and the filters live in the query string.
 * Which node is searched is not in there at all - the build decides that.
 *
 * Writing replaces the current entry rather than adding one, because a
 * keystroke is not somewhere to go back to.
 */

/** What the URL says the search is. */
export function readParams() {
	return new URLSearchParams(location.search);
}

/**
 * Put a search in the URL.
 *
 * Values are given per name: a string, or anything iterable for a name that
 * may appear more than once. Empty and absent values are left out, so a
 * search nobody has narrowed leaves a clean URL.
 *
 * @param {Object} search what to write, by parameter name
 */
export function writeParams(search) {
	const params = new URLSearchParams();

	for(const [name, value] of Object.entries(search)) {
		for(const one of listOf(value)) params.append(name, one);
	}

	const query = params.toString();
	history.replaceState(null, '', query ? `?${query}` : location.pathname);
}

function listOf(value) {
	if(value === null || value === undefined || value === '') return [];
	if(typeof value === 'string') return [value];
	if(typeof value[Symbol.iterator] === 'function') return [...value].filter(Boolean);

	return [String(value)];
}

/**
 * A range bucket as it is written in a URL - `100-250`, `-100` for everything
 * under a value and `500-` for everything above one.
 *
 * The buckets a page counts into are what a range can be, so a range comes
 * back by matching one of them rather than by being parsed. A URL naming a
 * range the page no longer offers simply picks nothing.
 */
export function rangeToParam(range) {
	if(!range) return null;

	return `${range.from ?? ''}-${range.to ?? ''}`;
}

export function rangeFromParam(text, ranges) {
	if(!text) return null;

	return ranges.find(range => rangeToParam(range) === text) || null;
}
