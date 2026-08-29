package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.DoubleValues;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Matches;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;

/**
 * A query scored as the wrapped query, multiplied by what the {@link
 * RankingSignals} read from each document.
 *
 * <p>What matches, and in what order the matches are visited, is entirely the
 * wrapped query's; only the score changes. The multiplier is read per scored
 * document, so a document is paid for only when it is scored.
 *
 * <p>The reason this exists instead of Lucene's {@code FunctionScoreQuery}: a
 * multiplier read from documents could be anything, so that query reports an
 * unbounded maximum score and a search under it scores every match. Signals
 * are bounded - every multiplier lies between one and {@link
 * RankingSignals#maxMultiplier()} - and this query passes that bound through,
 * so a search for the best few documents can still skip the ranges of
 * documents whose score cannot compete even lifted as far as the signals
 * reach. The bound is leaned away from in both directions before it is used,
 * so floating point rounding widens the range a document survives in rather
 * than narrowing it: skipping is never allowed to lose a competitive document.
 */
final class SignalScoreQuery extends Query {
	private final Query in;
	private final RankingSignals signals;

	/**
	 * @param in
	 *   the query that decides what matches and how well
	 * @param signals
	 *   what to multiply each score by
	 */
	SignalScoreQuery(Query in, RankingSignals signals) {
		this.in = Objects.requireNonNull(in);
		this.signals = Objects.requireNonNull(signals);
	}

	@Override
	public Query rewrite(IndexSearcher searcher) throws IOException {
		var rewrittenIn = in.rewrite(searcher);
		var rewrittenSignals = signals.rewrite(searcher);
		if(rewrittenIn == in && rewrittenSignals == signals) {
			return this;
		}

		return new SignalScoreQuery(rewrittenIn, rewrittenSignals);
	}

	@Override
	public void visit(QueryVisitor visitor) {
		in.visit(visitor.getSubVisitor(BooleanClause.Occur.MUST, this));
	}

	@Override
	public Weight createWeight(
		IndexSearcher searcher,
		ScoreMode scoreMode,
		float boost
	) throws IOException {
		/*
		 * The boost goes to the wrapped query rather than being applied here:
		 * the multiplier does not read the score it scales, so where in the
		 * product the boost lands makes no difference to the result, and the
		 * wrapped query already knows how to carry one.
		 */
		var innerWeight = in.createWeight(searcher, scoreMode, boost);
		var maxMultiplier = signals.maxMultiplier();

		return new Weight(this) {
			@Override
			public boolean isCacheable(LeafReaderContext ctx) {
				return innerWeight.isCacheable(ctx) && signals.isCacheable(ctx);
			}

			@Override
			public int count(LeafReaderContext context) throws IOException {
				return innerWeight.count(context);
			}

			@Override
			public Matches matches(LeafReaderContext context, int doc) throws IOException {
				return innerWeight.matches(context, doc);
			}

			@Override
			public Explanation explain(LeafReaderContext context, int doc) throws IOException {
				var inner = innerWeight.explain(context, doc);
				if(!inner.isMatch()) {
					return inner;
				}

				var values = signals.getValues(context, null);
				values.advanceExact(doc);
				var multiplier = values.doubleValue();

				return Explanation.match(
					(float) (inner.getValue().doubleValue() * multiplier),
					"product of:",
					inner,
					signals.explain(context, doc)
				);
			}

			@Override
			public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
				var innerSupplier = innerWeight.scorerSupplier(context);
				if(innerSupplier == null) {
					return null;
				}

				return new ScorerSupplier() {
					@Override
					public Scorer get(long leadCost) throws IOException {
						return new SignalScorer(
							innerSupplier.get(leadCost),
							signals.getValues(context, null),
							maxMultiplier
						);
					}

					@Override
					public long cost() {
						return innerSupplier.cost();
					}

					@Override
					public void setTopLevelScoringClause() throws IOException {
						innerSupplier.setTopLevelScoringClause();
					}
				};
			}
		};
	}

	@Override
	public String toString(String field) {
		return "signals(" + in.toString(field) + ", " + signals + ")";
	}

	@Override
	public boolean equals(Object other) {
		return sameClassAs(other)
			&& in.equals(((SignalScoreQuery) other).in)
			&& signals.equals(((SignalScoreQuery) other).signals);
	}

	@Override
	public int hashCode() {
		return Objects.hash(classHash(), in, signals);
	}

	/**
	 * The wrapped scorer with the multiplier applied, and the bound carried
	 * both ways: upward through {@link #getMaxScore}, downward through {@link
	 * #setMinCompetitiveScore}.
	 */
	private static final class SignalScorer extends Scorer {
		private final Scorer in;
		private final DoubleValues multiplier;
		private final double maxMultiplier;

		SignalScorer(Scorer in, DoubleValues multiplier, double maxMultiplier) {
			this.in = in;
			this.multiplier = multiplier;
			this.maxMultiplier = maxMultiplier;
		}

		@Override
		public int docID() {
			return in.docID();
		}

		@Override
		public DocIdSetIterator iterator() {
			return in.iterator();
		}

		@Override
		public TwoPhaseIterator twoPhaseIterator() {
			return in.twoPhaseIterator();
		}

		@Override
		public int advanceShallow(int target) throws IOException {
			return in.advanceShallow(target);
		}

		@Override
		public float score() throws IOException {
			var score = in.score();
			multiplier.advanceExact(in.docID());
			return (float) (score * multiplier.doubleValue());
		}

		@Override
		public float getMaxScore(int upTo) throws IOException {
			/*
			 * A step up past the float rounding of both the product here and
			 * the one score() reports, so no document in the range scores
			 * above what this promised.
			 */
			return Math.nextUp((float) (in.getMaxScore(upTo) * maxMultiplier));
		}

		@Override
		public void setMinCompetitiveScore(float minScore) throws IOException {
			/*
			 * A score lifted as far as the bound allows and still below the
			 * minimum cannot compete, so the wrapped scorer may skip it. Two
			 * steps down so that every rounding along the way - the division
			 * here, the multiplication in score() - falls inside the slack
			 * rather than past the threshold.
			 */
			in.setMinCompetitiveScore(
				Math.max(0, Math.nextDown(Math.nextDown(
					(float) (minScore / maxMultiplier)
				)))
			);
		}
	}
}
