package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.function.UnaryOperator;

import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.SearchResult;

/**
 * Counts the documents a search matched per level of one field whose values
 * are paths through a tree, reading the levels the field wrote under
 * {@link FieldNames#HIERARCHY}.
 *
 * A counter is created by the type of the field through
 * {@link se.l4.exofind.engine.index.types.FieldType#createHierarchyFacetCounter},
 * which is what knows how the levels of a path are told apart and how one is
 * compared - a type only says which field to read, what separates its levels
 * and how a level is normalized before two of them are called the same. The
 * counting itself is {@link HierarchyFacets}, which is also what rolls values
 * of an object field up into the documents holding them.
 */
public interface HierarchyFacetCounter {
	/**
	 * Count the given matches per level of the tree.
	 *
	 * @param matches
	 *   what to count, collected over the scope of the facet
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
	 *   the counts, nested as the tree is, never {@code null}
	 * @throws IOException
	 */
	SearchResult.Facet count(
		FacetMatches matches,
		String path,
		int depth,
		int limit,
		Facet.Order order
	) throws IOException;

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
		return (matches, path, depth, limit, order) ->
			HierarchyFacets.count(matches, field, separator, normalize, path, depth, limit, order);
	}
}
