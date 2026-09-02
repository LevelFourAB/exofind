package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.LeafFieldComparator;
import org.apache.lucene.search.Pruning;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.comparators.DoubleComparator;
import org.apache.lucene.search.comparators.FloatComparator;
import org.apache.lucene.search.comparators.IntComparator;
import org.apache.lucene.search.comparators.LongComparator;
import org.apache.lucene.search.join.BitSetProducer;

/**
 * Ordering documents by a number that the index also filters on.
 *
 * <p>A number reaches Lucene twice: the {@code sort} usage writes doc values,
 * and the {@code filter} usage writes points. Doc values give a document's
 * value but have to be read for every document that matched. Points are a tree
 * over the same values, so a search that wants ten hits can rule out a whole
 * subtree without reading a document in it. Lucene's numeric comparators use
 * both, but take one field name for both, and the two usages are written under
 * different names here. This ordering carries the two names apart: values come
 * from the doc values name, and the points come from the filter name.
 *
 * <p>Use this only when the field has both usages. The two Lucene fields must
 * hold the same value for every document, which is only true when one value
 * was written to both. {@link #getField()} stays the doc values name, so
 * anything that reads the ordering back reads the field results are ordered
 * by.
 *
 * <h2>Which Lucene documents count as missing a value</h2>
 *
 * <p>Lucene only leaves documents out once it knows that a document without a
 * value cannot make the page, and it asks whether any document is without one
 * by comparing how many documents the points cover against how many documents
 * the segment holds. Every value of an object field is a Lucene document of
 * its own (see {@link NestedDocuments}), so on an index with object fields
 * that comparison always finds documents without points, and an order that
 * puts missing values at the competitive end never skips. Descending by a
 * field whose missing values sort last is exactly that order, and it is the
 * common way to read a catalogue.
 *
 * <p>Those documents are not documents of the index, and a search of the index
 * never collects one. So the comparator is handed a view of the segment where
 * the points of this field report covering every Lucene document, whenever
 * they already cover every document of the index. Nothing else about the view
 * differs, and the count is only corrected upwards to the whole segment when
 * the points leave no document of the index out.
 *
 * <p>{@link #withDocuments} takes what finds the documents of the index in a
 * segment. Give it only to an ordering over documents of the index. An
 * ordering over the values of an object field must be left without it: the
 * values are the hits there, and calling them covered would leave out a value
 * that holds no number.
 *
 * <h2>What this relies on Lucene for</h2>
 *
 * <p>Everything about the skipping itself stays Lucene's, including which
 * documents it walks out of the point tree and how often it bothers. Three
 * things are worth checking again on an upgrade.
 * {@code NumericComparator} builds its competitive iterator from
 * {@code LeafReader.getPointValues} under the field name passed to the
 * comparator, and reads values through the protected
 * {@code getNumericDocValues} of its leaf comparator, which is overridden
 * here. Its {@code PointsCompetitiveDISIBuilder} asks whether documents are
 * missing with {@code pointValues.getDocCount() != maxDoc}, which is the one
 * answer the view corrects. Points also have to be as wide as the values the
 * comparator compares - four bytes for {@code INT} and {@code FLOAT}, eight
 * for {@code LONG} and {@code DOUBLE} - and a segment whose points are shaped
 * differently is read through doc values instead of throwing.
 *
 * <p>Skipping stays Lucene's decision in every other way too. It happens only
 * for the first field of a sort, only after the collector has counted past its
 * total hits threshold, and only once the queue is full or the search
 * continues from a position. A segment written before the field gained its
 * {@code filter} usage has no points, and is read through doc values as
 * before.
 */
public final class NumberSortField extends SortField {
	private final String points;

	/**
	 * Finds the documents of the index in a segment, or {@code null} when
	 * every Lucene document of a segment is one.
	 */
	private final BitSetProducer documents;

	/**
	 * @param values
	 *   name of the Lucene field holding the doc values documents are ordered
	 *   by
	 * @param points
	 *   name of the Lucene field holding the same values as points
	 * @param type
	 *   how the values compare, one of {@code INT}, {@code LONG},
	 *   {@code FLOAT} or {@code DOUBLE}
	 * @param reverse
	 *   whether documents are ordered from the highest value down
	 * @throws IllegalArgumentException
	 *   if the type is not one a number is compared as
	 */
	public NumberSortField(String values, String points, Type type, boolean reverse) {
		this(values, points, type, reverse, null);
	}

