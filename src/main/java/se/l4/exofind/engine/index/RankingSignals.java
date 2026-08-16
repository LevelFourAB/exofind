package se.l4.exofind.engine.index;

import java.io.IOException;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DoubleValues;
import org.apache.lucene.search.DoubleValuesSource;
import org.apache.lucene.search.IndexSearcher;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;

/**
 * What the values of the documents themselves multiply into their score.
 *
 * Each signal reads the doc values a field wrote for sorting, shapes the value
 * into a number between zero and one, and contributes {@code 1 + weight *
 * shape}; the contributions are multiplied together, and the product multiplies
 * the score of the search. Two properties hold whatever the shapes are, and
 * they are why the shaping exists:
 *
 * <ul>
 * <li>a document holding no value contributes exactly one, so a signal lifts
 * the documents that have it rather than removing the ones that do not
 * <li>a shape is bounded, so however far a value runs a signal can lift a
 * document by at most its weight and can never drown out how well it matched
 * </ul>
 *
 * Reading the values costs a doc values seek per scored document, which is the
 * price of shaping where the search runs rather than where the document was
 * indexed - and what lets a ranking be changed, or two of them compared,
 * without touching a single document.
 */
public final class RankingSignals extends DoubleValuesSource {
	private final ImmutableList<Applied> signals;

	private RankingSignals(ImmutableList<Applied> signals) {
		this.signals = signals;
	}

	/**
	 * Get the source that multiplies the given signals into a score, or
	 * {@code null} when there are none to multiply.
	 *
	 * @param signals
	 * @return
	 */
	public static DoubleValuesSource of(ListIterable<Applied> signals) {
		if(signals.isEmpty()) {
			return null;
		}

		return new RankingSignals(Lists.immutable.ofAll(signals));
	}

	@Override
	public DoubleValues getValues(LeafReaderContext ctx, DoubleValues scores) throws IOException {
		var values = new DoubleValues[signals.size()];
		for(var i = 0; i < values.length; i++) {
			values[i] = signals.get(i).values().getValues(ctx, null);
		}

		return new DoubleValues() {
			private double multiplier = 1;

			@Override
			public double doubleValue() {
				return multiplier;
			}

			@Override
			public boolean advanceExact(int doc) throws IOException {
				var product = 1d;
				for(var i = 0; i < values.length; i++) {
					if(!values[i].advanceExact(doc)) {
						// No value to read, so this signal says nothing here
						continue;
					}

					var signal = signals.get(i);
					product *= 1 + signal.weight()
						* signal.shape().contribution(values[i].doubleValue());
				}

				multiplier = product;

				/*
				 * Always a multiplier, even where every signal was silent - a
				 * document is ranked by how well it matched, and a signal it
				 * holds no value for leaves that untouched rather than
				 * multiplying it away.
				 */
				return true;
			}
		};
	}

	@Override
	public boolean needsScores() {
		// The values come from the documents, never from how they matched
		return false;
	}

	@Override
	public boolean isCacheable(LeafReaderContext ctx) {
		return signals.allSatisfy(signal -> signal.values().isCacheable(ctx));
	}

	@Override
	public DoubleValuesSource rewrite(IndexSearcher searcher) throws IOException {
		var rewritten = Lists.mutable.<Applied>ofInitialCapacity(signals.size());
		var changed = false;

		for(var signal : signals) {
			var values = signal.values().rewrite(searcher);
			changed |= values != signal.values();
			rewritten.add(new Applied(values, signal.shape(), signal.weight()));
		}

		return changed ? new RankingSignals(rewritten.toImmutable()) : this;
	}

	@Override
	public int hashCode() {
		return signals.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) {
			return true;
		}

		return obj instanceof RankingSignals other && signals.equals(other.signals);
	}

	@Override
	public String toString() {
		return "signals(" + signals.makeString(", ") + ")";
	}

	/**
	 * One signal as it runs: where to read the value, how to shape it and how
	 * much it counts.
	 *
	 * @param values
	 *   the doc values the field wrote for sorting
	 * @param shape
	 *   what a value contributes
	 * @param weight
	 *   how much the contribution counts, as a share of the score
	 */
	public record Applied(DoubleValuesSource values, Shape shape, float weight) {
	}

	/**
	 * How a value read from a field becomes what it contributes.
	 *
	 * A shape answers between zero and one, both ends included, which is what
	 * bounds how far a signal can move a document. A new shape is a record
	 * here, an entry in {@code permits} and a branch where signals are
	 * compiled.
	 */
	public sealed interface Shape permits Saturation, Decay {
		/**
		 * Get what the given value contributes, between zero and one.
		 *
		 * @param value
		 * @return
		 */
		double contribution(double value);
	}

	/**
	 * Contribute by how far a value is above a pivot, half at the pivot itself.
	 *
	 * @param pivot
	 *   the value that counts for half
	 */
	public record Saturation(double pivot) implements Shape {
		@Override
		public double contribution(double value) {
			/*
			 * Below zero the shape says nothing - it describes how much of
			 * something a document has, and having less than none of it is
			 * having none.
			 */
			var above = Math.max(value, 0);
			return above / (above + pivot);
		}
	}

	/**
	 * Contribute by how recent an instant is, halving every half life.
	 *
	 * @param halfLifeMillis
	 *   how long it takes for the contribution to halve
	 * @param now
	 *   the instant the search runs at, in milliseconds since the epoch, read
	 *   once so that every document of one search is aged against the same
	 *   clock
	 */
	public record Decay(long halfLifeMillis, long now) implements Shape {
		@Override
		public double contribution(double value) {
			var age = now - value;
			if(age <= 0) {
				// Dated now or later, which is as recent as anything gets
				return 1;
			}

			return Math.pow(0.5, age / halfLifeMillis);
		}
	}
}
