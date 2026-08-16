package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.LongFunction;

import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.LongLongMaps;
import org.eclipse.collections.api.factory.primitive.LongSets;
import org.eclipse.collections.api.factory.primitive.ObjectLongMaps;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.tuple.primitive.LongLongPair;
import org.eclipse.collections.api.tuple.primitive.ObjectLongPair;

import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.SearchResult;

/**
 * Counting a facet over a field inside an object, where what was collected is
 * the values of that object rather than the documents holding them.
 *
 * The counts answer documents all the same, because that is what every other
 * facet counts and what ticking a value would leave: a product with three red
 * variants is one red product. Rolling up is what the values are counted
 * through - a value is counted the first time one of a document's values holds
 * it and never again - which is why the counting is here rather than left to
 * Lucene's facet counters, none of which knows that two matches can be the same
 * document.
 *
 * The values of one document sit together and end just before it, so the
 * document a value belongs to is the next one at or after it, and walking the
 * matches in order visits a document's values one block at a time. That is what
 * makes rolling up a set that is cleared per document rather than one held for
 * the whole index.
 */
final class NestedFacets {
	private NestedFacets() {
	}

	/**
	 * Count the documents behind the matched values per value of a field
	 * written as sorted set doc values.
	 *
	 * @param matches
	 *   the values that matched, and what rolls them up
	 * @param field
	 *   the Lucene field the values were written under
	 * @param limit
	 *   how many values to bring back at most
	 * @param order
	 *   the order values come back in
	 * @param decode
	 *   how a counted term reads back as a value
	 * @return
	 * @throws IOException
	 */
	static SearchResult.Facet countStrings(
		FacetMatches matches,
		String field,
		int limit,
		Facet.Order order,
		Function<String, Object> decode
	) throws IOException {
		var counts = ObjectLongMaps.mutable.<String>empty();

		for(var docs : matches.hits().getMatchingDocs()) {
			var context = docs.context();
			var values = context.reader().getSortedSetDocValues(field);
			var iterator = docs.bits() == null ? null : docs.bits().iterator();
			var parents = matches.parents().getBitSet(context);
			if(values == null || iterator == null || parents == null) {
				continue;
			}

			var document = -1;
			var counted = LongSets.mutable.empty();

			/*
			 * Counted by ordinal rather than by term: reading a term costs a
			 * walk of the dictionary and a string of its own, and counting by
			 * term would pay that for every document holding a value rather
			 * than once for the value itself.
			 */
			var byOrdinal = LongLongMaps.mutable.empty();

			for(
				var doc = iterator.nextDoc();
				doc != DocIdSetIterator.NO_MORE_DOCS;
				doc = iterator.nextDoc()
			) {
				if(doc > document) {
					document = documentOf(parents, doc);
					counted.clear();
				}

				if(!values.advanceExact(doc)) {
					continue;
				}

				for(var i = 0; i < values.docValueCount(); i++) {
					var ord = values.nextOrd();

					/*
					 * Ordinals are per segment and a document's values never
					 * cross one, so the same term is the same ordinal for as
					 * long as the set is held.
					 */
					if(counted.add(ord)) {
						byOrdinal.addToValue(ord, 1);
					}
				}
			}

			/*
			 * In order, because the dictionary is read forwards - a term after
			 * the one just read is found by carrying on rather than by seeking
			 * again.
			 */
			var ordinals = byOrdinal.keySet().toSortedArray();
			for(var ord : ordinals) {
				counts.addToValue(
					values.lookupOrd(ord).utf8ToString(),
					byOrdinal.get(ord)
				);
			}
		}

		Comparator<ObjectLongPair<String>> byValue =
			Comparator.comparing(ObjectLongPair::getOne);
		Comparator<ObjectLongPair<String>> byCount =
			Comparator.<ObjectLongPair<String>>comparingLong(ObjectLongPair::getTwo)
				.reversed()
				.thenComparing(byValue);

		var sorted = counts.keyValuesView()
			.toSortedList(order == Facet.Order.VALUE ? byValue : byCount);

		return toFacet(
			sorted.collect(pair -> new SearchResult.Facet.Value(
				decode.apply(pair.getOne()),
				pair.getTwo()
			)),
			limit,
			counts.size()
		);
	}