	private NumberSortField(
		String values,
		String points,
		Type type,
		boolean reverse,
		BitSetProducer documents
	) {
		super(values, type, reverse);

		switch(type) {
			case INT, LONG, FLOAT, DOUBLE -> {}
			default -> throw new IllegalArgumentException(
				"Sorting by points needs a numeric type, got " + type
			);
		}

		this.points = Objects.requireNonNull(points);
		this.documents = documents;
	}

	/**
	 * Get the name of the Lucene field holding the points.
	 *
	 * @return
	 */
	public String getPointsField() {
		return points;
	}

	/**
	 * Get this ordering told which Lucene documents of a segment are documents
	 * of the index, so that the values of object fields are not counted as
	 * documents that hold no number.
	 *
	 * @param documents
	 *   finds the documents of the index in a segment
	 * @return
	 *   a new ordering, this one left as it was
	 */
	NumberSortField withDocuments(BitSetProducer documents) {
		return copy(getReverse(), Objects.requireNonNull(documents));
	}

	/**
	 * Get this ordering with its comparison flipped, for walking backwards from
	 * a position.
	 *
	 * @return
	 */
	NumberSortField mirrored() {
		return copy(!getReverse(), documents);
	}

	private NumberSortField copy(boolean reverse, BitSetProducer documents) {
		var copy = new NumberSortField(getField(), points, getType(), reverse, documents);

		if(getMissingValue() != null) {
			copy.setMissingValue(getMissingValue());
		}

		return copy;
	}

	@Override
	public FieldComparator<?> getComparator(int numHits, Pruning pruning) {
		var values = getField();
		var missing = getMissingValue();
		var reverse = getReverse();

		return switch(getType()) {
			case INT -> new IntComparator(numHits, points, (Integer) missing, reverse, pruning) {
				@Override
				public LeafFieldComparator getLeafComparator(LeafReaderContext context)
					throws IOException
				{
					return new IntLeafComparator(covered(context)) {
						@Override
						protected NumericDocValues getNumericDocValues(
							LeafReaderContext context,
							String field
						) throws IOException {
							return DocValues.getNumeric(context.reader(), values);
						}
					};
				}
			};

			case LONG -> new LongComparator(numHits, points, (Long) missing, reverse, pruning) {
				@Override
				public LeafFieldComparator getLeafComparator(LeafReaderContext context)
					throws IOException
				{
					return new LongLeafComparator(covered(context)) {
						@Override
						protected NumericDocValues getNumericDocValues(
							LeafReaderContext context,
							String field
						) throws IOException {
							return DocValues.getNumeric(context.reader(), values);
						}
					};
				}
			};

			case FLOAT -> new FloatComparator(numHits, points, (Float) missing, reverse, pruning) {
				@Override
				public LeafFieldComparator getLeafComparator(LeafReaderContext context)
					throws IOException
				{
					return new FloatLeafComparator(covered(context)) {
						@Override
						protected NumericDocValues getNumericDocValues(
							LeafReaderContext context,
							String field
						) throws IOException {
							return DocValues.getNumeric(context.reader(), values);
						}
					};
				}
			};

			case DOUBLE -> new DoubleComparator(
				numHits,
				points,
				(Double) missing,
				reverse,
				pruning
			) {
				@Override
				public LeafFieldComparator getLeafComparator(LeafReaderContext context)
					throws IOException
				{
					return new DoubleLeafComparator(covered(context)) {
						@Override
						protected NumericDocValues getNumericDocValues(
							LeafReaderContext context,
							String field
						) throws IOException {
							return DocValues.getNumeric(context.reader(), values);
						}
					};
				}
			};

			default -> throw new IllegalStateException("Illegal sort type: " + getType());
		};
	}

