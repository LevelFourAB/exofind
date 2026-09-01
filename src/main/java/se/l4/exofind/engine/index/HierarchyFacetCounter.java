package se.l4.exofind.engine.index;

import java.util.function.UnaryOperator;

import se.l4.exofind.engine.query.Facet;

/**
 * Counts the matches of a search per level of one field whose values are
 * paths through a tree, reading the levels the field wrote under
 * {@link FieldNames#HIERARCHY}.
 *
 * A counter is created by the type of the field through
 * {@link se.l4.exofind.engine.index.types.FieldType#createHierarchyFacetCounter},
 * which is what knows how the levels of a path are told apart and how one is
 * compared - a type only says which field to read, what separates its levels
 * and how a level is normalized before two of them are called the same. The
 * counting itself is {@link HierarchyFacetCount}, fed by the shared walk of
 * the facet's scope - see {@link FacetWalk}.
 */
public interface HierarchyFacetCounter {
	/**
	 * Prepare to count one scope, per level of the tree.
	 *
	 * @param mode
	 *   what the matches of the scope are and what the counts should be of
	 * @param path
	 *   the level to count the children of, or {@code null} to count from the
	 *   top
	 * @param depth
	 *   how many levels below {@code path} to count
	 * @param limit
	 *   how many levels to bring back at most, per level counted
	 * @param order
	 *   the order the levels of one parent come back in
	 * @return
	 *   the count to feed through {@link FacetWalk}, never {@code null} - its
	 *   result answers the counts nested as the tree is
	 */
	FacetCount prepare(
		FacetMatches.Mode mode,
		String path,
		int depth,
		int limit,
		Facet.Order order
	);

	/**
	 * Count a field whose levels were written as sorted set doc values.
	 *
	 * @param field
	 *   the Lucene field the levels were written under
	 * @param separator
	 *   what separates one level of a path from the next
	 * @param normalize
	 *   how a path is read before two of them are called the same, which is
	 *   what makes counting the children of a path agree with narrowing to it
	 * @return
	 */
	static HierarchyFacetCounter overPaths(
		String field,
		String separator,
		UnaryOperator<String> normalize
	) {
		return (mode, path, depth, limit, order) ->
			new HierarchyFacetCount(field, mode, separator, normalize, path, depth, limit, order);
	}
}
