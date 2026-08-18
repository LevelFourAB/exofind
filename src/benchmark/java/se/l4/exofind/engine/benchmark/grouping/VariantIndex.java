package se.l4.exofind.engine.benchmark.grouping;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.apache.lucene.store.FSDirectory;

/**
 * Variants kept as documents of the index: every variant is a document of its
 * own carrying its product's text and refinements alongside its own values, and
 * a product is what a page of variants is grouped into.
 *
 * <p>Three ways of grouping over the same index, chosen by what the grouping is
 * allowed to assume - see {@link By}. The index is the same one in all three:
 * {@link Shape#GROUPED}, {@link Shape#COLLAPSED} and {@link Shape#BLOCKED}
 * differ in what they assume, not in what they hold.
 */
final class VariantIndex implements ShapeIndex {
	private final FSDirectory directory;
	private final DirectoryReader reader;
	private final IndexSearcher searcher;
	private final By by;
	private final int products;

	/**
	 * @param by
	 *   how to find the product behind a variant that matched
	 */
	VariantIndex(Path path, By by, boolean cached) throws IOException {
		this.directory = FSDirectory.open(path);
		this.reader = DirectoryReader.open(directory);
		this.searcher = Searching.searcher(reader, cached);
		this.by = by;

		/*
		 * Grouping by an index into an array needs to know how far the numbers
		 * go, which is read once per reader here - the same walk of the values
		 * a global ordinal map over a group field is built by.
		 */
		this.products = by == By.ORDINAL ? Searching.highest(reader, Fields.PRODUCT) + 1 : 0;
	}

	@Override
	public Hits products(Ask ask) throws IOException {
		var found = searcher.search(
			query(ask),
			GroupHeads.byScore(Fields.PRODUCT, ask.limit(), by, products)
		);

		return new Hits(found.groups(), found.total());
	}

	@Override
	public Hits variants(Ask ask) throws IOException {
		var found = searcher.search(
			query(ask),
			new TopScoreDocCollectorManager(ask.limit(), Searching.TOTAL_HITS_THRESHOLD)
		);

		return new Hits(
			Searching.ids(searcher, found.scoreDocs, Fields.VARIANT),
			found.totalHits.value()
		);
	}

	@Override
	public long countProducts(Ask ask) throws IOException {
		return searcher.search(query(ask), GroupHeads.count(Fields.PRODUCT, by, products));
	}

	@Override
	public Hits cheapestFirst(Ask ask) throws IOException {
		var found = searcher.search(
			query(ask),
			GroupHeads.byMinValue(Fields.PRODUCT, Fields.PRICE_SORT, ask.limit(), by, products)
		);

		return new Hits(found.groups(), found.total());
	}

	@Override
	public Counts colors(Ask ask) throws IOException {
		return searcher.search(
			query(ask),
			ValueCounts.perGroup(Fields.COLOR, Fields.PRODUCT, by, products, ask.limit())
		);
	}

	@Override
	public long[] allProducts(Ask ask) throws IOException {
		return searcher.search(query(ask), Searching.everyId(Fields.PRODUCT));
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
	 * Build the query matching the variants answering an ask. Both halves of it
	 * run against one document, so conditions that have to hold inside a single
	 * variant need nothing to hold them together.
	 */
	private Query query(Ask ask) {
		return Searching.all(Searching.text(ask), Searching.variant(ask));
	}
}
