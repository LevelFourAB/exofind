package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Function;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.util.LongValues;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.LongIntMaps;
import org.eclipse.collections.api.factory.primitive.LongLongMaps;
import org.eclipse.collections.api.map.primitive.MutableLongIntMap;
import org.eclipse.collections.api.map.primitive.MutableLongLongMap;

import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.SearchResult;

/**
 * Counting a facet per value of a field written as sorted set doc values.
 *
 * The mode of the scope decides what one count means. A match counted off
 * itself - a document, or a value hit - counts each of its values once, and a
 * Lucene document holds each value of a sorted set once already. Matches that
 * are values rolled up into their documents answer documents all the same,
 * because that is what every other facet counts and what ticking a value would
 * leave: a product with three red variants is one red product. A value is then
 * counted the first time one of a document's values holds it and never again.
 * Matches counted into the document above each one answer values - a brand
 * facet over value hits answers how many matching variants each brand has -
 * and the document's values are read once and reused for the rest of its
 * matches, which also keeps the forward-only doc values moving forward.
 *
 * Counted by ordinal from start to finish: a segment counts into its own
 * ordinal space, folds into the whole through the reader's ordinal map - a
 * packed lookup {@link FacetStates} keeps for as long as the reader is open -
 * and only the values the facet answers with are ever read back as terms.
 * Reading a term walks the dictionary and makes a string, so paying it per
 * answered value rather than per counted one is most of what counting a
 * high-cardinality field costs. The containers follow the sizes involved, the
 * way Lucene's own counters choose: arrays while a scan of the cardinality is
 * cheaper than hashing the matches, maps when the matches are few and the
 * values are many.
 */
final class StringFacetCount implements FacetCount {
	/**
	 * Cardinality below which the whole-reader counts are always an array -
	 * cheaper than any map, and too small for its scan to matter.
	 */
	private static final int DENSE_ALWAYS = 1024;

	private final String field;
	private final FacetMatches.Mode mode;
	private final int limit;
	private final Facet.Order order;
	private final Function<String, Object> decode;

	private int totalMatches;

	/*
	 * Resolved when the first segment holding values arrives - the walk is
	 * the first place a leaf of the reader is seen.
	 */
	private IndexReader reader;
	private FacetStates.StringOrds ords;

	/*
	 * Counts per global ordinal, one of the two non-null: an array when most
	 * ordinals are expected to be hit, a map when the matches are few
	 * compared to the index.
	 */
	private int[] dense;
	private MutableLongLongMap sparse;

	StringFacetCount(
		String field,
		FacetMatches.Mode mode,
		int limit,
		Facet.Order order,
		Function<String, Object> decode
	) {
		this.field = field;
		this.mode = mode;
		this.limit = limit;
		this.order = order;
		this.decode = decode;
	}

	@Override
	public void begin(int totalMatches) {
		this.totalMatches = totalMatches;
	}

	@Override
	public Leaf leaf(LeafReaderContext context, int matches) throws IOException {
		var values = context.reader().getSortedSetDocValues(field);
		if(values == null) {
			return null;
		}

		if(reader == null) {
			reader = ReaderUtil.getTopLevelContext(context).reader();
			ords = FacetStates.stringOrdsOf(reader, field);

			if(ords.cardinality() < DENSE_ALWAYS) {
				dense = new int[(int) ords.cardinality()];
			} else if(ords.cardinality() > Integer.MAX_VALUE
				|| totalMatches < reader.maxDoc() / 10) {
				sparse = LongLongMaps.mutable.empty();
			} else {
				dense = new int[(int) ords.cardinality()];
			}
		}

		var globals = ords.map() == null ? null : ords.map().getGlobalOrds(context.ord);

		/*
		 * Few matches next to the segment's values: fold each one into the
		 * whole as it is counted. Many: count into an array the segment's own
		 * ordinals index and fold once at the end, so the packed ordinal map
		 * is read per distinct value rather than per match.
		 */
		var counts = matches < values.getValueCount() / 10
			|| values.getValueCount() > Integer.MAX_VALUE
			? new DirectSegCounts(globals)
			: new ArraySegCounts(globals, (int) values.getValueCount());

		return switch(mode) {
			case DOCUMENTS, VALUES -> new EachMatch(values, counts);
			case ROLLED_UP -> new RolledUp(values, counts);
			case PARENTS_BY_VALUE -> new ByDocument(values, counts);
		};
	}

	@Override
	public SearchResult.Facet result() throws IOException {
		if(reader == null) {
			return new SearchResult.Facet(Lists.immutable.empty(), 0);
		}

		return order == Facet.Order.VALUE ? byValue() : byCount();
	}

	/**
	 * Add to the count of one value of the whole reader.
	 */
	private void addGlobal(long ord, int count) {
		if(dense != null) {
			dense[(int) ord] += count;
		} else {
			sparse.addToValue(ord, count);
		}
	}

