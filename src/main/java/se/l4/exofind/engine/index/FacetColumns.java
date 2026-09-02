package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.FixedBitSet;

/**
 * The values of one facet field of one segment laid out flat, so counting a
 * match is an array read.
 *
 * Doc values are what a facet field was written as, and reading them back is
 * what counting a facet costs: they are packed, decoded a block at a time, and
 * read forward only, so every match pays a handful of dependent lookups before
 * its first value arrives. That is paid once per match per facet, and a page of
 * four facets over ten thousand matches pays it forty thousand times. A column
 * pays it once per segment instead - every document is decoded in one forward
 * pass - and answers a match with the array reads the doc values were hiding.
 *
 * A column holds segment ordinals or raw numbers, which are as fixed as the
 * segment itself, so {@link FacetStates} keeps one per segment core and hands
 * the same one to every search until the core goes away. Counting folds through
 * the reader's ordinal map exactly as before; the column only replaces where a
 * segment's ordinals are read from.
 *
 * The layout follows the segment. A field that holds at most one value per
 * document in the segment - which Lucene tells apart, whatever the definition
 * allowed - is one array indexed by document; anything else is the values of
 * every document laid end to end with one offset per document into them. Each
 * value costs four bytes as an ordinal and eight as a number, per segment that
 * is open, which is what a node spends on heap for its facets.
 */
final class FacetColumns {
	private FacetColumns() {
	}

	/**
	 * The ordinals of a field written as sorted set doc values.
	 */
	sealed interface Ords {
		/**
		 * The ordinal a value of the segment has, for a document holding at
		 * most one - {@link #NONE} where the document holds none.
		 *
		 * @param ord
		 *   the ordinal per document
		 */
		record Single(int[] ord) implements Ords {
			/**
			 * The ordinal of a document holding no value.
			 */
			static final int NONE = -1;
		}

		/**
		 * The ordinals of every document laid end to end, each document's
		 * values in ordinal order the way the doc values hand them out.
		 *
		 * @param starts
		 *   where each document's ordinals start in {@code ords}, one more
		 *   entry than the segment has documents so the last one has an end
		 * @param ords
		 *   the ordinals of every document, in document order
		 */
		record Multi(int[] starts, int[] ords) implements Ords {
		}
	}

	/**
	 * The numbers of a field written as sorted numeric doc values.
	 */
	sealed interface Longs {
		/**
		 * The number a document holds, for a segment where each holds at most
		 * one.
		 *
		 * @param value
		 *   the number per document, meaningless where {@code present} says
		 *   the document holds none
		 * @param present
		 *   which documents hold a number, or {@code null} where every one
		 *   does
		 */
		record Single(long[] value, FixedBitSet present) implements Longs {
			/**
			 * Get whether the document holds a number.
			 */
			boolean has(int doc) {
				return present == null || present.get(doc);
			}
		}

		/**
		 * The numbers of every document laid end to end, each document's
		 * values in ascending order the way the doc values hand them out, a
		 * number that a document holds twice standing twice.
		 *
		 * @param starts
		 *   where each document's numbers start in {@code values}, one more
		 *   entry than the segment has documents so the last one has an end
		 * @param values
		 *   the numbers of every document, in document order
		 */
		record Multi(int[] starts, long[] values) implements Longs {
		}
	}

	/**
	 * A column of ordinals read as one span per document, whichever layout it
	 * has: the ordinals of a document are {@code values[from(doc)..to(doc))}.
	 * What a leaf off the hottest path reads through, so it needs one loop
	 * rather than one per layout; the layout is decided once here, and the
	 * JIT folds the choice out of the loop.
	 */
	static final class OrdSpans {
		final int[] values;
		private final int[] starts;
		private final boolean single;

		OrdSpans(Ords column) {
			if(column instanceof Ords.Single one) {
				values = one.ord();
				starts = null;
				single = true;
			} else {
				var multi = (Ords.Multi) column;
				values = multi.ords();
				starts = multi.starts();
				single = false;
			}
		}

		int from(int doc) {
			return single ? doc : starts[doc];
		}

		int to(int doc) {
			return single
				? (values[doc] == Ords.Single.NONE ? doc : doc + 1)
				: starts[doc + 1];
		}
	}

	/**
	 * A column of numbers read as one span per document, whichever layout it
	 * has - see {@link OrdSpans}.
	 */
	static final class LongSpans {
		final long[] values;
		private final int[] starts;
		private final FixedBitSet present;
		private final boolean single;

