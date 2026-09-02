package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.IntroSorter;

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
 *
 * A column answers a match at a time, so what a wide search pays still grows
 * with its matches. The same values turned the other way round - per value,
 * which documents hold it - answer a value at a time instead, and a search
 * that matched most of a segment is then counted without visiting a single
 * match: a value held by many documents is a bitmap intersected with the
 * matches, and one held by few is a list of documents looked up in them. The
 * cost of counting becomes the size of the field rather than of the search.
 * {@link OrdPostings} and {@link LongPostings} are that inversion, built from
 * the column on the first wide search and kept beside it; they cost about as
 * much again as the column does.
 *
 * A field inside an object holds its values on documents of their own, and a
 * facet over it counts the documents above them: a product with three red
 * variants is one red product. Where a search matches those values whole -
 * every value of a document or none, which is what a search asking nothing of
 * the values themselves does - the inversion can be built in terms of the
 * documents above the values, holding each document once per value however
 * many of its values hold it, and a rolled-up count is then the same
 * intersection or lookups against the documents that matched.
 * {@link #rolledUpOrdPostings} and {@link #rolledUpLongPostings} are that
 * inversion; a search that matched only some of a document's values cannot
 * use them, since which documents count then depends on which values matched.
 */
final class FacetColumns {
	/**
	 * The share of a segment a scope has to cover before its postings are
	 * built at all: one document in this many. Below it a walk of the matches
	 * is cheaper than any inversion would be, so building one would only
	 * spend memory.
	 */
	private static final int WIDE_ONE_IN = 5;

	/**
	 * What walking one match costs, in the words {@link OrdPostings#cost} is
	 * counted in. Walking reads the column, folds a count and moves the
	 * iterator per match, where a word is a load, an and and a popcount;
	 * measured on the catalogue corpus as about nine nanoseconds against
	 * under one.
	 */
	private static final int WALKED_MATCH_COST = 12;

	private FacetColumns() {
	}

	/**
	 * Get whether a scope covers enough of a field for its postings to be
	 * worth having.
	 *
	 * @param matches
	 *   how many matches the scope holds in the segment
	 * @param docCount
	 *   how many documents of the segment hold the field
	 * @return
	 */
	static boolean isWide(int matches, int docCount) {
		return matches >= docCount / WIDE_ONE_IN;
	}

	/**
	 * Get whether counting the given number of matches through postings of
	 * the given cost beats walking them.
	 *
	 * @param cost
	 *   what the postings cost to count in full
	 * @param matches
	 *   how many matches the scope holds in the segment
	 * @return
	 */
	static boolean cheaperThanWalking(long cost, int matches) {
		return cost <= (long) matches * WALKED_MATCH_COST;
	}

	/**
	 * The ordinals of a field written as sorted set doc values.
	 */
	sealed interface Ords {
		/**
		 * Get how many documents of the segment hold a value. What a scope is
		 * measured against to tell how much of the field it covers: a segment
		 * holds the values of object fields as documents of their own, so its
		 * document count says little about a field only the documents above
		 * them hold.
		 */
		int docCount();

		/**
		 * The ordinal a value of the segment has, for a document holding at
		 * most one - {@link #NONE} where the document holds none.
		 *
		 * @param ord
		 *   the ordinal per document
		 * @param docCount
		 *   how many documents hold a value
		 */
		record Single(int[] ord, int docCount) implements Ords {
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
		 * @param docCount
		 *   how many documents hold a value
		 */
		record Multi(int[] starts, int[] ords, int docCount) implements Ords {
		}
	}

	/**
	 * The numbers of a field written as sorted numeric doc values.
	 */
	sealed interface Longs {
		/**
		 * Get how many documents of the segment hold a number - see
		 * {@link Ords#docCount()}.
		 */
		int docCount();

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
		 * @param docCount
		 *   how many documents hold a number
		 */
		record Single(long[] value, FixedBitSet present, int docCount) implements Longs {
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
		 * @param docCount
		 *   how many documents hold a number
		 */
		record Multi(int[] starts, long[] values, int docCount) implements Longs {
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
	 * The ordinals of a segment inverted: per ordinal, the documents holding
	 * it, as a bitmap where they are many and as a list where they are few.
	 *
	 * An ordinal held by more documents than a bitmap over the segment has
	 * words - {@link #DENSE_ABOVE_WORDS} times over - gets that bitmap, whose
	 * intersection with the matches is counted a word at a time. Any other
	 * holds its documents as a sorted list and counts by looking each one up
	 * in the matches. A bitmap is as long as the segment, whatever share of
	 * it holds the field, so this is judged in words rather than in
	 * documents: a segment holding the values of object fields as documents
	 * of their own is several times longer than the documents a facet counts.
	 * The split keeps the memory near that of the column - a bitmap costs the
	 * segment's documents in bits, a list four bytes per posting - and the
	 * work per ordinal at the cheaper of the two.
	 *
	 * @param dense
	 *   the bitmap per ordinal, {@code null} for an ordinal held as a list
	 * @param starts
	 *   where each ordinal's list starts in {@code docs}, one more entry than
	 *   there are ordinals; empty for an ordinal held as a bitmap
	 * @param docs
	 *   the lists of every ordinal laid end to end, each in document order
	 * @param cost
	 *   what counting every ordinal against a scope costs, in words read -
	 *   a document looked up counting as {@link #LOOKUP_COST} of them - what
	 *   a leaf weighs against walking the matches
	 */
	record OrdPostings(FixedBitSet[] dense, int[] starts, int[] docs, long cost) {
		/**
		 * How many times the words of a bitmap an ordinal's list has to be
		 * before the bitmap is the cheaper of the two, both in memory and in
		 * work: a list of that length costs the same bytes as the bitmap, and
		 * looking its documents up costs about the same as reading the words.
		 */
		static final int DENSE_ABOVE_WORDS = 2;

		/**
		 * What looking one document up in the matches costs next to reading
		 * one word of a bitmap: a random read against a sequential one.
		 */
		static final int LOOKUP_COST = 2;

		/**
		 * Get how many ordinals the segment holds.
		 */
		int valueCount() {
			return dense.length;
		}

		/**
		 * Count the documents holding the ordinal among the matches.
		 *
		 * @param ord
		 * @param matches
		 *   the matches of the segment, as long as the segment
		 * @return
		 */
		int count(int ord, FixedBitSet matches) {
			var bits = dense[ord];
			if(bits != null) {
				return (int) FixedBitSet.intersectionCount(matches, bits);
			}

			var count = 0;
			for(int i = starts[ord], end = starts[ord + 1]; i < end; i++) {
				if(matches.get(docs[i])) {
					count++;
				}
			}

			return count;
		}
	}

	/**
	 * The numbers of a segment sorted, each with the document holding it, so
	 * that the documents holding a number - or any number in a range - are one
	 * run of the arrays. A document holding a number twice stands twice, and
	 * a document holding two numbers in one range stands in it twice, which is
	 * what a caller counting documents has to fold.
	 *
	 * @param values
	 *   every number of the segment, ascending
	 * @param docs
	 *   the document holding each number, ascending within equal numbers
	 * @param single
	 *   whether every document holds at most one number, in which case no
	 *   document stands twice anywhere
	 */
	record LongPostings(long[] values, int[] docs, boolean single) {
		/**
		 * Get where the numbers at or above the given one start.
		 */
		int from(long value) {
			var low = 0;
			var high = values.length;
			while(low < high) {
				var mid = (low + high) >>> 1;
				if(values[mid] < value) {
					low = mid + 1;
				} else {
					high = mid;
				}
			}

			return low;
		}

		/**
		 * Get where the numbers above the given one start - one past the last
		 * at or below it.
		 */
		int to(long value) {
			var low = 0;
			var high = values.length;
			while(low < high) {
				var mid = (low + high) >>> 1;
				if(values[mid] <= value) {
					low = mid + 1;
				} else {
					high = mid;
				}
			}

			return low;
		}
	}

	/**
	 * Invert the given column. The ordinals run from zero to one below the
	 * value count.
	 *
	 * @param column
	 *   the ordinals of the segment
	 * @param valueCount
	 *   how many distinct values the segment holds
	 * @param maxDoc
	 *   how many documents the segment holds
	 * @return
	 */
	static OrdPostings ordPostings(Ords column, int valueCount, int maxDoc) {
		return invert(new OrdSpans(column), valueCount, maxDoc, null);
	}

	/**
	 * Invert the given column of a field inside an object in terms of the
	 * documents above its values: per ordinal, the documents of the index
	 * with at least one value holding it, each once. The ordinals run from
	 * zero to one below the value count. A value with no document after it
	 * belongs to nobody and is left out.
	 *
	 * @param column
	 *   the ordinals of the segment, held by the values
	 * @param valueCount
	 *   how many distinct values the segment holds
	 * @param maxDoc
	 *   how many documents the segment holds
	 * @param documents
	 *   the documents of the index in the segment; the document a value
	 *   belongs to is the next one at or after it
	 * @return
	 */
	static OrdPostings rolledUpOrdPostings(
		Ords column,
		int valueCount,
		int maxDoc,
		BitSet documents
	) {
		return invert(new OrdSpans(column), valueCount, maxDoc, documents);
	}

	/**
	 * Invert ordinals into the postings of what holds them: the Lucene
	 * documents themselves, or - given the documents of the index - the
	 * document above each one, held once per ordinal however many of its
	 * values hold it.
	 */
	private static OrdPostings invert(
		OrdSpans spans,
		int valueCount,
		int maxDoc,
		BitSet documents
	) {
		/*
		 * The document last counted for each ordinal, for holding a document
		 * once per ordinal: documents arrive in order, so the one counted
		 * last is the only one a repeat can belong to. A Lucene document
		 * holds each ordinal once already, so counting it as its own document
		 * needs none of this.
		 */
		var countedFor = documents == null ? null : new int[valueCount];
		var frequency = new int[valueCount];
		var document = -1;

		if(countedFor != null) {
			Arrays.fill(countedFor, -1);
		}

		for(var doc = 0; doc < maxDoc; doc++) {
			var from = spans.from(doc);
			var end = spans.to(doc);
			if(from == end) {
				continue;
			}

			var posting = doc;
			if(documents != null) {
				if(doc > document) {
					document = documentAbove(doc, documents);
					if(document == DocIdSetIterator.NO_MORE_DOCS) {
						break;
					}
				}

				posting = document;
			}

			for(var i = from; i < end; i++) {
				var ord = spans.values[i];
				if(countedFor == null) {
					frequency[ord]++;
				} else if(countedFor[ord] != posting) {
					countedFor[ord] = posting;
					frequency[ord]++;
				}
			}
		}

		var words = FixedBitSet.bits2words(maxDoc);
		var denseAbove = (long) words * OrdPostings.DENSE_ABOVE_WORDS;
		var dense = new FixedBitSet[valueCount];
		var starts = new int[valueCount + 1];
		var cost = (long) valueCount;
		for(var ord = 0; ord < valueCount; ord++) {
			if(frequency[ord] > denseAbove) {
				dense[ord] = new FixedBitSet(maxDoc);
				starts[ord + 1] = starts[ord];
				cost += words;
			} else {
				starts[ord + 1] = starts[ord] + frequency[ord];
				cost += (long) frequency[ord] * OrdPostings.LOOKUP_COST;
			}
		}

		if(countedFor != null) {
			Arrays.fill(countedFor, -1);
		}

		var docs = new int[starts[valueCount]];
		var cursor = Arrays.copyOf(starts, valueCount);
		document = -1;
		for(var doc = 0; doc < maxDoc; doc++) {
			var from = spans.from(doc);
			var end = spans.to(doc);
			if(from == end) {
				continue;
			}

			var posting = doc;
			if(documents != null) {
				if(doc > document) {
					document = documentAbove(doc, documents);
					if(document == DocIdSetIterator.NO_MORE_DOCS) {
						break;
					}
				}

				posting = document;
			}

			for(var i = from; i < end; i++) {
				var ord = spans.values[i];
				if(countedFor != null) {
					if(countedFor[ord] == posting) {
						continue;
					}

					countedFor[ord] = posting;
				}

				if(dense[ord] != null) {
					dense[ord].set(posting);
				} else {
					docs[cursor[ord]++] = posting;
				}
			}
		}

		return new OrdPostings(dense, starts, docs, cost);
	}

	/**
	 * Get the document of the index a value belongs to: the next document at
	 * or after it, or {@link DocIdSetIterator#NO_MORE_DOCS} where there is
	 * none.
	 */
	private static int documentAbove(int doc, BitSet documents) {
		return doc < documents.length()
			? documents.nextSetBit(doc)
			: DocIdSetIterator.NO_MORE_DOCS;
	}

	/**
	 * Sort the given column by number.
	 *
	 * @param column
	 *   the numbers of the segment
	 * @param maxDoc
	 *   how many documents the segment holds
	 * @return
	 */
	static LongPostings longPostings(Longs column, int maxDoc) {
		var spans = new LongSpans(column);

		var total = 0;
		for(var doc = 0; doc < maxDoc; doc++) {
			total += spans.to(doc) - spans.from(doc);
		}

		var values = new long[total];
		var docs = new int[total];
		var at = 0;
		for(var doc = 0; doc < maxDoc; doc++) {
			for(int i = spans.from(doc), end = spans.to(doc); i < end; i++) {
				values[at] = spans.values[i];
				docs[at] = doc;
				at++;
			}
		}

		sort(values, docs, total);
		return new LongPostings(values, docs, column instanceof Longs.Single);
	}

	/**
	 * Sort the given column of a field inside an object by number, each
	 * number standing beside the document of the index above the value
	 * holding it - once per document, however many of its values hold the
	 * number. A value with no document after it belongs to nobody and is left
	 * out.
	 *
	 * @param column
	 *   the numbers of the segment, held by the values
	 * @param maxDoc
	 *   how many documents the segment holds
	 * @param documents
	 *   the documents of the index in the segment; the document a value
	 *   belongs to is the next one at or after it
	 * @return
	 */
	static LongPostings rolledUpLongPostings(Longs column, int maxDoc, BitSet documents) {
		var spans = new LongSpans(column);

		var total = 0;
		for(var doc = 0; doc < maxDoc; doc++) {
			total += spans.to(doc) - spans.from(doc);
		}

		var values = new long[total];
		var docs = new int[total];
		var at = 0;
		var document = -1;
		for(var doc = 0; doc < maxDoc; doc++) {
			var from = spans.from(doc);
			var end = spans.to(doc);
			if(from == end) {
				continue;
			}

			if(doc > document) {
				document = documentAbove(doc, documents);
				if(document == DocIdSetIterator.NO_MORE_DOCS) {
					break;
				}
			}

			for(var i = from; i < end; i++) {
				values[at] = spans.values[i];
				docs[at] = document;
				at++;
			}
		}

		sort(values, docs, at);

		/*
		 * A document holding a number in two of its values stands twice, side
		 * by side once sorted, and is kept once. Whether any document is then
		 * still left standing under two numbers is what a caller counting
		 * documents into buckets has to know.
		 */
		var kept = 0;
		var seen = new FixedBitSet(maxDoc);
		var single = true;
		for(var i = 0; i < at; i++) {
			if(i > 0 && values[i] == values[kept - 1] && docs[i] == docs[kept - 1]) {
				continue;
			}

			if(seen.getAndSet(docs[i])) {
				single = false;
			}

			values[kept] = values[i];
			docs[kept] = docs[i];
			kept++;
		}

		return new LongPostings(
			Arrays.copyOf(values, kept),
			Arrays.copyOf(docs, kept),
			single
		);
	}

	/**
	 * Sort the first {@code total} numbers ascending, the documents beside
	 * them following and ascending within equal numbers.
	 */
	private static void sort(long[] values, int[] docs, int total) {
		new IntroSorter() {
			private long pivotValue;
			private int pivotDoc;

			@Override
			protected int compare(int i, int j) {
				var byValue = Long.compare(values[i], values[j]);
				return byValue != 0 ? byValue : Integer.compare(docs[i], docs[j]);
			}

			@Override
			protected void swap(int i, int j) {
				var value = values[i];
				values[i] = values[j];
				values[j] = value;

				var doc = docs[i];
				docs[i] = docs[j];
				docs[j] = doc;
			}

			@Override
			protected void setPivot(int i) {
				pivotValue = values[i];
				pivotDoc = docs[i];
			}

			@Override
			protected int comparePivot(int j) {
				var byValue = Long.compare(pivotValue, values[j]);
				return byValue != 0 ? byValue : Integer.compare(pivotDoc, docs[j]);
			}
		}.sort(0, total);
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

			var docCount = 0;
			for(
				var doc = singleton.nextDoc();
				doc != DocIdSetIterator.NO_MORE_DOCS;
				doc = singleton.nextDoc()
			) {
				ord[doc] = singleton.ordValue();
				docCount++;
			}

			return new Ords.Single(ord, docCount);
		}

		var starts = new int[maxDoc + 1];
		var ords = new int[Math.max(16, maxDoc)];
		var count = 0;
		var next = 0;
		var docCount = 0;

		for(
			var doc = values.nextDoc();
			doc != DocIdSetIterator.NO_MORE_DOCS;
			doc = values.nextDoc()
		) {
			// Documents without values start where the next one with values does
			Arrays.fill(starts, next, doc + 1, count);
			next = doc + 1;
			docCount++;

			var valueCount = values.docValueCount();
			if(ords.length < count + valueCount) {
				ords = Arrays.copyOf(ords, Math.max(ords.length * 2, count + valueCount));
			}

			for(var i = 0; i < valueCount; i++) {
				ords[count++] = (int) values.nextOrd();
			}
		}

		Arrays.fill(starts, next, starts.length, count);
		return new Ords.Multi(
			starts,
			ords.length == count ? ords : Arrays.copyOf(ords, count),
			docCount
		);
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

			var docCount = 0;
			for(
				var doc = singleton.nextDoc();
				doc != DocIdSetIterator.NO_MORE_DOCS;
				doc = singleton.nextDoc()
			) {
				value[doc] = singleton.longValue();
				present.set(doc);
				docCount++;
			}

			return new Longs.Single(value, docCount == maxDoc ? null : present, docCount);
		}

		var starts = new int[maxDoc + 1];
		var numbers = new long[Math.max(16, maxDoc)];
		var count = 0;
		var next = 0;
		var docCount = 0;

		for(
			var doc = values.nextDoc();
			doc != DocIdSetIterator.NO_MORE_DOCS;
			doc = values.nextDoc()
		) {
			Arrays.fill(starts, next, doc + 1, count);
			next = doc + 1;
			docCount++;

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
			numbers.length == count ? numbers : Arrays.copyOf(numbers, count),
			docCount
		);
	}
}