	/**
	 * Answer the first values in value order, which for these ordinals is
	 * ascending by term.
	 */
	private SearchResult.Facet byValue() throws IOException {
		var values = Lists.mutable.<SearchResult.Facet.Value>empty();
		var distinct = 0;

		if(dense != null) {
			for(var ord = 0; ord < dense.length; ord++) {
				if(dense[ord] != 0) {
					distinct++;
					if(values.size() < limit) {
						values.add(valueOf(ord, dense[ord]));
					}
				}
			}
		} else {
			var sorted = sparse.keySet().toSortedArray();
			distinct = sorted.length;
			for(var i = 0; i < sorted.length && i < limit; i++) {
				values.add(valueOf(sorted[i], sparse.get(sorted[i])));
			}
		}

		return new SearchResult.Facet(values.toImmutable(), distinct);
	}

	/**
	 * Answer the most counted values, ties broken by term order - only they
	 * are ever read back as terms.
	 */
	private SearchResult.Facet byCount() throws IOException {
		var top = new TopValues(Math.max(limit, 0));
		var distinct = 0;

		if(dense != null) {
			for(var ord = 0; ord < dense.length; ord++) {
				if(dense[ord] != 0) {
					distinct++;
					top.offer(ord, dense[ord]);
				}
			}
		} else {
			distinct = sparse.size();
			sparse.forEachKeyValue(top::offer);
		}

		var values = new SearchResult.Facet.Value[top.size()];
		for(var i = values.length - 1; i >= 0; i--) {
			// The heap surrenders the worst kept value first
			values[i] = valueOf(top.peekOrd(), top.peekCount());
			top.pop();
		}

		return new SearchResult.Facet(Lists.immutable.of(values), distinct);
	}

	/**
	 * Read one answered value back as a term and decode it.
	 */
	private SearchResult.Facet.Value valueOf(long ord, long count) throws IOException {
		int segment;
		long segmentOrd;
		if(ords.map() == null) {
			segment = 0;
			segmentOrd = ord;
		} else {
			segment = ords.map().getFirstSegmentNumber(ord);
			segmentOrd = ords.map().getFirstSegmentOrd(ord);
		}

		var values = reader.leaves().get(segment).reader().getSortedSetDocValues(field);
		return new SearchResult.Facet.Value(
			decode.apply(values.lookupOrd(segmentOrd).utf8ToString()),
			count
		);
	}

	/**
	 * Counts of one segment on their way into the whole.
	 */
	private sealed interface SegCounts {
		/**
		 * Count the ordinal once.
		 */
		void add(long ord);

		/**
		 * Count the ordinal, at most once per document - what rolling up
		 * means. Documents arrive in order, so the one counted last is the
		 * only one a repeat can belong to.
		 */
		void addOncePer(long ord, int document);

		/**
		 * The segment is done - fold anything still held into the whole.
		 */
		void finish();
	}

	/**
	 * Counts folded into the whole as they arrive, for a segment with few
	 * matches next to its values.
	 */
	private final class DirectSegCounts implements SegCounts {
		private final LongValues globals;
		private MutableLongIntMap countedFor;

		DirectSegCounts(LongValues globals) {
			this.globals = globals;
		}

		@Override
		public void add(long ord) {
			addGlobal(globals == null ? ord : globals.get(ord), 1);
		}

		@Override
		public void addOncePer(long ord, int document) {
			if(countedFor == null) {
				countedFor = LongIntMaps.mutable.empty();
			}

			if(countedFor.getIfAbsent(ord, -1) != document) {
				countedFor.put(ord, document);
				add(ord);
			}
		}

		@Override
		public void finish() {
		}
	}

	/**
	 * Counts held per segment ordinal and folded once at the end, for a
	 * segment where the matches would read the ordinal map more often than a
	 * scan of its values does.
	 */
	private final class ArraySegCounts implements SegCounts {
		private final LongValues globals;
		private final int[] counts;
		private int[] countedFor;

		ArraySegCounts(LongValues globals, int valueCount) {
			this.globals = globals;
			this.counts = new int[valueCount];
		}

		@Override
		public void add(long ord) {
			counts[(int) ord]++;
		}

		@Override
		public void addOncePer(long ord, int document) {
			if(countedFor == null) {
				countedFor = new int[counts.length];
				Arrays.fill(countedFor, -1);
			}

			if(countedFor[(int) ord] != document) {
				countedFor[(int) ord] = document;
				counts[(int) ord]++;
			}
		}

		@Override
		public void finish() {
			for(var ord = 0; ord < counts.length; ord++) {
				if(counts[ord] != 0) {
					addGlobal(globals == null ? ord : globals.get(ord), counts[ord]);
				}
			}
		}
	}

	/**
	 * Each match counts its own values, one count per value it holds.
	 */
	private static final class EachMatch implements Leaf {
		private final SortedSetDocValues values;
		private final SegCounts counts;

		/*
		 * A field holding one value per document reads through the sorted
		 * doc values directly - the set wrapper answers the same thing
		 * through two more calls per document.
		 */
		private final SortedDocValues singleton;

		EachMatch(SortedSetDocValues values, SegCounts counts) {
			this.values = values;
			this.counts = counts;
			this.singleton = DocValues.unwrapSingleton(values);
		}

