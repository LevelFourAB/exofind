package se.l4.exofind.engine.index;

import java.io.IOException;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DoubleValues;
import org.apache.lucene.search.DoubleValuesSource;
import org.apache.lucene.search.Explanation;
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
	public static RankingSignals of(ListIterable<Applied> signals) {
		if(signals.isEmpty()) {
			return null;
		}

		return new RankingSignals(Lists.immutable.ofAll(signals));
	}

	/**
	 * Get a value no multiplier these signals produce exceeds.
	 *
	 * <p>Every shape answers at most one, so a signal contributes at most
	 * {@code 1 + weight} and the product of those is a ceiling on what
	 * {@link #getValues} can say for any document. Rounded upward past any
	 * floating point error, so the bound holds rather than merely almost
	 * holds - it is what lets a search skip documents whose score cannot
	 * compete even lifted this far.
	 *
	 * @return
	 */
	public double maxMultiplier() {
		var product = 1d;
		for(var signal : signals) {
			product *= 1 + signal.weight();
		}

		return Math.nextUp(product);
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

	/**
	 * Get what each signal made of one document, and their product.
	 *
	 * <p>A signal the document holds no value for is reported as contributing
	 * one rather than left out.
	 *
	 * @param ctx
	 * @param doc
	 *   the document within the segment
	 * @return
	 * @throws IOException
	 */
	public Explanation explain(LeafReaderContext ctx, int doc) throws IOException {
		var details = new Explanation[signals.size()];
		var product = 1d;

		for(var i = 0; i < signals.size(); i++) {
			var signal = signals.get(i);
			var values = signal.values().getValues(ctx, null);

			if(!values.advanceExact(doc)) {
				details[i] = Explanation.match(
					1f,
					"signal " + signal.field() + " (" + signal.shape()
						+ ", weight " + signal.weight() + ") has no value here"
				);
				continue;
			}

			var value = values.doubleValue();
			var contribution = 1 + signal.weight() * signal.shape().contribution(value);
			product *= contribution;

			details[i] = Explanation.match(
				(float) contribution,
				"signal " + signal.field() + " (" + signal.shape()
					+ ", weight " + signal.weight() + ") reads " + value
			);
		}

		return Explanation.match((float) product, "signals, product of:", details);
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
	public RankingSignals rewrite(IndexSearcher searcher) throws IOException {
		var rewritten = Lists.mutable.<Applied>ofInitialCapacity(signals.size());
		var changed = false;

		for(var signal : signals) {
			var values = signal.values().rewrite(searcher);
			changed |= values != signal.values();
			rewritten.add(new Applied(signal.field(), values, signal.shape(), signal.weight()));
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
	 * @param field
	 *   name of the field the values are read from, as the definition calls it
	 *   - carried so that an explanation can name the signal the way the caller
	 *   asked for it
	 * @param values
	 *   the doc values the field wrote for sorting
	 * @param shape
	 *   what a value contributes
	 * @param weight
	 *   how much the contribution counts, as a share of the score
	 */
	public record Applied(String field, DoubleValuesSource values, Shape shape, float weight) {
		@Override
		public String toString() {
			return field + " " + shape + " x" + weight;
		}
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
		public String toString() {
			return "saturation, pivot " + pivot;
		}

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
		public String toString() {
			return "decay, half life " + halfLifeMillis + "ms";
		}

		@Override
		public double contribution(double value) {
			var age = now - value;
			if(age <= 0) {
				// Dated now or later, which is as recent as anything gets
				return 1;
			}

			return halving(age / (double) halfLifeMillis);
		}

		private static final double LN2 = Math.log(2);

		/*
		 * Coefficients of the Taylor series of e^t, for the fraction left
		 * after the halvings below are taken out whole.
		 */
		private static final double C2 = 1 / 2d;
		private static final double C3 = 1 / 6d;
		private static final double C4 = 1 / 24d;
		private static final double C5 = 1 / 120d;
		private static final double C6 = 1 / 720d;
		private static final double C7 = 1 / 5040d;

		/**
		 * Get what is left after halving something {@code x} times, {@code x}
		 * at least zero.
		 *
		 * <p>Computed here rather than asked of {@link Math#pow}, which
		 * answers to the full width of a double and costs the better part of
		 * ranking a document to do it - this runs once per document scored.
		 * The whole halvings are taken out through the exponent of the result,
		 * and what a fraction of one leaves is a series that stops when the
		 * terms fall below what a score can hold: the answer is within
		 * {@code 1e-8} of the true value, an order under the ulp of the float
		 * the score becomes.
		 */
		private static double halving(double x) {
			if(x > 1100) {
				// Closer to nothing than any width of number can tell apart
				return 0;
			}

			var whole = Math.rint(x);
			var t = (whole - x) * LN2;
			var fraction = 1 + t * (1 + t * (C2 + t * (C3 + t * (C4
				+ t * (C5 + t * (C6 + t * C7))))));

			return Math.scalb(fraction, -(int) whole);
		}
	}
}
