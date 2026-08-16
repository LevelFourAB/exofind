package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * Match documents that satisfy at least one of the clauses it holds.
 *
 * A filter where several values can be ticked at once is this clause, or the
 * {@code in} matcher when the values are all in the same field.
 *
 * @param clauses
 *   the clauses, one of which is enough. An empty list matches nothing, so a
 *   filter nobody has picked a value in narrows to nothing rather than
 *   silently widening to everything
 */
public record OrQuery(ImmutableList<Query> clauses) implements Query {
	public OrQuery {
		if(clauses == null) {
			clauses = Lists.immutable.empty();
		}
	}

	@Override
	public String type() {
		return "or";
	}

	@Override
	public boolean scores() {
		return clauses.anySatisfy(Query::scores);
	}

	/**
	 * Match documents that satisfy any of the given clauses.
	 *
	 * @param clauses
	 * @return
	 */
	public static OrQuery of(Query... clauses) {
		return new OrQuery(Lists.immutable.of(clauses));
	}

	/**
	 * Match documents that satisfy any of the given clauses.
	 *
	 * @param clauses
	 * @return
	 */
	public static OrQuery of(Iterable<? extends Query> clauses) {
		return new OrQuery(Lists.immutable.<Query>ofAll(clauses));
	}
}
