package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Comparator;
import java.util.function.LongFunction;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.util.BitSet;
import org.eclipse.collections.api.factory.primitive.LongLists;
import org.eclipse.collections.api.factory.primitive.LongLongMaps;
import org.eclipse.collections.api.factory.primitive.LongSets;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.api.map.primitive.LongLongMap;
import org.eclipse.collections.api.map.primitive.MutableLongLongMap;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.api.tuple.primitive.LongLongPair;

import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.SearchResult;

/**
 * Counting a facet per value of a field written as sorted numeric doc values.
 *
 * {@link StringFacetCount} describes what one count means per mode.
 * What the numeric encoding adds is that a document can hold the same value
 * twice, so a match counted off itself still counts each distinct value once -
 * the values arrive sorted, which makes the previous one enough to compare
 * with. Rolling up compares across all of a document's matches instead, where
 * the values of different matches do not arrive sorted against each other, so
 * a set carries what the document counted.
 *
 * A scope that is everything the reader holds is counted per segment into a
 * map of its own and that map is kept, see
 * {@link FacetStates#keepSegmentCounts}: the next walk of the segment folds
 * it into the whole without counting a match. Numbers are the same in every
 * segment, so nothing has to be mapped on the way in.
 *
 * A facet with a prefix counts every value the same way and keeps the values
 * whose decoded form starts with the prefix, compared ignoring case: a year
 * facet answers the nineties for {@code 19}, a timestamp facet a month for
 * {@code 2024-06}.
 */
final class LongFacetCount implements FacetCount {
	private final String field;
	private final FacetMatches.Mode mode;
	private final BitSetProducer parents;
	private final FacetMatches.Whole whole;
	private final int limit;
	private final Facet.Order order;
	private final LongFunction<Object> decode;
	private final String prefix;

	private final MutableLongLongMap counts = LongLongMaps.mutable.empty();

	LongFacetCount(
		String field,
		FacetMatches scope,
		int limit,
		Facet.Order order,
		LongFunction<Object> decode,
		String prefix
	) {
		this.field = field;
		this.mode = scope.mode();
		this.parents = scope.parents();
		this.whole = scope.whole();
		this.limit = limit;
		this.order = order;
		this.decode = decode;
		this.prefix = prefix;
	}

	@Override
	public Leaf leaf(LeafReaderContext context, int matches) throws IOException {
		if(context.reader().getSortedNumericDocValues(field) == null) {
			return null;
		}

		/*
		 * Everything the reader holds is counted into a map of the segment's
		 * own and kept; a segment counted before folds what was kept and is
		 * left unwalked. A narrower scope counts straight into the whole.
		 */
		var into = counts;
		LeafReaderContext keepUnder = null;
		if(whole != null) {
			var kept = FacetStates.segmentCountsOf(context, field, mode, whole.path(), LongLongMap.class);
			if(kept != null) {
				kept.forEachKeyValue(counts::addToValue);
				return null;
			}

			into = LongLongMaps.mutable.empty();
			keepUnder = context;
		}

		var column = FacetStates.longsOf(context, field);
		return switch(mode) {
			case DOCUMENTS, VALUES -> new EachMatch(column, into, keepUnder);
			case EVERY_VALUE -> {
				var documents = parents.getBitSet(context);
				yield documents == null ? null : new EveryValue(column, documents, into, keepUnder);
			}
			case ROLLED_UP -> new RolledUp(column, into, keepUnder);
			case PARENTS_BY_VALUE -> new ByDocument(column, into, keepUnder);
		};
	}

	@Override
	public SearchResult.Facet result() {
		Comparator<LongLongPair> byValue = Comparator.comparingLong(LongLongPair::getOne);
		Comparator<LongLongPair> byCount =
			Comparator.<LongLongPair>comparingLong(LongLongPair::getTwo)
				.reversed()
				.thenComparing(byValue);

		var counted = counts.keyValuesView();
		if(prefix != null) {
			counted = counted.select(pair -> {
				var value = String.valueOf(decode.apply(pair.getOne()));
				return value.regionMatches(true, 0, prefix, 0, prefix.length());
			});
		}

		var sorted = counted.toSortedList(order == Facet.Order.VALUE ? byValue : byCount);

		var values = sorted.collect(pair -> new SearchResult.Facet.Value(
			decode.apply(pair.getOne()),
			pair.getTwo()
		));

		return new SearchResult.Facet(
			(values.size() > limit ? values.take(limit) : values).toList().toImmutable(),
			sorted.size()
		);
	}

