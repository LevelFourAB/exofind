package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
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
	private final ListIterable<Facet.Range> ranges;
	private final LongRange[] bounds;

	private final long[] counts;

	RangeFacetCount(
		String field,
		FacetMatches.Mode mode,
		ListIterable<Facet.Range> ranges,
		LongRange[] bounds
	) {
		this.field = field;
		this.mode = mode;
		this.ranges = ranges;
		this.bounds = bounds;
		this.counts = new long[bounds.length];
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
		private final SortedNumericDocValues values;

		/*
		 * The last match counted into each bucket. Matches arrive in order,
		 * so comparing against the current one dedupes without clearing an
		 * array per match.
		 */
		private final int[] countedFor;

		EachMatch(SortedNumericDocValues values) {
			this.values = values;
			this.countedFor = new int[bounds.length];
			Arrays.fill(countedFor, -1);
		}

		@Override
		public void count(int doc) throws IOException {
			if(!values.advanceExact(doc)) {
				return;
			}

			for(var i = 0; i < values.docValueCount(); i++) {
				var value = values.nextValue();

				for(var bucket = 0; bucket < bounds.length; bucket++) {
					if(countedFor[bucket] != doc && bounds[bucket].accept(value)) {
						countedFor[bucket] = doc;
						counts[bucket]++;
					}
				}
			}
		}
	}

	/**
	 * The matches are values of an object field and the counts are of the
	 * documents holding them: a document counts into a bucket once, however
	 * many values of however many of its matches fall in it.
	 */
	private final class RolledUp implements Leaf {
		private final SortedNumericDocValues values;

		/*
		 * The last document counted into each bucket. Documents arrive in
		 * order, so comparing against the current one dedupes without
		 * clearing an array per document.
		 */
		private final int[] countedFor;

		private int document = -1;

		RolledUp(SortedNumericDocValues values) {
			this.values = values;
			this.countedFor = new int[bounds.length];
			Arrays.fill(countedFor, -1);
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
				var value = values.nextValue();

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
	 * The matches are values of an object field but the field counted is one
	 * of the index: each match counts into every bucket any of its document's
	 * values falls in.
	 */
	private final class ByDocument implements Leaf {
		private final SortedNumericDocValues values;
		private final boolean[] inBucket;

		ByDocument(SortedNumericDocValues values) {
			this.values = values;
			this.inBucket = new boolean[bounds.length];
		}

		@Override
		public void beginDocument(int document) throws IOException {
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
