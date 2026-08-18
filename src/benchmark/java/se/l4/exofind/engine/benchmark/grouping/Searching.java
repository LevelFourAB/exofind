package se.l4.exofind.engine.benchmark.grouping;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TermQuery;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * What every shape needs to turn an {@link Ask} into Lucene and a page of
 * Lucene documents back into identifiers.
 *
 * <p>The clauses are built here rather than per shape so that a condition
 * reaches every layout as the same terms and the same points, and a difference
 * in timing is a difference in the layout.
 */
final class Searching {
	/**
	 * How many matches a search counts before it settles for knowing there are
	 * more, which is what a page of results needs. Lucene's own default.
	 */
	static final int TOTAL_HITS_THRESHOLD = 1000;

	private Searching() {
	}

	/**
	 * Open a searcher over a reader.
	 *
	 * @param cached
	 *   whether to keep what a narrowing clause matched, which a repeated
	 *   refinement is then answered from. Lucene's own default is to keep it,
	 *   and a node leaves it alone
	 */
	static IndexSearcher searcher(org.apache.lucene.index.IndexReader reader, boolean cached) {
		var searcher = new IndexSearcher(reader);
		if(!cached) {
			searcher.setQueryCache(null);
		}

		return searcher;
	}

	/**
	 * Get the largest value a numeric doc values field holds, or {@code -1}
	 * where it holds none.
	 *
	 * <p>Walks every value once, which is what building a map from group values
	 * to numbers costs and is paid once per reader rather than per search.
	 */
	static int highest(org.apache.lucene.index.IndexReader reader, String field)
		throws IOException
	{
		var highest = -1L;

		for(var leaf : reader.leaves()) {
			var values = leaf.reader().getNumericDocValues(field);
			if(values == null) {
				continue;
			}

			for(
				var doc = values.nextDoc();
				doc != org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;
				doc = values.nextDoc()
			) {
				highest = Math.max(highest, values.longValue());
			}
		}

		return (int) highest;
	}

	/**
	 * Build the clause matching the text of an ask against a document holding
	 * the text of a product, or {@code null} when the ask carries none.
	 *
	 * <p>The words all have to be found, each of them in the title or the
	 * description, and a word found in the title counts for more.
	 */
	static Query text(Ask ask) {
		if(ask.text() == null) {
			return null;
		}

		var all = new BooleanQuery.Builder();
		for(var word : ask.text().split(" ")) {
			all.add(
				new BooleanQuery.Builder()
					.add(
						new BoostQuery(new TermQuery(new Term(Fields.TITLE, word)), 3f),
						BooleanClause.Occur.SHOULD
					)
					.add(
						new TermQuery(new Term(Fields.DESCRIPTION, word)),
						BooleanClause.Occur.SHOULD
					)
					.build(),
				BooleanClause.Occur.MUST
			);
		}

		return all.build();
	}

	/**
	 * Build the clause matching a document holding the values of one variant,
	 * or {@code null} when the ask narrows by none of them.
	 */
	static Query variant(Ask ask) {
		if(!ask.hasVariantConditions()) {
			return null;
		}

		var all = new BooleanQuery.Builder();
		if(ask.color() != null) {
			all.add(
				new TermQuery(new Term(Fields.COLOR, ask.color())),
				BooleanClause.Occur.FILTER
			);
		}

		if(ask.maxPrice() != null) {
			all.add(
				DoublePoint.newRangeQuery(
					Fields.PRICE,
					Double.NEGATIVE_INFINITY,
					ask.maxPrice()
				),
				BooleanClause.Occur.FILTER
			);
		}

		return all.build();
	}

	/**
	 * Join clauses into one query, leaving out the ones that are {@code null}.
	 * A ranking clause is asked for; a narrowing one is only filtered by, so
	 * that nothing pays for a score no result is ordered by.
	 *
	 * @return
	 *   the query, or a query matching everything when nothing was given
	 */
	static Query all(Query ranked, Query... filters) {
		var any = false;
		var builder = new BooleanQuery.Builder();

		if(ranked != null) {
			builder.add(ranked, BooleanClause.Occur.MUST);
			any = true;
		}

		for(var filter : filters) {
			if(filter != null) {
				builder.add(filter, BooleanClause.Occur.FILTER);
				any = true;
			}
		}

		return any ? builder.build() : new MatchAllDocsQuery();
	}

	/**
	 * Read an identifier off each document of a page, keeping the order the
	 * page came back in.
	 *
	 * @param field
	 *   numeric doc values holding the identifier
	 */
	static long[] ids(IndexSearcher searcher, ScoreDoc[] docs, String field) throws IOException {
		var ids = new long[docs.length];
		if(docs.length == 0) {
			return ids;
		}

		/*
		 * Doc values are read forwards, and a page arrives ordered by how well
		 * it answered the search - so the page is walked in the order the
		 * reader will take and each identifier put back where its hit was.
		 */
		var order = new long[docs.length];
		for(var i = 0; i < docs.length; i++) {
			order[i] = ((long) docs[i].doc << 32) | i;
		}

		Arrays.sort(order);

		var leaves = searcher.getIndexReader().leaves();
		var leaf = -1;
		var base = 0;
		var end = -1;
		org.apache.lucene.index.NumericDocValues values = null;

		for(var entry : order) {
			var doc = (int) (entry >>> 32);
			var slot = (int) (entry & 0xffff_ffffL);

			while(doc >= end) {
				leaf++;
				var context = leaves.get(leaf);
				base = context.docBase;
				end = base + context.reader().maxDoc();
				values = context.reader().getNumericDocValues(field);
			}

			ids[slot] = values != null && values.advanceExact(doc - base)
				? values.longValue()
				: -1;
		}

		return ids;
	}

	/**
	 * Collect an identifier off every document a search matches, in ascending
	 * order and each of them once.
	 *
	 * @param field
	 *   numeric doc values holding the identifier
	 */
	static CollectorManager<?, long[]> everyId(String field) {
		return new CollectorManager<Gathering, long[]>() {
			@Override
			public Gathering newCollector() {
				return new Gathering(field);
			}

			@Override
			public long[] reduce(Collection<Gathering> collectors) {
				var all = new LongHashSet();
				for(var collector : collectors) {
					all.addAll(collector.found);
				}

				var ids = all.toArray();
				Arrays.sort(ids);
				return ids;
			}
		};
	}

	private static final class Gathering implements Collector {
		private final String field;
		private final LongHashSet found = new LongHashSet();

		Gathering(String field) {
			this.field = field;
		}

		@Override
		public ScoreMode scoreMode() {
			return ScoreMode.COMPLETE_NO_SCORES;
		}

		@Override
		public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
			var values = context.reader().getNumericDocValues(field);
			if(values == null) {
				return new LeafCollector() {
					@Override
					public void setScorer(Scorable scorer) {
					}

					@Override
					public void collect(int doc) {
					}
				};
			}

			return new LeafCollector() {
				@Override
				public void setScorer(Scorable scorer) {
				}

				@Override
				public void collect(int doc) throws IOException {
					if(values.advanceExact(doc)) {
						found.add(values.longValue());
					}
				}
			};
		}
	}
}
