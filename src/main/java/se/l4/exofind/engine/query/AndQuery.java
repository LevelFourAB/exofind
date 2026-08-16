package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * Match documents that satisfy every one of the clauses it holds.
 *
 * The clauses of a search are already combined this way, so this is only
 * needed for nesting - the branch of an {@link OrQuery} that takes more than
 * one clause to describe.
 *
 * @param clauses
 *   the clauses that all have to be satisfied, none of them matching every
 *   document
 */
public record AndQuery(ImmutableList<Query> clauses) implements Query {
	public AndQuery {
		if(clauses == null) {
			clauses = Lists.immutable.empty();
		}
	}

	@Override
	public String type() {
		return "and";
	}

	@Override
	public boolean scores() {
		return clauses.anySatisfy(Query::scores);
	}

	/**
	 * Match documents that satisfy all of the given clauses.
	 *
	 * @param clauses
	 * @return
	 */
	public static AndQuery of(Query... clauses) {
		return new AndQuery(Lists.immutable.of(clauses));
	}

	/**
	 * Match documents that satisfy all of the given clauses.
	 *
	 * @param clauses
	 * @return
	 */
	public static AndQuery of(Iterable<? extends Query> clauses) {
		return new AndQuery(Lists.immutable.<Query>ofAll(clauses));
	}
}
