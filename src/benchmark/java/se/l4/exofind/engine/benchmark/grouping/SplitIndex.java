package se.l4.exofind.engine.benchmark.grouping;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.apache.lucene.store.FSDirectory;

/**
 * Products and variants kept in indexes of their own, joined by the identifier
 * of the product.
 *
 * <p>A condition on a variant is answered by the variant index, and the
 * identifiers it comes back with are what the product index is narrowed by -
 * so a search costs two searches and a set of identifiers as wide as the
 * narrower of them matched. Nothing is written twice, and a variant is changed
 * without the product it belongs to being touched.
 */
final class SplitIndex implements ShapeIndex {
	private final FSDirectory productDirectory;
	private final FSDirectory variantDirectory;
	private final DirectoryReader productReader;
	private final DirectoryReader variantReader;
	private final IndexSearcher products;
	private final IndexSearcher variants;

	SplitIndex(Path path, boolean cached) throws IOException {
		productDirectory = FSDirectory.open(path.resolve(Layouts.PRODUCTS));
		variantDirectory = FSDirectory.open(path.resolve(Layouts.VARIANTS));
		productReader = DirectoryReader.open(productDirectory);
		variantReader = DirectoryReader.open(variantDirectory);
		products = Searching.searcher(productReader, cached);
		variants = Searching.searcher(variantReader, cached);
	}

	@Override
	public Hits products(Ask ask) throws IOException {
		var found = products.search(
			productQuery(ask),
			new TopScoreDocCollectorManager(ask.limit(), Searching.TOTAL_HITS_THRESHOLD)
		);

		return new Hits(
			Searching.ids(products, found.scoreDocs, Fields.PRODUCT),
			found.totalHits.value()
		);
	}

	@Override
	public Hits variants(Ask ask) throws IOException {
		var narrowed = ask.text() == null
			? null
			: holding(products.search(Searching.text(ask), Searching.everyId(Fields.PRODUCT)));

		var found = variants.search(
			Searching.all(null, Searching.variant(ask), narrowed),
			new TopScoreDocCollectorManager(ask.limit(), Searching.TOTAL_HITS_THRESHOLD)
		);

		return new Hits(
			Searching.ids(variants, found.scoreDocs, Fields.VARIANT),
			found.totalHits.value()
		);
	}

	@Override
	public long countProducts(Ask ask) throws IOException {
		return products.count(productQuery(ask));
	}

	@Override
	public long[] allProducts(Ask ask) throws IOException {
		return products.search(productQuery(ask), Searching.everyId(Fields.PRODUCT));
	}

	@Override
	public Stats stats() throws IOException {
		return new Stats(
			productReader.numDocs() + variantReader.numDocs(),
			productReader.leaves().size() + variantReader.leaves().size(),
			Layouts.bytes(productDirectory) + Layouts.bytes(variantDirectory)
		);
	}

	@Override
	public void close() throws IOException {
		productReader.close();
		variantReader.close();
		productDirectory.close();
		variantDirectory.close();
	}

	private Query productQuery(Ask ask) throws IOException {
		var narrowed = ask.hasVariantConditions()
			? holding(variants.search(Searching.variant(ask), Searching.everyId(Fields.PRODUCT)))
			: null;

		return Searching.all(Searching.text(ask), narrowed);
	}

	/**
	 * Build the query matching the products named by a set of identifiers.
	 */
	private static Query holding(long[] ids) {
		return LongPoint.newSetQuery(Fields.PRODUCT_POINT, ids);
	}
}