	/**
	 * Count the documents behind the matched values per value of a field
	 * written as sorted numeric doc values.
	 *
	 * @param matches
	 *   the values that matched, and what rolls them up
	 * @param field
	 *   the Lucene field the values were written under
	 * @param limit
	 *   how many values to bring back at most
	 * @param order
	 *   the order values come back in
	 * @param decode
	 *   how a counted number reads back as a value
	 * @return
	 * @throws IOException
	 */
	static SearchResult.Facet countLongs(
		FacetMatches matches,
		String field,
		int limit,
		Facet.Order order,
		LongFunction<Object> decode
	) throws IOException {
		var counts = LongLongMaps.mutable.empty();

		for(var docs : matches.hits().getMatchingDocs()) {
			var context = docs.context();
			var values = context.reader().getSortedNumericDocValues(field);
			var iterator = docs.bits() == null ? null : docs.bits().iterator();
			var parents = matches.parents().getBitSet(context);
			if(values == null || iterator == null || parents == null) {
				continue;
			}

			var document = -1;
			var counted = LongSets.mutable.empty();

			for(
				var doc = iterator.nextDoc();
				doc != DocIdSetIterator.NO_MORE_DOCS;
				doc = iterator.nextDoc()
			) {
				if(doc > document) {
					document = documentOf(parents, doc);
					counted.clear();
				}

				if(!values.advanceExact(doc)) {
					continue;
				}

				for(var i = 0; i < values.docValueCount(); i++) {
					var value = values.nextValue();
					if(counted.add(value)) {
						counts.addToValue(value, 1);
					}
				}
			}
		}

		Comparator<LongLongPair> byValue = Comparator.comparingLong(LongLongPair::getOne);
		Comparator<LongLongPair> byCount =
			Comparator.<LongLongPair>comparingLong(LongLongPair::getTwo)
				.reversed()
				.thenComparing(byValue);

		var sorted = counts.keyValuesView()
			.toSortedList(order == Facet.Order.VALUE ? byValue : byCount);

		return toFacet(
			sorted.collect(pair -> new SearchResult.Facet.Value(
				decode.apply(pair.getOne()),
				pair.getTwo()
			)),
			limit,
			counts.size()
		);
	}

	/**
	 * Count the documents behind the matched values into buckets, reading a
	 * field written as sorted numeric doc values.
	 *
	 * A document falls in a bucket when any of its values does, so one holding
	 * two values of the same bucket is counted once - the same rolling up the
	 * counting per value does.
	 *
	 * @param matches
	 *   the values that matched, and what rolls them up
	 * @param field
	 *   the Lucene field the values were written under
	 * @param ranges
	 *   the buckets as they were asked for, for echoing their bounds
	 * @param bounds
	 *   the same buckets in the encoding the values were written in
	 * @return
	 * @throws IOException
	 */
	static SearchResult.Facet countRanges(
		FacetMatches matches,
		String field,
		ListIterable<Facet.Range> ranges,
		LongRange[] bounds
	) throws IOException {
		var counts = new long[bounds.length];

		for(var docs : matches.hits().getMatchingDocs()) {
			var context = docs.context();
			var values = context.reader().getSortedNumericDocValues(field);
			var iterator = docs.bits() == null ? null : docs.bits().iterator();
			var parents = matches.parents().getBitSet(context);
			if(values == null || iterator == null || parents == null) {
				continue;
			}

			var document = -1;
			var counted = new boolean[bounds.length];

			for(
				var doc = iterator.nextDoc();
				doc != DocIdSetIterator.NO_MORE_DOCS;
				doc = iterator.nextDoc()
			) {
				if(doc > document) {
					document = documentOf(parents, doc);
					Arrays.fill(counted, false);
				}

				if(!values.advanceExact(doc)) {
					continue;
				}

				for(var i = 0; i < values.docValueCount(); i++) {
					var value = values.nextValue();

					for(var bucket = 0; bucket < bounds.length; bucket++) {
						if(!counted[bucket] && bounds[bucket].accept(value)) {
							counted[bucket] = true;
							counts[bucket]++;
						}
					}
				}
			}
		}

		var buckets = Lists.mutable.<SearchResult.Facet.Bucket>empty();
		var position = 0;
		for(var range : ranges) {
			buckets.add(
				new SearchResult.Facet.Bucket(range.from(), range.to(), counts[position++])
			);
		}

		return SearchResult.Facet.ofBuckets(buckets.toImmutable());
	}

	/**
	 * Get the document a value belongs to, which is the first document at or
	 * after it.
	 */
	private static int documentOf(BitSet parents, int doc) {
		return doc < parents.length()
			? parents.nextSetBit(doc)
			: DocIdSetIterator.NO_MORE_DOCS;
	}

	private static SearchResult.Facet toFacet(
		ListIterable<SearchResult.Facet.Value> values,
		int limit,
		int totalValues
	) {
		return new SearchResult.Facet(
			(values.size() > limit ? values.take(limit) : values).toList().toImmutable(),
			totalValues
		);
	}
}
