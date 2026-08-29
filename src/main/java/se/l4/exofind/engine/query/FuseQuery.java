package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * Match what several rankings found, scored by where each of them placed a
 * document.
 *
 * Each ranking is searched on its own, down to {@code depth} results. A
 * document is then scored by the sum of {@code weight / (rankConstant + rank)}
 * over the rankings that reached it, counting the first result of a ranking as
 * rank one. Only the position a ranking gave a document is read, so a text
 * ranking scored in BM25 and a {@link KnnQuery} scored in cosine similarity
 * combine without either scale being normalized into the other:
 *
 * <pre>
 * SearchRequest.create()
 *   .withQuery(
 *     Query.fuse(
 *       FuseQuery.ranking(Query.text("waterproof jacket")),
 *       FuseQuery.ranking(Query.knn("embedding", vector, 100))
 *     ).withFilter(Query.field("inStock", Matchers.equalTo(true)))
 *   )
 *   .build()
 * </pre>
 *
 * A document that several rankings placed well outranks one that a single
 * ranking placed first. A ranking that answers badly costs the results it
 * would have contributed and cannot take the page over, so a ranking nobody
 * vouched for - one by a vector built from what a person did before - degrades
 * into noise instead of hijacking the search.
 *
 * This is a top-N clause the way {@code knn} is a top-k clause. Its matches are
 * the {@code depth} best of each ranking and nothing else, however many
 * documents satisfy them. That bounds the total, the facet counts and how deep
 * paging reaches. A clause beside it narrows the merged list afterwards, so a
 * condition every result has to meet belongs in {@link #filter()}, which
 * reaches each ranking before it is cut to depth.
 *
 * The fused score is a sum of reciprocal ranks, a number near
 * {@code 1 / rankConstant}. A clause beside this one that also scores adds its
 * own scale on top, which is the blend fusing exists to avoid. Rank with the
 * fusion and filter with everything beside it.
 *
 * @param rankings
 *   the rankings to fuse, at least two. Each is run on its own, and a document
 *   is scored by where it landed in the ones that reached it
 * @param depth
 *   how far down each ranking is read, at least one. A document no ranking
 *   placed inside its first {@code depth} results is not a result at all.
 *   Deeper reaches more of what a single ranking found, and costs a longer
 *   list per ranking to merge
 * @param rankConstant
 *   how much the difference between neighbouring ranks counts, above zero.
 *   Small makes the first few results of each ranking count for much more than
 *   the rest; large flattens the difference out, so being found by several
 *   rankings matters more than being found first by one
 * @param filter
 *   clauses narrowing every ranking before it is cut to depth, all of which
 *   have to be satisfied; empty for no narrowing. A {@code knn} inside a
 *   ranking takes these as its own pre-filter, so a narrowed vector ranking
 *   still returns the neighbours it asked for
 */
public record FuseQuery(
	ImmutableList<Ranking> rankings,
	int depth,
	float rankConstant,
	ImmutableList<Query> filter
) implements Query {
	/**
	 * How far down each ranking is read when nothing else is asked for.
	 */
	public static final int DEFAULT_DEPTH = 100;

	/**
	 * How much the difference between neighbouring ranks counts when nothing
	 * else is asked for. Sixty is the constant reciprocal rank fusion was
	 * published with and what everything measured against it uses.
	 */
	public static final float DEFAULT_RANK_CONSTANT = 60f;

	/**
	 * How much a ranking counts when nothing else is asked for.
	 */
	public static final float DEFAULT_WEIGHT = 1f;

	public FuseQuery {
		if(rankings == null || rankings.size() < 2) {
			throw new IllegalArgumentException(
				"Fusing ranks a document by where several rankings put it, so it needs at least two of them"
			);
		}

		if(depth < 1) {
			throw new IllegalArgumentException("A ranking can not be read less than one deep");
		}

		if(!(rankConstant > 0) || !Float.isFinite(rankConstant)) {
			throw new IllegalArgumentException(
				"How much neighbouring ranks differ by has to be above zero"
			);
		}

		if(filter == null) {
			filter = Lists.immutable.empty();
		}
	}

	@Override
	public String type() {
		return "fuse";
	}

	@Override
	public boolean scores() {
		return true;
	}

	/**
	 * One of the rankings being fused - the clauses it runs, and how much
	 * where it put a document counts.
	 *
	 * A weight scales what this ranking contributes to every document it
	 * reached. The clauses alone decide the order inside the ranking, so a
	 * weight only weighs one ranking against another.
	 *
	 * @param clauses
	 *   what the ranking searches for, all of which have to be satisfied. At
	 *   least one
	 * @param weight
	 *   how much where this ranking put a document counts against the other
	 *   rankings, zero or above
	 */
	public record Ranking(ImmutableList<Query> clauses, float weight) {
		public Ranking {
			if(clauses == null || clauses.isEmpty()) {
				throw new IllegalArgumentException("A ranking needs something to rank by");
			}

			if(!(weight >= 0) || !Float.isFinite(weight)) {
				throw new IllegalArgumentException("A ranking can not weigh less than nothing");
			}
		}

		/**
		 * Get this ranking counted by the given weight, with what it searches
		 * for left as it is.
		 *
		 * @param weight
		 * @return
		 */
		public Ranking withWeight(float weight) {
			return new Ranking(clauses, weight);
		}
	}

	/**
	 * One ranking of a fusion, counting as much as the others.
	 *
	 * @param clauses
	 *   what the ranking searches for, all of which have to be satisfied
	 * @return
	 */
	public static Ranking ranking(Query... clauses) {
		return new Ranking(Lists.immutable.of(clauses), DEFAULT_WEIGHT);
	}

	/**
	 * One ranking of a fusion, counting as much as the others.
	 *
	 * @param clauses
	 *   what the ranking searches for, all of which have to be satisfied
	 * @return
	 */
	public static Ranking ranking(Iterable<? extends Query> clauses) {
		return new Ranking(Lists.immutable.ofAll(clauses), DEFAULT_WEIGHT);
	}

	/**
	 * Fuse the given rankings, reading each of them as deep as
	 * {@link #DEFAULT_DEPTH}.
	 *
	 * @param rankings
	 * @return
	 */
	public static FuseQuery of(Ranking... rankings) {
		return new FuseQuery(
			Lists.immutable.of(rankings),
			DEFAULT_DEPTH,
			DEFAULT_RANK_CONSTANT,
			Lists.immutable.empty()
		);
	}

	/**
	 * Fuse the given rankings, reading each of them as deep as
	 * {@link #DEFAULT_DEPTH}.
	 *
	 * @param rankings
	 * @return
	 */
	public static FuseQuery of(Iterable<? extends Ranking> rankings) {
		return new FuseQuery(
			Lists.immutable.ofAll(rankings),
			DEFAULT_DEPTH,
			DEFAULT_RANK_CONSTANT,
			Lists.immutable.empty()
		);
	}

	/**
	 * Get this clause reading each of its rankings the given number of results
	 * deep, with everything else about it left as it is.
	 *
	 * @param depth
	 * @return
	 */
	public FuseQuery withDepth(int depth) {
		return new FuseQuery(rankings, depth, rankConstant, filter);
	}

	/**
	 * Get this clause counting neighbouring ranks the given amount apart, with
	 * everything else about it left as it is.
	 *
	 * @param rankConstant
	 * @return
	 */
	public FuseQuery withRankConstant(float rankConstant) {
		return new FuseQuery(rankings, depth, rankConstant, filter);
	}

	/**
	 * Get this clause with the given clauses narrowing every ranking before it
	 * is cut to depth.
	 *
	 * @param clauses
	 * @return
	 */
	public FuseQuery withFilter(Query... clauses) {
		return new FuseQuery(rankings, depth, rankConstant, Lists.immutable.of(clauses));
	}

	/**
	 * Get this clause with the given clauses narrowing every ranking before it
	 * is cut to depth.
	 *
	 * @param clauses
	 * @return
	 */
	public FuseQuery withFilter(Iterable<? extends Query> clauses) {
		return new FuseQuery(rankings, depth, rankConstant, Lists.immutable.ofAll(clauses));
	}
}
