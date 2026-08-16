package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * Match documents that satisfy none of the clauses it holds.
 *
 * Several clauses read as a list of things to leave out rather than as one
 * condition to invert, so a document is dropped as soon as any of them matches
 * it.
 *
 * Excluding is all this does - it never brings documents in, so a search made
 * only of exclusions still runs against everything the index holds.
 *
 * @param clauses
 *   the clauses that documents must not satisfy
 */
public record NotQuery(ImmutableList<Query> clauses) implements Query {
	public NotQuery {
		if(clauses == null) {
			clauses = Lists.immutable.empty();
		}
	}

	@Override
	public String type() {
		return "not";
	}

	@Override
	public boolean scores() {
		/*
		 * A document that is still here did not match anything inside, so
		 * there is nothing here for ranking to be built on.
		 */
		return false;
	}

	/**
	 * Match documents that satisfy none of the given clauses.
	 *
	 * @param clauses
	 * @return
	 */
	public static NotQuery of(Query... clauses) {
		return new NotQuery(Lists.immutable.of(clauses));
	}

	/**
	 * Match documents that satisfy none of the given clauses.
	 *
	 * @param clauses
	 * @return
	 */
	public static NotQuery of(Iterable<? extends Query> clauses) {
		return new NotQuery(Lists.immutable.<Query>ofAll(clauses));
	}
}
