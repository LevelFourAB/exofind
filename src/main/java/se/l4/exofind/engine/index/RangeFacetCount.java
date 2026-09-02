package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BitSetIterator;
import org.apache.lucene.util.FixedBitSet;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.SearchResult;

/**
 * Counting a facet into buckets, reading a field written as sorted numeric
 * doc values.
 *
 * {@link StringFacetCount} describes what one count means per mode; a
 * bucket adds that what falls in it is decided over all of a unit's values. A
 * document falls in a bucket when any of its values does, and counts once
 * however many of them do. Buckets may overlap, so one value can count into
 * several.
 */
final class RangeFacetCount implements FacetCount {
	private final String field;
	private final FacetMatches.Mode mode;
	private final BitSetProducer parents;
	private final ListIterable<Facet.Range> ranges;
	private final LongRange[] bounds;

	private final long[] counts;

	RangeFacetCount(
		String field,
		FacetMatches scope,
		ListIterable<Facet.Range> ranges,
		LongRange[] bounds
	) {
		this.field = field;
		this.mode = scope.mode();
		this.parents = scope.parents();
		this.ranges = ranges;
		this.bounds = bounds;
		this.counts = new long[bounds.length];
	}

	@Override
	public Leaf leaf(LeafReaderContext context, int matches) throws IOException {
		if(context.reader().getSortedNumericDocValues(field) == null) {
			return null;
		}

		var column = FacetStates.longsOf(context, field);
		return switch(mode) {
			case DOCUMENTS, VALUES -> {
				/*
				 * A scope covering much of the segment is counted a bucket at
				 * a time off the sorted column where that is cheaper than
				 * walking it - see FacetColumns. Each bucket is a run of the
				 * sorted numbers, and its cost is the run's length, so the
				 * whole page costs about the segment's numbers once.
				 */
				FacetColumns.LongPostings postings = null;
				var maxDoc = context.reader().maxDoc();
				if(FacetColumns.isWide(matches, column.docCount())) {
					var sorted = FacetStates.longPostingsOf(context, field);
					var cost = (long) sorted.values().length
						* FacetColumns.OrdPostings.LOOKUP_COST;
					if(FacetColumns.cheaperThanWalking(cost, matches)) {
						postings = sorted;
					}
				}

				yield new EachMatch(column, postings, maxDoc);
			}
			case EVERY_VALUE -> {
				var documents = parents.getBitSet(context);
				if(documents == null) {
					yield null;
				}

				/*
				 * A wide scope of documents is counted a bucket at a time off
				 * postings built in terms of the documents, the way the leaf
				 * above counts documents - see FacetColumns and
				 * StringFacetCount, which judges width the same way.
				 */
				FacetColumns.LongPostings postings = null;
				var maxDoc = context.reader().maxDoc();
				if(FacetColumns.isWide(matches, FacetStates.documentCountOf(context, parents))) {
					var sorted = FacetStates.rolledUpLongPostingsOf(context, field, parents);
					if(sorted != null) {
						var cost = (long) sorted.values().length
							* FacetColumns.OrdPostings.LOOKUP_COST;
						if(FacetColumns.cheaperThanWalking(cost, matches)) {
							postings = sorted;
						}
					}
				}

				yield new EveryValue(column, documents, postings, maxDoc);
			}
			case ROLLED_UP -> new RolledUp(column);
			case PARENTS_BY_VALUE -> new ByDocument(column);
		};
	}

	/**
	 * Count every bucket against the given documents through postings sorted
	 * by number, each bucket being one run of them.
	 *
	 * A document holding two numbers in one bucket stands twice in its run,
	 * and counts once: the ones counted so far are marked per bucket, unless
	 * no document stands twice at all.
	 *
	 * @param postings
	 *   the numbers sorted, each beside what holds it
	 * @param matches
	 *   what matched, as long as the segment - the documents the postings
	 *   stand beside
	 * @param maxDoc
	 *   how many documents the segment holds
	 */
	private void countBuckets(
		FacetColumns.LongPostings postings,
		FixedBitSet matches,
		int maxDoc
	) {
		var counted = postings.single() ? null : new FixedBitSet(maxDoc);
		var docs = postings.docs();
		for(var bucket = 0; bucket < bounds.length; bucket++) {
			if(counted != null && bucket > 0) {
				counted.clear();
			}

			var from = postings.from(bounds[bucket].min);
			var to = postings.to(bounds[bucket].max);
			var count = 0L;
			for(var i = from; i < to; i++) {
				var doc = docs[i];
				if(matches.get(doc) && (counted == null || !counted.getAndSet(doc))) {
					count++;
				}
			}

			counts[bucket] += count;
		}
	}

