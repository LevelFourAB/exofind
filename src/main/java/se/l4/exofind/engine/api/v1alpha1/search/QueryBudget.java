package se.l4.exofind.engine.api.v1alpha1.search;

import java.util.List;

import org.eclipse.collections.api.list.MutableList;

import se.l4.exofind.engine.api.v1alpha1.search.model.Clause;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchRequest;
import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.Location;

/**
 * Measures how much work the clauses of a search ask for, before any of them
 * are mapped.
 *
 * <p>Four numbers bound it. The number of clauses the whole request holds
 * bounds what the search costs to run, as every clause is a question asked of
 * the index. How deeply they nest bounds the stack frames spent compiling and
 * running the query, since a clause holding clauses is walked by recursion
 * here, in {@link SearchRequestMapper} and in the query compiler. A
 * {@code knn} clause and a {@code fuse} clause each carry a number of their
 * own that decides how far the index is read for one clause.
 *
 * <p>The two numbers bounding the size of the request refuse it whole. Reading
 * the rest of an oversized body to describe everything else wrong with it is
 * the work these caps exist to avoid. What one clause asks for is reported the
 * way the mapper reports problems, alongside everything else found.
 *
 * <p>Every clause that can hold clauses is walked here. The walk switches over
 * {@link Clause} without a default branch, so a clause type added to the API
 * without being counted here fails to compile.
 */
final class QueryBudget {
	private static final ErrorType TOO_MANY_CLAUSES =
		ErrorType.withCode("search:query:too_many_clauses")
			.withArguments("max")
			.withMessage("A search holds at most {{max}} clauses, counted across the whole request");

	private static final ErrorType TOO_DEEP =
		ErrorType.withCode("search:query:too_deep")
			.withArguments("max")
			.withMessage("Clauses nest at most {{max}} deep");

	private static final ErrorType K_TOO_LARGE =
		ErrorType.withCode("search:clause:k_too_large")
			.withArguments("max")
			.withMessage("A `knn` clause brings back at most {{max}} neighbours");

	private static final ErrorType DEPTH_TOO_LARGE =
		ErrorType.withCode("search:clause:depth_too_large")
			.withArguments("max")
			.withMessage("Each ranking of a `fuse` clause is read at most {{max}} results down");

	private final SearchLimits limits;
	private final MutableList<ErrorMessage> errors;

	/**
	 * Clauses seen so far, across every part of the request that holds them.
	 */
	private int clauses;

	/**
	 * Whether the request went over its size and the walk gave up. Set once,
	 * so a body that is both too large and too deep is answered with the first
	 * thing found rather than with one error per branch.
	 */
	private boolean oversized;

	private QueryBudget(SearchLimits limits, MutableList<ErrorMessage> errors) {
		this.limits = limits;
		this.errors = errors;
	}

	/**
	 * Measure the clauses of a search against what the node allows.
	 *
	 * @param body
	 *   the request as received, never {@code null}
	 * @param limits
	 *   what the node allows
	 * @param errors
	 *   where whatever is over budget is collected
	 * @return
	 *   {@code false} when the request holds more clauses than it may, or
	 *   nests them deeper. The whole request is refused then, so the rest of
	 *   it is not worth reading
	 */
	static boolean check(
		SearchRequest body,
		SearchLimits limits,
		MutableList<ErrorMessage> errors
	) {
		var budget = new QueryBudget(limits, errors);

		budget.walk(body.query(), "/query", 1);
		budget.walk(body.filters(), "/filters", 1);

		if(body.hits() != null) {
			budget.walk(body.hits().when(), "/hits/when", 1);
		}

		if(body.rescore() != null) {
			budget.walk(body.rescore().boost(), "/rescore/boost", 1);
		}

		return !budget.oversized;
	}

	/**
	 * Walk a list of clauses, counting each and descending into whatever it
	 * holds.
	 *
	 * @param clauses
	 *   the clauses to count, or {@code null} for a part the request left out
	 * @param path
	 *   JSON Pointer of the list itself; an entry reports at its own index
	 * @param depth
	 *   how deep the entries of this list sit, counting the clauses the
	 *   request carries directly as depth one
	 */
	private void walk(List<Clause> clauses, String path, int depth) {
		if(clauses == null || oversized) {
			return;
		}

		if(depth > limits.maxClauseDepth()) {
			refuse(TOO_DEEP.toMessage(Location.create(path), "max", limits.maxClauseDepth()));
			return;
		}

		for(var i = 0; i < clauses.size(); i++) {
			var clause = clauses.get(i);
			if(clause == null) {
				// The mapper reports what is missing; an absent clause costs nothing
				continue;
			}

			this.clauses++;
			if(this.clauses > limits.maxClauses()) {
				refuse(TOO_MANY_CLAUSES.toMessage(
					Location.create(path + "/" + i),
					"max", limits.maxClauses()
				));
				return;
			}

			var at = path + "/" + i;
			switch(clause) {
				case Clause.Field field -> {
					// Matches on a value of its own rather than on clauses
				}

				case Clause.Text text -> {
					// Matches on text of its own rather than on clauses
				}

				case Clause.Knn knn -> {
					/*
					 * A k below one is the mapper's to report - only a k the
					 * clause could otherwise be run with is measured here.
					 */
					if(knn.k() != null && knn.k() > limits.maxKnnK()) {
						errors.add(K_TOO_LARGE.toMessage(
							Location.create(at + "/k"),
							"max", limits.maxKnnK()
						));
					}

					walk(knn.filter(), at + "/filter", depth + 1);
				}

				case Clause.Fuse fuse -> {
					if(fuse.depth() != null && fuse.depth() > limits.maxFuseDepth()) {
						errors.add(DEPTH_TOO_LARGE.toMessage(
							Location.create(at + "/depth"),
							"max", limits.maxFuseDepth()
						));
					}

					if(fuse.rankings() != null) {
						for(var r = 0; r < fuse.rankings().size(); r++) {
							var ranking = fuse.rankings().get(r);
							if(ranking != null) {
								walk(
									ranking.clauses(),
									at + "/rankings/" + r + "/clauses",
									depth + 1
								);
							}
						}
					}

					walk(fuse.filter(), at + "/filter", depth + 1);
				}

				case Clause.Nested nested -> walk(nested.clauses(), at + "/clauses", depth + 1);

				case Clause.And and -> walk(and.clauses(), at + "/clauses", depth + 1);

				case Clause.Or or -> walk(or.clauses(), at + "/clauses", depth + 1);

				case Clause.Not not -> walk(not.clauses(), at + "/clauses", depth + 1);

				case Clause.Boost boost -> walk(boost.clauses(), at + "/clauses", depth + 1);
			}

			if(oversized) {
				return;
			}
		}
	}

	private void refuse(ErrorMessage message) {
		oversized = true;
		errors.add(message);
	}
}