		@Override
		public void count(int doc) throws IOException {
			if(singleton != null) {
				if(singleton.advanceExact(doc)) {
					counts.add(singleton.ordValue());
				}
			} else if(values.advanceExact(doc)) {
				for(var i = 0; i < values.docValueCount(); i++) {
					counts.add(values.nextOrd());
				}
			}
		}

		@Override
		public void finish() {
			counts.finish();
		}
	}

	/**
	 * The matches are values of an object field and the counts are of the
	 * documents holding them: a value counts once per document however many of
	 * its values hold it.
	 */
	private static final class RolledUp implements Leaf {
		private final SortedSetDocValues values;
		private final SegCounts counts;
		private int document = -1;

		RolledUp(SortedSetDocValues values, SegCounts counts) {
			this.values = values;
			this.counts = counts;
		}

		@Override
		public void beginDocument(int document) {
			this.document = document;
		}

		@Override
		public void count(int doc) throws IOException {
			if(!values.advanceExact(doc)) {
				return;
			}

			for(var i = 0; i < values.docValueCount(); i++) {
				/*
				 * Ordinals are per segment and a document's values never
				 * cross one, so the same term is the same ordinal for as
				 * long as the counts are held.
				 */
				counts.addOncePer(values.nextOrd(), document);
			}
		}

		@Override
		public void finish() {
			counts.finish();
		}
	}

	/**
	 * The matches are values of an object field but the field counted is one
	 * of the index: each match counts what its document says there.
	 */
	private static final class ByDocument implements Leaf {
		private final SortedSetDocValues values;
		private final SegCounts counts;

		/*
		 * A field holding one value per document reads through the sorted
		 * doc values directly - the set wrapper answers the same thing
		 * through two more calls per document.
		 */
		private final SortedDocValues singleton;

		private long[] documentOrds = new long[4];
		private int documentOrdCount;

		ByDocument(SortedSetDocValues values, SegCounts counts) {
			this.values = values;
			this.counts = counts;
			this.singleton = DocValues.unwrapSingleton(values);
		}

		@Override
		public void beginDocument(int document) throws IOException {
			documentOrdCount = 0;

			if(singleton != null) {
				if(singleton.advanceExact(document)) {
					documentOrds[0] = singleton.ordValue();
					documentOrdCount = 1;
				}
			} else if(values.advanceExact(document)) {
				var count = values.docValueCount();
				if(documentOrds.length < count) {
					documentOrds = new long[count];
				}

				for(var i = 0; i < count; i++) {
					documentOrds[i] = values.nextOrd();
				}

				documentOrdCount = count;
			}
		}

		@Override
		public void count(int doc) {
			for(var i = 0; i < documentOrdCount; i++) {
				counts.add(documentOrds[i]);
			}
		}

		@Override
		public void finish() {
			counts.finish();
		}
	}

	/**
	 * The best values seen so far, at most a fixed number of them: the most
	 * counted, ties going to the earlier ordinal, which is term order.
	 *
	 * A binary heap whose root is the worst value kept, so a value that
	 * cannot compete is turned away by one comparison. Reading the answer
	 * pops the root, worst first.
	 */
	private static final class TopValues {
		private final long[] ords;
		private final long[] counts;
		private int size;

		TopValues(int capacity) {
			this.ords = new long[capacity];
			this.counts = new long[capacity];
		}

		int size() {
			return size;
		}

		long peekOrd() {
			return ords[0];
		}

		long peekCount() {
			return counts[0];
		}

		void offer(long ord, long count) {
			if(size < ords.length) {
				ords[size] = ord;
				counts[size] = count;
				size++;
				siftUp(size - 1);
			} else if(ords.length > 0 && better(ord, count, ords[0], counts[0])) {
				ords[0] = ord;
				counts[0] = count;
				siftDown(0);
			}
		}

		void pop() {
			size--;
			ords[0] = ords[size];
			counts[0] = counts[size];
			siftDown(0);
		}

		private static boolean better(long ordA, long countA, long ordB, long countB) {
			return countA > countB || (countA == countB && ordA < ordB);
		}

		private boolean betterAt(int a, int b) {
			return better(ords[a], counts[a], ords[b], counts[b]);
		}

		private void swap(int a, int b) {
			var ord = ords[a];
			var count = counts[a];
			ords[a] = ords[b];
			counts[a] = counts[b];
			ords[b] = ord;
			counts[b] = count;
		}

		private void siftUp(int at) {
			while(at > 0) {
				var parent = (at - 1) / 2;
				if(betterAt(parent, at)) {
					swap(parent, at);
					at = parent;
				} else {
					break;
				}
			}
		}

		private void siftDown(int at) {
			while(true) {
				var left = at * 2 + 1;
				var right = left + 1;
				var worst = at;

				if(left < size && betterAt(worst, left)) {
					worst = left;
				}
				if(right < size && betterAt(worst, right)) {
					worst = right;
				}

				if(worst == at) {
					break;
				}

				swap(at, worst);
				at = worst;
			}
		}
	}
}
