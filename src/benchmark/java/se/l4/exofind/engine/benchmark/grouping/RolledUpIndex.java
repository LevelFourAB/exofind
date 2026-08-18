package se.l4.exofind.engine.benchmark.grouping;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSelector;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.apache.lucene.store.FSDirectory;

/**
 * Variants rolled up onto the product: one document per product carrying every
 * colour, size and price its variants come in, with nothing left saying which
 * value belonged with which.
 *
 * <p>The cheapest layout to search and the only one that answers a different
 * question than it was asked: two conditions that have to hold inside one
 * variant are satisfied by two different variants here, so a product with a red
 * variant and, separately, a cheap one answers a search for a cheap red one.
 * Kept in the comparison as the floor a layout has to beat to be worth its
 * cost.
 */
final class RolledUpIndex implements ShapeIndex {
	private final FSDirectory directory;
	private final DirectoryReader reader;
	private final IndexSearcher searcher;

	RolledUpIndex(Path path, boolean cached) throws IOException {
		directory = FSDirectory.open(path);
		reader = DirectoryReader.open(directory);
		searcher = Searching.searcher(reader, cached);
	}

	@Override
	public Hits products(Ask ask) throws IOException {
		var found = searcher.search(
			query(ask),
			new TopScoreDocCollectorManager(ask.limit(), Searching.TOTAL_HITS_THRESHOLD)
		);

		return new Hits(
			Searching.ids(searcher, found.scoreDocs, Fields.PRODUCT),
			found.totalHits.value()
		);
	}

	@Override
	public long countProducts(Ask ask) throws IOException {
		return searcher.count(query(ask));
	}

	@Override
	public Hits cheapestFirst(Ask ask) throws IOException {
		/*
		 * The lowest price the product comes in at all, which is not the lowest
		 * price among the variants that answered the search - nothing here says
		 * which prices belong to them.
		 */
		var sort = new Sort(new SortedNumericSortField(
			Fields.PRICE_SORT,
			SortField.Type.DOUBLE,
			false,
			SortedNumericSelector.Type.MIN
		));

		var found = searcher.search(
			query(ask),
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
			query(ask),
			ValueCounts.perDocument(Fields.COLOR, ask.limit())
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

	private Query query(Ask ask) {
		return Searching.all(Searching.text(ask), Searching.variant(ask));
	}
}
