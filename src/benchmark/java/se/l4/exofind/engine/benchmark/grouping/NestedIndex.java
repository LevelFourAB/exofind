package se.l4.exofind.engine.benchmark.grouping;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.QueryBitSetProducer;
import org.apache.lucene.search.join.ScoreMode;
import org.apache.lucene.search.join.ToChildBlockJoinQuery;
import org.apache.lucene.search.join.ToParentBlockJoinQuery;
import org.apache.lucene.search.join.ToParentBlockJoinSortField;
import org.apache.lucene.store.FSDirectory;

/**
 * Variants kept as sub-documents: every variant is a Lucene document written
 * into the same block as the product it belongs to, and a condition on a
 * variant is joined back up to the product.
 *
 * <p>This is the layout Exofind uses for object fields today.
 */
final class NestedIndex implements ShapeIndex {
	private final FSDirectory directory;
	private final DirectoryReader reader;
	private final IndexSearcher searcher;

	private final BitSetProducer parents;
	private final Query variants;
	private final Query products;

	NestedIndex(Path path, boolean cached) throws IOException {
		directory = FSDirectory.open(path);
		reader = DirectoryReader.open(directory);
		searcher = Searching.searcher(reader, cached);

		parents = new QueryBitSetProducer(
			new BooleanQuery.Builder()
				.add(new MatchAllDocsQuery(), BooleanClause.Occur.FILTER)
				.add(new FieldExistsQuery(Fields.NESTED), BooleanClause.Occur.MUST_NOT)
				.build()
		);

		variants = new TermQuery(new Term(Fields.NESTED, Layouts.VARIANTS));
		products = new ParentsFilter(parents);
	}

	@Override
	public Hits products(Ask ask) throws IOException {
		var found = searcher.search(
			productQuery(ask),
			new TopScoreDocCollectorManager(ask.limit(), Searching.TOTAL_HITS_THRESHOLD)
		);

		return new Hits(
			Searching.ids(searcher, found.scoreDocs, Fields.PRODUCT),
			found.totalHits.value()
		);
	}

	@Override
	public Hits variants(Ask ask) throws IOException {
		var found = searcher.search(
			variantQuery(ask),
			new TopScoreDocCollectorManager(ask.limit(), Searching.TOTAL_HITS_THRESHOLD)
		);

		return new Hits(
			Searching.ids(searcher, found.scoreDocs, Fields.VARIANT),
			found.totalHits.value()
		);
	}

	@Override
	public long countProducts(Ask ask) throws IOException {
		return searcher.count(productQuery(ask));
	}

	@Override
	public Hits cheapestFirst(Ask ask) throws IOException {
		/*
		 * Only the variants the search matched may stand for their product, so
		 * the set is built from the same conditions the search ran - ordering
		 * a page of red variants by the cheapest variant in any colour would
		 * be out of order in a way nothing on the page shows.
		 */
		var matched = new QueryBitSetProducer(childQuery(ask));

		var sort = new Sort(new ToParentBlockJoinSortField(
			Fields.PRICE_SORT,
			SortField.Type.DOUBLE,
			false,
			false,
			parents,
			matched
		));

		var found = searcher.search(
			productQuery(ask),
			new TopFieldCollectorManager(sort, ask.limit(), Searching.TOTAL_HITS_THRESHOLD)
		);

		return new Hits(
			Searching.ids(searcher, found.scoreDocs, Fields.PRODUCT),
			found.totalHits.value()
		);
	}

	@Override
	public Counts colors(Ask ask) throws IOException {
		return searcher.search(
			new ToChildBlockJoinQuery(productQuery(ask), parents),
			ValueCounts.perParent(Fields.COLOR, parents, ask.limit())
		);
	}

	@Override
	public long[] allProducts(Ask ask) throws IOException {
		return searcher.search(productQuery(ask), Searching.everyId(Fields.PRODUCT));
	}

	@Override
	public Stats stats() throws IOException {
		return new Stats(reader.numDocs(), reader.leaves().size(), Layouts.bytes(directory));
	}

	@Override
	public void close() throws IOException {
		reader.close();
		directory.close();
	}

	/**
	 * Build the query matching the products answering an ask. The join already
	 * answers with products, so the products are only named where there is no
	 * join and nothing else that could match a variant.
	 */
	private Query productQuery(Ask ask) {
		var text = Searching.text(ask);
		if(!ask.hasVariantConditions()) {
			return text == null ? products : text;
		}

		return Searching.all(
			text,
			new ToParentBlockJoinQuery(childQuery(ask), parents, ScoreMode.None)
		);
	}

	/**
	 * Build the query matching the variants answering an ask, whichever product
	 * they belong to.
	 */
	private Query childQuery(Ask ask) {
		var conditions = Searching.variant(ask);
		return conditions == null
			? variants
			: new BooleanQuery.Builder()
				.add(variants, BooleanClause.Occur.FILTER)
				.add(conditions, BooleanClause.Occur.FILTER)
				.build();
	}

	/**
	 * Build the query matching the variants of the products answering an ask -
	 * which takes the text down to the variants it says nothing about, because
	 * only the product carries it.
	 */
	private Query variantQuery(Ask ask) {
		var text = Searching.text(ask);
		if(text == null) {
			return childQuery(ask);
		}

		return new BooleanQuery.Builder()
			.add(new ToChildBlockJoinQuery(text, parents), BooleanClause.Occur.MUST)
			.add(childQuery(ask), BooleanClause.Occur.FILTER)
			.build();
	}
}
