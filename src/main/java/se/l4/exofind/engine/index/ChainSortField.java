package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.LeafFieldComparator;
import org.apache.lucene.search.Pruning;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.comparators.DoubleComparator;
import org.apache.lucene.search.comparators.FloatComparator;
import org.apache.lucene.search.comparators.IntComparator;
import org.apache.lucene.search.comparators.LongComparator;

/**
 * Ordering documents by the values a {@link ValueChain} reads for them.
 *
 * <p>Each document is ordered by the values of the first step of the chain it
 * holds any value on, and by one of those: the end the ordering asks for, the
 * same way {@link NestedSortField} picks one. A document holding no value on
 * any step sorts where the field of the first step puts a missing value.
 *
 * <p>The values are read through Lucene's own numeric comparators, handed a
 * view of the chain as the doc values of the document. Nothing skips: the
 * points of a field describe every value of it, not the value a chain picked
 * for a document, so they can not rule a document out.
 *
 * <p>Only numbers are ordered this way - an {@code INT}, {@code LONG},
 * {@code FLOAT} or {@code DOUBLE} sort. Number and timestamp fields write
 * these. Every step of the chain has to write the same kind, which
 * {@link QueryCompiler} checks before building one.
 */
final class ChainSortField extends SortField {
	private final ValueChain chain;
	private final boolean max;

	/**
	 * @param field
	 *   the Lucene field the first step reads, for anything that reads the
	 *   ordering back
	 * @param type
	 *   how the values compare, one of {@code INT}, {@code LONG},
	 *   {@code FLOAT} or {@code DOUBLE}
	 * @param reverse
	 *   whether documents are ordered from the highest value down
	 * @param max
	 *   whether the highest of a document's values stands for it. The lowest
	 *   stands for it otherwise
	 * @param chain
	 *   the values to order by
	 * @throws IllegalArgumentException
	 *   if the type is not one a number is compared as
	 */
	ChainSortField(
		String field,
		Type type,
		boolean reverse,
		boolean max,
		ValueChain chain
	) {
		super(field, type, reverse);

		switch(type) {
			case INT, LONG, FLOAT, DOUBLE -> {}
			default -> throw new IllegalArgumentException(
				"Ordering by a chain of values needs a numeric type, got " + type
			);
		}

		this.max = max;
		this.chain = Objects.requireNonNull(chain);
	}

	/**
	 * Get this ordering with its comparison flipped, for walking backwards from
	 * a position.
	 *
	 * Which value stands for a document is not part of the mirroring - a
	 * document ordered by its cheapest value is ordered by that same value
	 * whichever way the page is read - so only the comparison is flipped.
	 *
	 * @return
	 */
	ChainSortField mirrored() {
		var mirror = new ChainSortField(getField(), getType(), !getReverse(), max, chain);

		if(getMissingValue() != null) {
			mirror.setMissingValue(getMissingValue());
		}

		return mirror;
	}

	@Override
	public FieldComparator<?> getComparator(int numHits, Pruning pruning) {
		var field = getField();
		var missing = getMissingValue();
		var reverse = getReverse();

		return switch(getType()) {
			case INT -> new IntComparator(numHits, field, (Integer) missing, reverse, Pruning.NONE) {
				@Override
				public LeafFieldComparator getLeafComparator(LeafReaderContext context)
					throws IOException
				{
					return new IntLeafComparator(context) {
						@Override
						protected NumericDocValues getNumericDocValues(
							LeafReaderContext context,
							String field
						) throws IOException {
							return view(context);
						}
					};
				}
			};

			case LONG -> new LongComparator(numHits, field, (Long) missing, reverse, Pruning.NONE) {
				@Override
				public LeafFieldComparator getLeafComparator(LeafReaderContext context)
					throws IOException
				{
					return new LongLeafComparator(context) {
						@Override
						protected NumericDocValues getNumericDocValues(
							LeafReaderContext context,
							String field
						) throws IOException {
							return view(context);
						}
					};
				}
			};

			case FLOAT -> new FloatComparator(numHits, field, (Float) missing, reverse, Pruning.NONE) {
				@Override
				public LeafFieldComparator getLeafComparator(LeafReaderContext context)
					throws IOException
				{
					return new FloatLeafComparator(context) {
						@Override
						protected NumericDocValues getNumericDocValues(
							LeafReaderContext context,
							String field
						) throws IOException {
							return view(context);
						}
					};
				}
			};

			case DOUBLE -> new DoubleComparator(numHits, field, (Double) missing, reverse, Pruning.NONE) {
				@Override
				public LeafFieldComparator getLeafComparator(LeafReaderContext context)
					throws IOException
				{
					return new DoubleLeafComparator(context) {
						@Override
						protected NumericDocValues getNumericDocValues(
							LeafReaderContext context,
							String field
						) throws IOException {
							return view(context);
						}
					};
				}
			};

			default -> throw new IllegalStateException("Illegal sort type: " + getType());
		};
	}

