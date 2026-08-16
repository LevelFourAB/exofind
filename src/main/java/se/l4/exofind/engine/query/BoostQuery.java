package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * Rank documents that satisfy the clauses it holds higher, without leaving out
 * the ones that do not.
 *
 * This is the clause that turns a rule of the business into part of the
 * ranking - staff picks first, what is in stock above what is not, this
 * month's campaign lifted a little. It is the only clause that widens nothing
 * and narrows nothing.
 *
 * @param weight
 *   how much satisfying the clauses counts, relative to the rest of the query.
 *   Above one lifts, below one holds back
 * @param clauses
 *   the clauses a document has to satisfy to be lifted
 */
public record BoostQuery(float weight, ImmutableList<Query> clauses) implements Query {
	public BoostQuery {
		if(clauses == null) {
			clauses = Lists.immutable.empty();
		}

		if(weight < 0) {
			throw new IllegalArgumentException("A boost can not weigh less than nothing");
		}
	}

	@Override
	public String type() {
		return "boost";
	}

	@Override
	public boolean scores() {
		return true;
	}

	/**
	 * Lift documents that satisfy all of the given clauses.
	 *
	 * @param weight
	 * @param clauses
	 * @return
	 */
	public static BoostQuery of(float weight, Query... clauses) {
		return new BoostQuery(weight, Lists.immutable.of(clauses));
	}

	/**
	 * Lift documents that satisfy all of the given clauses.
	 *
	 * @param weight
	 * @param clauses
	 * @return
	 */
	public static BoostQuery of(float weight, Iterable<? extends Query> clauses) {
		return new BoostQuery(weight, Lists.immutable.<Query>ofAll(clauses));
	}
}
