package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * Match the documents whose vector in a field is nearest to a given one,
 * scored by how near they are.
 *
 * This is a top-k clause rather than a predicate - it returns the {@code k}
 * nearest documents rather than everything within some distance. The filter it
 * carries narrows which documents may be neighbours <em>before</em> the
 * nearest are picked, so a filtered search still returns up to {@code k}
 * results. That is why the filter sits on this clause instead of beside it:
 * a clause next to it would only intersect the global top-k afterwards, which
 * looks like missing results and can not be fixed by the caller.
 *
 * Scores, so combining it with text clauses through {@link OrQuery} or
 * {@link BoostQuery} adds the two rankings together - the plain Lucene form of
 * a hybrid search.
 *
 * Inside a {@link NestedQuery} the clause names a vector field of that path and
 * picks the {@code k} nearest values. A document holding several of them takes
 * up several of the {@code k}.
 *
 * The filter there names fields inside the path, as every clause inside a
 * {@code nested} clause does. A condition on a field of the index is written
 * beside the {@code nested} clause and applies after the nearest are picked, so
 * it can leave fewer than {@code k} results.
 *
 * @param field
 *   name of the vector field, as it is called in the definition of the index
 * @param vector
 *   the vector to find the neighbours of, with the dimensions the field
 *   declares
 * @param k
 *   how many neighbours to return
 * @param filter
 *   clauses narrowing which documents may be neighbours, all of which have to
 *   be satisfied; empty for no narrowing
 */
public record KnnQuery(String field, float[] vector, int k, ImmutableList<Query> filter)
	implements Query {
	public KnnQuery {
		if(filter == null) {
			filter = Lists.immutable.empty();
		}
	}

	@Override
	public String type() {
		return "knn";
	}

	@Override
	public boolean scores() {
		return true;
	}

	/**
	 * Get this clause with the given clauses narrowing which documents may be
	 * neighbours.
	 *
	 * @param clauses
	 * @return
	 */
	public KnnQuery withFilter(Query... clauses) {
		return new KnnQuery(field, vector, k, Lists.immutable.of(clauses));
	}

	/**
	 * Match the {@code k} documents whose vector in the given field is nearest
	 * to the given one.
	 *
	 * @param field
	 * @param vector
	 * @param k
	 * @return
	 */
	public static KnnQuery of(String field, float[] vector, int k) {
		return new KnnQuery(field, vector, k, Lists.immutable.empty());
	}
}
