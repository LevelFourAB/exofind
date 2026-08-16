package se.l4.exofind.engine.query;

import org.eclipse.collections.api.list.ImmutableList;

/**
 * Where a hit sits in the order of a search - the values it was ordered by,
 * with its place in the index breaking ties between equal values.
 *
 * Every hit carries one, and handing it back as {@link SearchRequest.Builder#withAfter(SortKey)}
 * or {@link SearchRequest.Builder#withBefore(SortKey)} continues from that hit
 * without counting past everything before it, so moving through results costs
 * the same at any depth. A key only names a position under the sort it was
 * taken from - the values are one per step of that sort, tie breakers
 * included, and mean nothing under another.
 *
 * The tie break is not stable across a pull or a merge, so continuing from a
 * key can skip or repeat a document that moved between requests. That is the
 * drift every cursor design accepts rather than a bug.
 *
 * @param values
 *   the values the hit was ordered by, in sort order. Opaque - hand them back
 *   exactly as they were given
 * @param doc
 *   the place of the hit in the index, breaking ties between equal values
 */
public record SortKey(
	ImmutableList<Object> values,
	int doc
) {
	public SortKey {
		if(values == null || values.isEmpty()) {
			throw new IllegalArgumentException("A sort key needs the values the hit was ordered by");
		}
	}
}