		LongSpans(Longs column) {
			if(column instanceof Longs.Single one) {
				values = one.value();
				starts = null;
				present = one.present();
				single = true;
			} else {
				var multi = (Longs.Multi) column;
				values = multi.values();
				starts = multi.starts();
				present = null;
				single = false;
			}
		}

		int from(int doc) {
			return single ? doc : starts[doc];
		}

		int to(int doc) {
			return single
				? (present == null || present.get(doc) ? doc + 1 : doc)
				: starts[doc + 1];
		}
	}

	/**
	 * Lay out the ordinals of the given field in the given segment. The field
	 * has to hold values in the segment.
	 *
	 * @param reader
	 *   the segment
	 * @param field
	 *   the Lucene field the values were written under
	 * @return
	 * @throws IOException
	 * @throws IllegalStateException
	 *   if the segment holds more distinct values than an ordinal of four
	 *   bytes can name
	 */
	static Ords ords(LeafReader reader, String field) throws IOException {
		var values = reader.getSortedSetDocValues(field);
		if(values.getValueCount() > Integer.MAX_VALUE) {
			throw new IllegalStateException(
				"Field " + field + " holds more than " + Integer.MAX_VALUE
					+ " distinct values in one segment, which counting a facet over it"
					+ " can not address"
			);
		}

		var maxDoc = reader.maxDoc();
		var singleton = DocValues.unwrapSingleton(values);
		if(singleton != null) {
			var ord = new int[maxDoc];
			Arrays.fill(ord, Ords.Single.NONE);

			for(
				var doc = singleton.nextDoc();
				doc != DocIdSetIterator.NO_MORE_DOCS;
				doc = singleton.nextDoc()
			) {
				ord[doc] = singleton.ordValue();
			}

			return new Ords.Single(ord);
		}

		var starts = new int[maxDoc + 1];
		var ords = new int[Math.max(16, maxDoc)];
		var count = 0;
		var next = 0;

		for(
			var doc = values.nextDoc();
			doc != DocIdSetIterator.NO_MORE_DOCS;
			doc = values.nextDoc()
		) {
			// Documents without values start where the next one with values does
			Arrays.fill(starts, next, doc + 1, count);
			next = doc + 1;

			var valueCount = values.docValueCount();
			if(ords.length < count + valueCount) {
				ords = Arrays.copyOf(ords, Math.max(ords.length * 2, count + valueCount));
			}

			for(var i = 0; i < valueCount; i++) {
				ords[count++] = (int) values.nextOrd();
			}
		}

		Arrays.fill(starts, next, starts.length, count);
		return new Ords.Multi(starts, ords.length == count ? ords : Arrays.copyOf(ords, count));
	}

	/**
	 * Lay out the numbers of the given field in the given segment. The field
	 * has to hold values in the segment.
	 *
	 * @param reader
	 *   the segment
	 * @param field
	 *   the Lucene field the values were written under
	 * @return
	 * @throws IOException
	 */
	static Longs longs(LeafReader reader, String field) throws IOException {
		var values = reader.getSortedNumericDocValues(field);
		var maxDoc = reader.maxDoc();

		var singleton = DocValues.unwrapSingleton(values);
		if(singleton != null) {
			var value = new long[maxDoc];
			var present = new FixedBitSet(maxDoc);

			for(
				var doc = singleton.nextDoc();
				doc != DocIdSetIterator.NO_MORE_DOCS;
				doc = singleton.nextDoc()
			) {
				value[doc] = singleton.longValue();
				present.set(doc);
			}

			return new Longs.Single(value, present.cardinality() == maxDoc ? null : present);
		}

		var starts = new int[maxDoc + 1];
		var numbers = new long[Math.max(16, maxDoc)];
		var count = 0;
		var next = 0;

		for(
			var doc = values.nextDoc();
			doc != DocIdSetIterator.NO_MORE_DOCS;
			doc = values.nextDoc()
		) {
			Arrays.fill(starts, next, doc + 1, count);
			next = doc + 1;

			var valueCount = values.docValueCount();
			if(numbers.length < count + valueCount) {
				numbers = Arrays.copyOf(numbers, Math.max(numbers.length * 2, count + valueCount));
			}

			for(var i = 0; i < valueCount; i++) {
				numbers[count++] = values.nextValue();
			}
		}

		Arrays.fill(starts, next, starts.length, count);
		return new Longs.Multi(
			starts,
			numbers.length == count ? numbers : Arrays.copyOf(numbers, count)
		);
	}
}
