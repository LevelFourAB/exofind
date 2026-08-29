package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * A second pass over the best results of a search, reordering them without
 * changing which documents matched.
 *
 * <p>The first pass ranks the whole result set by relevance. Its best
 * {@code window} results are scored a second time by what this holds, and the
 * two scores are added as {@code first + weight * second}. Results below the
 * window keep the order the first pass gave them. A boost here can move a
 * result up the first page. It can never pull one onto it.
 *
 * <pre>
 * SearchRequest.create()
 *   .withQuery(Query.text("running shoes"))
 *   .withRescore(
 *     Rescore.of(200, BoostQuery.of(2f, Query.field("brand", Matchers.equalTo("adidas"))))
 *       .withSignals(RankingSignal.saturation("purchases", 50))
 *   )
 *   .build()
 * </pre>
 *
 * <p>Only read where relevance is the ordering. A search that sorts by a field
 * of its own is ordered by that field, and a rescore says nothing about it -
 * the same rule {@link RankingSignal signals} follow.
 *
 * <p>The window is ranked from the first result every time, so a page inside
 * it costs the same whichever page it is. A search whose {@code offset} and
 * {@code limit} reach past the window is refused rather than answered from a
 * window that does not cover it.
 *
 * @param window
 *   how many of the best results take part, at least one. A hundred or a few
 *   hundred is the range this is meant for - every result in the window is
 *   scored again
 * @param boost
 *   clauses that lift what satisfies them. Nothing here narrows: a clause that
 *   would exclude a document elsewhere in a search only leaves it unlifted.
 *   Weigh one against another by wrapping it in a {@link BoostQuery}
 * @param signals
 *   the values of the documents themselves to take into the second score,
 *   shaped the way a {@link RankingSignal} always is. These are the whole of
 *   what the second pass reads - the ranking of the index belongs to the first
 *   pass and is never applied again here. Applied to every result in the
 *   window, whether or not it satisfied a boost
 * @param weight
 *   how much the second score counts against the first
 */
public record Rescore(
	int window,
	ImmutableList<Query> boost,
	ImmutableList<RankingSignal> signals,
	float weight
) {
	/**
	 * How much the second score counts when nothing else is asked for.
	 */
	public static final float DEFAULT_WEIGHT = 1f;

	public Rescore {
		if(window < 1) {
			throw new IllegalArgumentException("A rescore has to reach at least one result");
		}

		if(boost == null) {
			boost = Lists.immutable.empty();
		}

		if(signals == null) {
			signals = Lists.immutable.empty();
		}

		if(boost.isEmpty() && signals.isEmpty()) {
			throw new IllegalArgumentException(
				"A rescore has to hold a boost or a signal to reorder by"
			);
		}

		if(!(weight >= 0) || !Float.isFinite(weight)) {
			throw new IllegalArgumentException("A rescore can not weigh less than nothing");
		}
	}

	/**
	 * Reorder the best results by the given clauses.
	 *
	 * @param window
	 *   how many of the best results take part
	 * @param boost
	 *   the clauses that lift what satisfies them
	 * @return
	 */
	public static Rescore of(int window, Query... boost) {
		return new Rescore(window, Lists.immutable.of(boost), null, DEFAULT_WEIGHT);
	}

	/**
	 * Reorder the best results by the given clauses.
	 *
	 * @param window
	 *   how many of the best results take part
	 * @param boost
	 *   the clauses that lift what satisfies them
	 * @return
	 */
	public static Rescore of(int window, Iterable<? extends Query> boost) {
		return new Rescore(window, Lists.immutable.ofAll(boost), null, DEFAULT_WEIGHT);
	}

	/**
	 * Reorder the best results by the given signals.
	 *
	 * @param window
	 *   how many of the best results take part
	 * @param signals
	 *   the values of the documents to take into the second score
	 * @return
	 */
	public static Rescore ofSignals(int window, RankingSignal... signals) {
		return new Rescore(window, null, Lists.immutable.of(signals), DEFAULT_WEIGHT);
	}

	/**
	 * Get this rescore reading the given signals instead, with everything else
	 * about it left as it is.
	 *
	 * @param signals
	 * @return
	 */
	public Rescore withSignals(RankingSignal... signals) {
		return new Rescore(window, boost, Lists.immutable.of(signals), weight);
	}

	/**
	 * Get this rescore lifting by the given clauses instead, with everything
	 * else about it left as it is.
	 *
	 * @param boost
	 * @return
	 */
	public Rescore withBoost(Query... boost) {
		return new Rescore(window, Lists.immutable.of(boost), signals, weight);
	}

	/**
	 * Get this rescore counted by the given weight, with everything else about
	 * it left as it is.
	 *
	 * @param weight
	 * @return
	 */
	public Rescore withWeight(float weight) {
		return new Rescore(window, boost, signals, weight);
	}
}
