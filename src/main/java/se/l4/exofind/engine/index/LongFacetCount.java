package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Comparator;
import java.util.function.LongFunction;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
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
		var values = context.reader().getSortedNumericDocValues(field);
		if(values == null) {
			return null;
		}

		return switch(mode) {
			case DOCUMENTS, VALUES -> new EachMatch(values);
			case ROLLED_UP -> new RolledUp(values);
			case PARENTS_BY_VALUE -> new ByDocument(values);
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
		private final SortedNumericDocValues values;

		/*
		 * A field holding one value per document reads through the numeric
		 * doc values directly - the sorted wrapper answers the same thing
		 * through two more calls per document.
		 */
		private final NumericDocValues singleton;

		EachMatch(SortedNumericDocValues values) {
			this.values = values;
			this.singleton = DocValues.unwrapSingleton(values);
		}

		@Override
		public void count(int doc) throws IOException {
			if(singleton != null) {
				if(singleton.advanceExact(doc)) {
					counts.addToValue(singleton.longValue(), 1);
				}
			} else if(values.advanceExact(doc)) {
				var previous = Long.MIN_VALUE;
				for(var i = 0; i < values.docValueCount(); i++) {
					var value = values.nextValue();
					if(i == 0 || value != previous) {
						counts.addToValue(value, 1);
					}

					previous = value;
				}
			}
		}
	}

	/**
	 * The matches are values of an object field and the counts are of the
	 * documents holding them: a value counts once per document however many of
	 * its values hold it.
	 */
	private final class RolledUp implements Leaf {
		private final SortedNumericDocValues values;
		private final MutableLongSet counted = LongSets.mutable.empty();

		RolledUp(SortedNumericDocValues values) {
			this.values = values;
		}

		@Override
		public void beginDocument(int document) {
			counted.clear();
		}

		@Override
		public void count(int doc) throws IOException {
			if(!values.advanceExact(doc)) {
				return;
			}

			for(var i = 0; i < values.docValueCount(); i++) {
				var value = values.nextValue();
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
		private final SortedNumericDocValues values;
		private final MutableLongList documentValues = LongLists.mutable.empty();

		ByDocument(SortedNumericDocValues values) {
			this.values = values;
		}

		@Override
		public void beginDocument(int document) throws IOException {
			documentValues.clear();

			if(values.advanceExact(document)) {
				var previous = Long.MIN_VALUE;
				for(var i = 0; i < values.docValueCount(); i++) {
					var value = values.nextValue();
					if(i == 0 || value != previous) {
						documentValues.add(value);
					}

					previous = value;
				}
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
