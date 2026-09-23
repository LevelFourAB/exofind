package se.l4.exofind.engine.index;

import java.io.IOException;

import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.index.LeafReaderContext;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.SearchResult;

/**
 * Counting a facet into buckets by the values a {@link ValueChain} reads for
 * each document.
 *
 * <p>A document counts into a bucket when one of the values of the first step
 * of the chain it holds any value on falls in it, and counts once however
 * many of them do. The values of a later step take no part for a document
 * that holds a value on an earlier one. A {@link ChainSortField} over the
 * same chain orders by the same values.
 *
 * <p>The chain names its values itself, so the matches are always documents
 * of the index - {@link FacetMatches.Mode#DOCUMENTS} - or, for a search whose
 * hits are values, the values counted into what the document holding each
 * one reads - {@link FacetMatches.Mode#PARENTS_BY_VALUE}.
 */
final class ChainRangeFacetCount implements FacetCount {
	private final ValueChain chain;
	private final FacetMatches.Mode mode;
	private final ListIterable<Facet.Range> ranges;
	private final LongRange[] bounds;

	private final long[] counts;

	/**
	 * @param chain
	 *   the values to count, read from the doc values the ranges of the
	 *   field are counted over
	 * @param scope
	 *   the matches to count
	 * @param ranges
	 *   the buckets as the facet asked for them
	 * @param bounds
	 *   the buckets, each bound in the encoding the values were written in
	 */
	ChainRangeFacetCount(
		ValueChain chain,
		FacetMatches scope,
		ListIterable<Facet.Range> ranges,
		LongRange[] bounds
	) {
		this.chain = chain;
		this.mode = scope.mode();
		this.ranges = ranges;
		this.bounds = bounds;
		this.counts = new long[bounds.length];

		if(mode != FacetMatches.Mode.DOCUMENTS && mode != FacetMatches.Mode.PARENTS_BY_VALUE) {
			throw new IllegalArgumentException(
				"A chain reads the values of documents, so it counts documents or values by their document, not " + mode
			);
		}
	}

	@Override
	public Leaf leaf(LeafReaderContext context, int matches) throws IOException {
		var values = chain.values(context);
		if(values == null) {
			return null;
		}

		return mode == FacetMatches.Mode.DOCUMENTS
			? new EachDocument(values)
			: new ByDocument(values);
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
	 * Mark the buckets any value that stands for a document falls in.
	 *
	 * @param values
	 *   the values of the segment
	 * @param document
	 *   the document
	 * @param inBucket
	 *   set for each bucket a value falls in, cleared first
	 * @return
	 *   whether any value stands for the document
	 */
	private boolean bucketsOf(
		ValueChain.Values values,
		int document,
		boolean[] inBucket
	) throws IOException {
		for(var bucket = 0; bucket < bounds.length; bucket++) {
			inBucket[bucket] = false;
		}

		// Only a document of the index has values that stand for it
		if(!values.documents().get(document) || !values.advanceExact(document)) {
			return false;
		}

		for(var i = 0; i < values.count(); i++) {
			var value = values.value(i);

			for(var bucket = 0; bucket < bounds.length; bucket++) {
				if(!inBucket[bucket] && bounds[bucket].accept(value)) {
					inBucket[bucket] = true;
				}
			}
		}

		return true;
	}

	/**
	 * The matches are documents of the index, each counted into the buckets
	 * of its own values.
	 */
	private final class EachDocument implements Leaf {
		private final ValueChain.Values values;
		private final boolean[] inBucket;

		EachDocument(ValueChain.Values values) {
			this.values = values;
			this.inBucket = new boolean[bounds.length];
		}

		@Override
		public void count(int doc) throws IOException {
			if(!bucketsOf(values, doc, inBucket)) {
				return;
			}

			for(var bucket = 0; bucket < bounds.length; bucket++) {
				if(inBucket[bucket]) {
					counts[bucket]++;
				}
			}
		}
	}

	/**
	 * The matches are values of an object field, each counted into the
	 * buckets the values of its document fall in.
	 */
	private final class ByDocument implements Leaf {
		private final ValueChain.Values values;
		private final boolean[] inBucket;

		private boolean holds;

		ByDocument(ValueChain.Values values) {
			this.values = values;
			this.inBucket = new boolean[bounds.length];
		}

		@Override
		public void beginDocument(int document) throws IOException {
			holds = bucketsOf(values, document, inBucket);
		}

		@Override
		public void count(int doc) {
			if(!holds) {
				return;
			}

			for(var bucket = 0; bucket < bounds.length; bucket++) {
				if(inBucket[bucket]) {
					counts[bucket]++;
				}
			}
		}
	}
}
