package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Comparator;
import java.util.function.LongFunction;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.eclipse.collections.api.factory.primitive.LongLists;
import org.eclipse.collections.api.factory.primitive.LongLongMaps;
import org.eclipse.collections.api.factory.primitive.LongSets;
import org.eclipse.collections.api.list.primitive.MutableLongList;
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
 */
final class LongFacetCount implements FacetCount {
	private final String field;
	private final FacetMatches.Mode mode;
	private final int limit;
	private final Facet.Order order;
	private final LongFunction<Object> decode;

	private final MutableLongLongMap counts = LongLongMaps.mutable.empty();

	LongFacetCount(
		String field,
		FacetMatches.Mode mode,
		int limit,
		Facet.Order order,
		LongFunction<Object> decode
	) {
		this.field = field;
		this.mode = mode;
		this.limit = limit;
		this.order = order;
		this.decode = decode;
	}

	@Override
	public Leaf leaf(LeafReaderContext context, int matches) throws IOException {
		if(context.reader().getSortedNumericDocValues(field) == null) {
			return null;
		}

		var column = FacetStates.longsOf(context, field);
		return switch(mode) {
			case DOCUMENTS, VALUES -> new EachMatch(column);
			case ROLLED_UP -> new RolledUp(column);
			case PARENTS_BY_VALUE -> new ByDocument(column);
		};
	}

	@Override
	public SearchResult.Facet result() {
		Comparator<LongLongPair> byValue = Comparator.comparingLong(LongLongPair::getOne);
		Comparator<LongLongPair> byCount =
			Comparator.<LongLongPair>comparingLong(LongLongPair::getTwo)
				.reversed()
				.thenComparing(byValue);

		var sorted = counts.keyValuesView()
			.toSortedList(order == Facet.Order.VALUE ? byValue : byCount);

		var values = sorted.collect(pair -> new SearchResult.Facet.Value(
			decode.apply(pair.getOne()),
			pair.getTwo()
		));

		return new SearchResult.Facet(
			(values.size() > limit ? values.take(limit) : values).toList().toImmutable(),
			counts.size()
		);
	}

	/**
	 * Each match counts its own values, each distinct value once.
	 */
	private final class EachMatch implements Leaf {
		private final FacetColumns.LongSpans spans;

		EachMatch(FacetColumns.Longs column) {
			this.spans = new FacetColumns.LongSpans(column);
		}

		@Override
		public void count(int doc) {
			var from = spans.from(doc);
			var previous = Long.MIN_VALUE;
			for(int i = from, end = spans.to(doc); i < end; i++) {
				var value = spans.values[i];
				if(i == from || value != previous) {
					counts.addToValue(value, 1);
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
		public void countAll(DocIdSetIterator docs) throws IOException {
			for(
				var doc = docs.nextDoc();
				doc != DocIdSetIterator.NO_MORE_DOCS;
				doc = docs.nextDoc()
			) {
				count(doc);
			}
		}
	}

	/**
	 * The matches are values of an object field and the counts are of the
	 * documents holding them: a value counts once per document however many of
	 * its values hold it.
	 */
	private final class RolledUp implements Leaf {
		private final FacetColumns.LongSpans spans;
		private final MutableLongSet counted = LongSets.mutable.empty();

		RolledUp(FacetColumns.Longs column) {
			this.spans = new FacetColumns.LongSpans(column);
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
					counts.addToValue(value, 1);
				}
			}
		}
	}

	/**
	 * The matches are values of an object field but the field counted is one
	 * of the index: each match counts what its document says there.
	 */
	private final class ByDocument implements Leaf {
		private final FacetColumns.LongSpans spans;
		private final MutableLongList documentValues = LongLists.mutable.empty();

		ByDocument(FacetColumns.Longs column) {
			this.spans = new FacetColumns.LongSpans(column);
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
				counts.addToValue(documentValues.get(i), 1);
			}
		}
	}
}
