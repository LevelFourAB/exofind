package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * How one hit scored under one search.
 *
 * <p>The tree is stated in the terms the search was written in. A step
 * compiled from a clause of the request carries the path of that clause, and
 * fields are named as the definition names them rather than as they are
 * written into the index.
 *
 * <p>Steps that contributed nothing are kept, and a hit the search does not
 * match is answered with {@link #matched} false and a tree whose clause steps
 * are also marked as not matching.
 *
 * @param matched
 *   whether the hit satisfies the search
 * @param score
 *   what the hit scored, zero when it matched nothing. The same number a
 *   search reports for the hit
 * @param detail
 *   how the score was arrived at, never {@code null}
 * @param relaxed
 *   what the search let go of to match anything, or {@code null} when it let go
 *   of nothing. A search that matches nothing drops words and runs again, and
 *   what is explained is the search that ran
 */
public record SearchExplanation(
	boolean matched,
	float score,
	Detail detail,
	SearchResult.Relaxed relaxed
) {
	/**
	 * One step of a score.
	 *
	 * @param matched
	 *   whether this step was satisfied. A step that was not contributes
	 *   nothing, and its children say why
	 * @param score
	 *   what this step contributed to the step above it
	 * @param description
	 *   what the step is, in words
	 * @param clause
	 *   path of the clause this step was compiled from, such as
	 *   {@code query[0].clauses[2]}, or {@code null} for a step that is not a
	 *   clause of its own
	 * @param clauseType
	 *   the kind of clause, as {@link Query#type()} names it, or {@code null}
	 *   wherever {@code clause} is
	 * @param field
	 *   the field of the definition the step reads, or {@code null} when the
	 *   step reads none or reads several
	 * @param usage
	 *   which way of using the field the step reads it as, such as
	 *   {@code matching} or {@code filter}, or {@code null} wherever
	 *   {@code field} is
	 * @param locale
	 *   BCP 47 tag of the variant the step reads, or {@code null} for a field
	 *   holding one variant for every language
	 * @param children
	 *   the steps this one is made of, in the order they were reckoned. Empty
	 *   at a leaf, never {@code null}
	 */
	public record Detail(
		boolean matched,
		float score,
		String description,
		String clause,
		String clauseType,
		String field,
		String usage,
		String locale,
		ImmutableList<Detail> children
	) {
		public Detail {
			if(children == null) {
				children = Lists.immutable.empty();
			}
		}
	}
}