	@Override
	public SearchResult.Facet result() {
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
	 * Each match counts its own values: one count per bucket any of them
	 * falls in.
	 */
	private final class EachMatch implements Leaf {
		private final FacetColumns.LongSpans spans;
		private final FacetColumns.LongPostings postings;
		private final int maxDoc;

		/*
		 * The last match counted into each bucket. Matches arrive in order,
		 * so comparing against the current one dedupes without clearing an
		 * array per match.
		 */
		private final int[] countedFor;

		EachMatch(FacetColumns.Longs column, FacetColumns.LongPostings postings, int maxDoc) {
			this.spans = new FacetColumns.LongSpans(column);
			this.postings = postings;
			this.maxDoc = maxDoc;
			this.countedFor = new int[bounds.length];
			Arrays.fill(countedFor, -1);
		}

		@Override
		public void countAll(FixedBitSet matches) throws IOException {
			if(postings == null) {
				countAll(new BitSetIterator(matches, matches.length()));
			} else {
				countBuckets(postings, matches, maxDoc);
			}
		}

		@Override
		public void count(int doc) {
			for(int i = spans.from(doc), end = spans.to(doc); i < end; i++) {
				var value = spans.values[i];

				for(var bucket = 0; bucket < bounds.length; bucket++) {
					if(countedFor[bucket] != doc && bounds[bucket].accept(value)) {
						countedFor[bucket] = doc;
						counts[bucket]++;
					}
				}
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
	 * documents holding them: a document counts into a bucket once, however
	 * many values of however many of its matches fall in it.
	 */
	private final class RolledUp implements Leaf {
		private final FacetColumns.LongSpans spans;

		/*
		 * The last document counted into each bucket. Documents arrive in
		 * order, so comparing against the current one dedupes without
		 * clearing an array per document.
		 */
		private final int[] countedFor;

		private int document = -1;

		RolledUp(FacetColumns.Longs column) {
			this.spans = new FacetColumns.LongSpans(column);
			this.countedFor = new int[bounds.length];
			Arrays.fill(countedFor, -1);
		}

		@Override
		public void beginDocument(int document) {
			this.document = document;
		}

		@Override
		public void count(int doc) {
			for(int i = spans.from(doc), end = spans.to(doc); i < end; i++) {
				var value = spans.values[i];

				for(var bucket = 0; bucket < bounds.length; bucket++) {
					if(countedFor[bucket] != document && bounds[bucket].accept(value)) {
						countedFor[bucket] = document;
						counts[bucket]++;
					}
				}
			}
		}
	}

	/**
	 * The matches are documents of the index and the numbers live on the
	 * values below each one: a document counts into a bucket once, however
	 * many values of however many of its values fall in it, read off the
	 * block of values below the document - or, over a wide scope, off
	 * postings that stand beside the documents rather than the values.
	 */
	private final class EveryValue implements Leaf {
		private final FacetColumns.LongSpans spans;
		private final BitSet documents;
		private final FacetColumns.LongPostings postings;
		private final int maxDoc;

		/*
		 * The last document counted into each bucket - see RolledUp.
		 */
		private final int[] countedFor;

		EveryValue(
			FacetColumns.Longs column,
			BitSet documents,
			FacetColumns.LongPostings postings,
			int maxDoc
		) {
			this.spans = new FacetColumns.LongSpans(column);
			this.documents = documents;
			this.postings = postings;
			this.maxDoc = maxDoc;
			this.countedFor = new int[bounds.length];
			Arrays.fill(countedFor, -1);
		}

		@Override
		public void count(int document) {
			for(var doc = FacetWalk.valuesFrom(documents, document); doc < document; doc++) {
				for(int i = spans.from(doc), end = spans.to(doc); i < end; i++) {
					var value = spans.values[i];

					for(var bucket = 0; bucket < bounds.length; bucket++) {
						if(countedFor[bucket] != document && bounds[bucket].accept(value)) {
							countedFor[bucket] = document;
							counts[bucket]++;
						}
					}
				}
			}
		}

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

		@Override
		public void countAll(FixedBitSet matches) throws IOException {
			if(postings == null) {
				countAll(new BitSetIterator(matches, matches.length()));
			} else {
				countBuckets(postings, matches, maxDoc);
			}
		}
	}

	/**
	 * The matches are values of an object field but the field counted is one
	 * of the index: each match counts into every bucket any of its document's
	 * values falls in.
	 */
	private final class ByDocument implements Leaf {
		private final FacetColumns.LongSpans spans;
		private final boolean[] inBucket;

		ByDocument(FacetColumns.Longs column) {
			this.spans = new FacetColumns.LongSpans(column);
			this.inBucket = new boolean[bounds.length];
		}

		@Override
		public void beginDocument(int document) {
			Arrays.fill(inBucket, false);

			for(int i = spans.from(document), end = spans.to(document); i < end; i++) {
				var value = spans.values[i];

				for(var bucket = 0; bucket < bounds.length; bucket++) {
					if(!inBucket[bucket] && bounds[bucket].accept(value)) {
						inBucket[bucket] = true;
					}
				}
			}
		}

		@Override
		public void count(int doc) {
			for(var bucket = 0; bucket < bounds.length; bucket++) {
				if(inBucket[bucket]) {
					counts[bucket]++;
				}
			}
		}
	}
}