	/**
	 * Counts of one segment going into a map: the whole, or - over everything
	 * the reader holds - one of the segment's own, kept and folded into the
	 * whole when the segment is done.
	 */
	private abstract class Into implements Leaf {
		final FacetColumns.LongSpans spans;
		final MutableLongLongMap into;
		private final LeafReaderContext keepUnder;

		Into(FacetColumns.Longs column, MutableLongLongMap into, LeafReaderContext keepUnder) {
			this.spans = new FacetColumns.LongSpans(column);
			this.into = into;
			this.keepUnder = keepUnder;
		}

		@Override
		public void finish() {
			if(keepUnder != null) {
				FacetStates.keepSegmentCounts(keepUnder, field, mode, whole.path(), into);
				into.forEachKeyValue(counts::addToValue);
			}
		}
	}

	/**
	 * Each match counts its own values, each distinct value once.
	 */
	private final class EachMatch extends Into {
		EachMatch(FacetColumns.Longs column, MutableLongLongMap into, LeafReaderContext keepUnder) {
			super(column, into, keepUnder);
		}

		@Override
		public void count(int doc) {
			var from = spans.from(doc);
			var previous = Long.MIN_VALUE;
			for(int i = from, end = spans.to(doc); i < end; i++) {
				var value = spans.values[i];
				if(i == from || value != previous) {
					into.addToValue(value, 1);
				}

				previous = value;
			}
		}

		/*
		 * The loop the default runs, overridden so the calls inside it are
		 * made from this class alone and the JIT can inline them - see
		 * Leaf#countAll.
		 */
		@Override
		public void countAll(int[] docs, int length) {
			for(var i = 0; i < length; i++) {
				count(docs[i]);
			}
		}
	}

	/**
	 * The matches are values of an object field and the counts are of the
	 * documents holding them: a value counts once per document however many of
	 * its values hold it.
	 */
	private final class RolledUp extends Into {
		private final MutableLongSet counted = LongSets.mutable.empty();

		RolledUp(FacetColumns.Longs column, MutableLongLongMap into, LeafReaderContext keepUnder) {
			super(column, into, keepUnder);
		}

		@Override
		public void beginDocument(int document) {
			counted.clear();
		}

		@Override
		public void count(int doc) {
			for(int i = spans.from(doc), end = spans.to(doc); i < end; i++) {
				var value = spans.values[i];
				if(counted.add(value)) {
					into.addToValue(value, 1);
				}
			}
		}
	}

	/**
	 * The matches are documents of the index and the numbers live on the
	 * values below each one: a value counts once per document however many
	 * of its values hold it, read off the block of values below the document.
	 */
	private final class EveryValue extends Into {
		private final BitSet documents;
		private final MutableLongSet counted = LongSets.mutable.empty();

		EveryValue(
			FacetColumns.Longs column,
			BitSet documents,
			MutableLongLongMap into,
			LeafReaderContext keepUnder
		) {
			super(column, into, keepUnder);
			this.documents = documents;
		}

		@Override
		public void count(int document) {
			counted.clear();

			for(var doc = FacetWalk.valuesFrom(documents, document); doc < document; doc++) {
				for(int i = spans.from(doc), end = spans.to(doc); i < end; i++) {
					var value = spans.values[i];
					if(counted.add(value)) {
						into.addToValue(value, 1);
					}
				}
			}
		}

		@Override
		public void countAll(int[] docs, int length) {
			for(var i = 0; i < length; i++) {
				count(docs[i]);
			}
		}
	}

	/**
	 * The matches are values of an object field but the field counted is one
	 * of the index: each match counts what its document says there.
	 */
	private final class ByDocument extends Into {
		private final MutableLongList documentValues = LongLists.mutable.empty();

		ByDocument(FacetColumns.Longs column, MutableLongLongMap into, LeafReaderContext keepUnder) {
			super(column, into, keepUnder);
		}

		@Override
		public void beginDocument(int document) {
			documentValues.clear();

			var from = spans.from(document);
			var previous = Long.MIN_VALUE;
			for(int i = from, end = spans.to(document); i < end; i++) {
				var value = spans.values[i];
				if(i == from || value != previous) {
					documentValues.add(value);
				}

				previous = value;
			}
		}

		@Override
		public void count(int doc) {
			for(var i = 0; i < documentValues.size(); i++) {
				into.addToValue(documentValues.get(i), 1);
			}
		}
	}
}
