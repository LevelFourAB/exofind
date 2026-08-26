package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.LongFunction;

import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.LongLists;
import org.eclipse.collections.api.factory.primitive.LongLongMaps;
import org.eclipse.collections.api.factory.primitive.LongSets;
import org.eclipse.collections.api.factory.primitive.ObjectLongMaps;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.map.primitive.MutableLongLongMap;
import org.eclipse.collections.api.map.primitive.MutableObjectLongMap;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
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

			/*
			 * Counted by ordinal rather than by term: reading a term costs a
			 * walk of the dictionary and a string of its own, and counting by
			 * term would pay that for every document holding a value rather
			 * than once for the value itself.
			 */
			var byOrdinal = OrdinalCounts.of(values.getValueCount());

			for(
				var doc = iterator.nextDoc();
				doc != DocIdSetIterator.NO_MORE_DOCS;
				doc = iterator.nextDoc()
			) {
				if(doc > document) {
					document = documentOf(parents, doc);
				}

				if(!values.advanceExact(doc)) {
					continue;
				}

				for(var i = 0; i < values.docValueCount(); i++) {
					var ord = values.nextOrd();

					/*
					 * Ordinals are per segment and a document's values never
					 * cross one, so the same term is the same ordinal for as
					 * long as the counts are held.
					 */
					byOrdinal.addOncePer(ord, document);
				}
			}

			byOrdinal.copyInto(values, counts);
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
	 * Count matched values per value of a field of the index, read off the
	 * document each one belongs to - what a facet over a field of the index
	 * answers when the hits of the search are values.
	 *
	 * Each matched value is one count, so a brand facet answers how many
	 * matching variants each brand has - a document with three matching values
	 * counts three times, once per hit it stands behind. The document's values
	 * of the counted field are deduplicated within it, the way counting the
	 * document itself would, so a document naming the same brand twice still
	 * counts each of its hits once there.
	 *
	 * The values of one document sit together and end just before it, so the
	 * matches of one document arrive together and its doc values are read once
	 * and reused for the rest of its matches - which is also what keeps the
	 * forward-only doc values moving forward.
	 *
	 * @param matches
	 *   the values that matched, and what finds the document above each one
	 * @param field
	 *   the Lucene field the documents' values were written under
	 * @param limit
	 *   how many values to bring back at most
	 * @param order
	 *   the order values come back in
	 * @param decode
	 *   how a counted term reads back as a value
	 * @return
	 * @throws IOException
	 */
	static SearchResult.Facet countParentStrings(
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
			var documentOrds = new long[4];
			var documentOrdCount = 0;
			var byOrdinal = OrdinalCounts.of(values.getValueCount());

			/*
			 * A field holding one value per document reads through the sorted
			 * doc values directly - the set wrapper answers the same thing
			 * through two more calls per document.
			 */
			var singleton = DocValues.unwrapSingleton(values);

			for(
				var doc = iterator.nextDoc();
				doc != DocIdSetIterator.NO_MORE_DOCS;
				doc = iterator.nextDoc()
			) {
				if(doc > document) {
					document = documentOf(parents, doc);
					if(document == DocIdSetIterator.NO_MORE_DOCS) {
						break;
					}

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

				for(var i = 0; i < documentOrdCount; i++) {
					byOrdinal.add(documentOrds[i]);
				}
			}

			byOrdinal.copyInto(values, counts);
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
	 * Count matched values per value of a field of the index written as sorted
	 * numeric doc values, read off the document each one belongs to. What the
	 * counting means is {@link #countParentStrings}.
	 *
	 * @param matches
	 *   the values that matched, and what finds the document above each one
	 * @param field
	 *   the Lucene field the documents' values were written under
	 * @param limit
	 *   how many values to bring back at most
	 * @param order
	 *   the order values come back in
	 * @param decode
	 *   how a counted number reads back as a value
	 * @return
	 * @throws IOException
	 */
	static SearchResult.Facet countParentLongs(
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
			var documentValues = LongLists.mutable.empty();

			for(
				var doc = iterator.nextDoc();
				doc != DocIdSetIterator.NO_MORE_DOCS;
				doc = iterator.nextDoc()
			) {
				if(doc > document) {
					document = documentOf(parents, doc);
					if(document == DocIdSetIterator.NO_MORE_DOCS) {
						break;
					}

					documentValues.clear();
					if(values.advanceExact(document)) {
						/*
						 * Sorted numeric doc values can repeat a value the
						 * document gave twice, so the document's values are
						 * deduplicated as they are read - they arrive sorted,
						 * which makes the previous one enough to compare with.
						 */
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

				for(var i = 0; i < documentValues.size(); i++) {
					counts.addToValue(documentValues.get(i), 1);
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
	 * Count matched values into buckets over a field of the index, read off
	 * the document each one belongs to. A value falls in a bucket when any of
	 * its document's values does, and counts once per bucket however many of
	 * them do - the same reading {@link #countRanges} gives a document.
	 *
	 * @param matches
	 *   the values that matched, and what finds the document above each one
	 * @param field
	 *   the Lucene field the documents' values were written under
	 * @param ranges
	 *   the buckets as they were asked for, for echoing their bounds
	 * @param bounds
	 *   the same buckets in the encoding the values were written in
	 * @return
	 * @throws IOException
	 */
	static SearchResult.Facet countParentRanges(
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
			var inBucket = new boolean[bounds.length];

			for(
				var doc = iterator.nextDoc();
				doc != DocIdSetIterator.NO_MORE_DOCS;
				doc = iterator.nextDoc()
			) {
				if(doc > document) {
					document = documentOf(parents, doc);
					if(document == DocIdSetIterator.NO_MORE_DOCS) {
						break;
					}

					Arrays.fill(inBucket, false);
					if(values.advanceExact(document)) {
						for(var i = 0; i < values.docValueCount(); i++) {
							var value = values.nextValue();
							for(var bucket = 0; bucket < bounds.length; bucket++) {
								if(!inBucket[bucket] && bounds[bucket].accept(value)) {
									inBucket[bucket] = true;
								}
							}
						}
					}
				}

				for(var bucket = 0; bucket < bounds.length; bucket++) {
					if(inBucket[bucket]) {
						counts[bucket]++;
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

	/**
	 * Counts per ordinal of one segment's sorted set doc values.
	 *
	 * An array indexed by ordinal while the segment holds few enough distinct
	 * values to afford one, a hash map above that - counting a match is an
	 * array store instead of a hash probe, and matches outnumber distinct
	 * values in any count worth speeding up.
	 */
	private abstract static sealed class OrdinalCounts {
		/**
		 * How many distinct values an array is afforded for. 64k ordinals is
		 * half a megabyte of counts and markers, held only while one facet of
		 * one segment counts.
		 */
		private static final long DENSE_LIMIT = 1 << 16;

		static OrdinalCounts of(long valueCount) {
			return valueCount <= DENSE_LIMIT
				? new Dense((int) valueCount)
				: new Sparse();
		}

		/**
		 * Count the ordinal once.
		 */
		abstract void add(long ord);

		/**
		 * Count the ordinal, at most once per document - what rolling up
		 * means. Documents arrive in order, so the one counted last is the
		 * only one a repeat can belong to.
		 */
		abstract void addOncePer(long ord, int document);

		/**
		 * Add what was counted to {@code counts} by term. Ordinals are read in
		 * order, so the dictionary is read forwards - a term after the one
		 * just read is found by carrying on rather than by seeking again.
		 */
		abstract void copyInto(
			SortedSetDocValues values,
			MutableObjectLongMap<String> counts
		) throws IOException;

		private static final class Dense extends OrdinalCounts {
			private final int[] counts;
			private int[] countedFor;

			Dense(int valueCount) {
				this.counts = new int[valueCount];
			}

			@Override
			void add(long ord) {
				counts[(int) ord]++;
			}

			@Override
			void addOncePer(long ord, int document) {
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
			void copyInto(
				SortedSetDocValues values,
				MutableObjectLongMap<String> counts
			) throws IOException {
				for(var ord = 0; ord < this.counts.length; ord++) {
					if(this.counts[ord] > 0) {
						counts.addToValue(
							values.lookupOrd(ord).utf8ToString(),
							this.counts[ord]
						);
					}
				}
			}
		}

		private static final class Sparse extends OrdinalCounts {
			private final MutableLongLongMap counts = LongLongMaps.mutable.empty();
			private final MutableLongSet counted = LongSets.mutable.empty();
			private int countedDocument = -1;

			@Override
			void add(long ord) {
				counts.addToValue(ord, 1);
			}

			@Override
			void addOncePer(long ord, int document) {
				if(document != countedDocument) {
					countedDocument = document;
					counted.clear();
				}

				if(counted.add(ord)) {
					counts.addToValue(ord, 1);
				}
			}

			@Override
			void copyInto(
				SortedSetDocValues values,
				MutableObjectLongMap<String> counts
			) throws IOException {
				for(var ord : this.counts.keySet().toSortedArray()) {
					counts.addToValue(
						values.lookupOrd(ord).utf8ToString(),
						this.counts.get(ord)
					);
				}
			}
		}
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