	/**
	 * Get the chain in one segment as the doc values of its documents, one
	 * value per document.
	 */
	private NumericDocValues view(LeafReaderContext context) throws IOException {
		var values = chain.values(context);
		if(values == null) {
			return DocValues.emptyNumeric();
		}

		return new Picked(values, getType(), max, context.reader().maxDoc());
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) {
			return true;
		}

		if(!super.equals(obj) || getClass() != obj.getClass()) {
			return false;
		}

		var other = (ChainSortField) obj;
		return max == other.max && chain.equals(other.chain);
	}

	@Override
	public int hashCode() {
		return 31 * (31 * super.hashCode() + Boolean.hashCode(max)) + chain.hashCode();
	}

	@Override
	public String toString() {
		return super.toString() + " chain=" + chain.steps();
	}

	/**
	 * The chain as doc values: for each document of the index, the one value
	 * that stands for it.
	 *
	 * The values are compared the way the comparator compares them, which for
	 * a float or a double is not the order of the bits it is handed.
	 */
	private static final class Picked extends NumericDocValues {
		private final ValueChain.Values values;
		private final Type type;
		private final boolean max;
		private final int maxDoc;

		private int doc;
		private long value;

		Picked(ValueChain.Values values, Type type, boolean max, int maxDoc) {
			this.values = values;
			this.type = type;
			this.max = max;
			this.maxDoc = maxDoc;
			this.doc = -1;
		}

		@Override
		public boolean advanceExact(int target) throws IOException {
			doc = target;

			/*
			 * Only a document of the index is ordered. The values of object
			 * fields are Lucene documents too, and are never hits of a search
			 * ordered this way.
			 */
			if(!values.documents().get(target) || !values.advanceExact(target)) {
				return false;
			}

			var picked = values.value(0);
			for(var i = 1; i < values.count(); i++) {
				var candidate = values.value(i);
				var compared = compare(candidate, picked);
				if(max ? compared > 0 : compared < 0) {
					picked = candidate;
				}
			}

			value = picked;
			return true;
		}

		private int compare(long a, long b) {
			return switch(type) {
				case FLOAT -> Float.compare(Float.intBitsToFloat((int) a), Float.intBitsToFloat((int) b));
				case DOUBLE -> Double.compare(Double.longBitsToDouble(a), Double.longBitsToDouble(b));
				default -> Long.compare(a, b);
			};
		}

		@Override
		public long longValue() {
			return value;
		}

		@Override
		public int docID() {
			return doc;
		}

		@Override
		public int nextDoc() throws IOException {
			return advance(doc + 1);
		}

		@Override
		public int advance(int target) throws IOException {
			var documents = values.documents();
			var next = target;
			while(next < maxDoc) {
				next = documents.nextSetBit(next);
				if(next == DocIdSetIterator.NO_MORE_DOCS) {
					break;
				}

				if(advanceExact(next)) {
					return next;
				}

				next++;
			}

			doc = DocIdSetIterator.NO_MORE_DOCS;
			return doc;
		}

		@Override
		public long cost() {
			return maxDoc;
		}
	}
}