	/**
	 * Get the segment as the comparator should read it: as it is where nothing
	 * needs correcting, and otherwise a view of it where the points of this
	 * field answer for every Lucene document.
	 *
	 * A comparator reads nothing off a segment but its reader - the points,
	 * the doc values skipper, the field infos, the doc values and the size.
	 * The position of the segment among the others is the collector's, and it
	 * keeps that from the reader it was given, so a view carrying neither is
	 * enough here and would not be anywhere else.
	 *
	 * @param context
	 *   the segment as the collector reads it
	 * @return
	 * @throws IOException
	 */
	private LeafReaderContext covered(LeafReaderContext context) throws IOException {
		if(documents == null) {
			// Without object fields every Lucene document is a document already
			return context;
		}

		var reader = context.reader();

		var found = reader.getPointValues(points);
		if(found == null) {
			return context;
		}

		/*
		 * Lucene refuses points it cannot read as the width of its own type.
		 * Hiding them leaves the segment to be read through doc values, which
		 * answers the same order.
		 */
		var info = reader.getFieldInfos().fieldInfo(points);
		if(info.getPointDimensionCount() != 1 || info.getPointNumBytes() != width()) {
			return new CoveredSegment(reader, points, null).getContext();
		}

		var whole = reader.maxDoc();
		var index = FacetStates.documentCountOf(context, documents);
		if(index <= 0 || index == whole || found.getDocCount() != index) {
			/*
			 * Either the segment holds no values of object fields, so Lucene
			 * counts the right documents already, or some document of the
			 * index holds no value and the count has to stay as it is.
			 */
			return context;
		}

		return new CoveredSegment(reader, points, new CoveredPoints(found, whole)).getContext();
	}

	/**
	 * Get how many bytes a point of this type takes.
	 */
	private int width() {
		return switch(getType()) {
			case INT, FLOAT -> Integer.BYTES;
			default -> Long.BYTES;
		};
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) {
			return true;
		}

		if(!super.equals(obj) || getClass() != obj.getClass()) {
			return false;
		}

		var other = (NumberSortField) obj;
		return points.equals(other.points) && Objects.equals(documents, other.documents);
	}

	@Override
	public int hashCode() {
		return 31 * (31 * super.hashCode() + points.hashCode()) + Objects.hashCode(documents);
	}

	@Override
	public String toString() {
		return super.toString() + " points=" + points;
	}

	/**
	 * A segment as one comparator reads it, with the points of one field
	 * answered differently.
	 *
	 * Caches nothing and is cached against by nothing, so it lives no longer
	 * than the comparator that was handed it. Closing it would close the
	 * segment underneath, so it is never closed.
	 */
	private static final class CoveredSegment extends FilterLeafReader {
		private final String field;

		/**
		 * What the field answers with, or {@code null} to leave the field
		 * without points.
		 */
		private final PointValues points;

		CoveredSegment(LeafReader in, String field, PointValues points) {
			super(in);

			this.field = field;
			this.points = points;
		}

		@Override
		public PointValues getPointValues(String field) throws IOException {
			return this.field.equals(field) ? points : super.getPointValues(field);
		}

		@Override
		public CacheHelper getCoreCacheHelper() {
			return null;
		}

		@Override
		public CacheHelper getReaderCacheHelper() {
			return null;
		}
	}

	/**
	 * The points of a field, reporting that they cover the whole segment.
	 *
	 * Only the count of documents differs. What the points hold is read
	 * straight from the segment, so everything walking them finds what is
	 * really there.
	 */
	private static final class CoveredPoints extends PointValues {
		private final PointValues in;
		private final int docCount;

		CoveredPoints(PointValues in, int docCount) {
			this.in = in;
			this.docCount = docCount;
		}

		@Override
		public int getDocCount() {
			return docCount;
		}

		@Override
		public PointTree getPointTree() throws IOException {
			return in.getPointTree();
		}

		@Override
		public byte[] getMinPackedValue() throws IOException {
			return in.getMinPackedValue();
		}

		@Override
		public byte[] getMaxPackedValue() throws IOException {
			return in.getMaxPackedValue();
		}

		@Override
		public int getNumDimensions() throws IOException {
			return in.getNumDimensions();
		}

		@Override
		public int getNumIndexDimensions() throws IOException {
			return in.getNumIndexDimensions();
		}

		@Override
		public int getBytesPerDimension() throws IOException {
			return in.getBytesPerDimension();
		}

		@Override
		public long size() {
			return in.size();
		}
	}
}
