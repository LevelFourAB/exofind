package se.l4.exofind.engine.benchmark.grouping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

/**
 * Writing a catalogue into each of the layouts under comparison.
 *
 * <p>Every layout is written by one thread, in ascending order of product and
 * with the same analysis and the same writer settings, so that two indexes
 * differ in how the catalogue is laid out and in nothing else. Nothing is
 * force-merged: the segments a layout ends up with are the segments its
 * document count leads to, which is part of what the layout costs.
 */
final class Layouts {
	/** What the variants of a product are called where they need a name. */
	static final String VARIANTS = "variants";

	/** Where the products live in a layout that keeps two indexes. */
	static final String PRODUCTS = "products";

	private Layouts() {
	}

	/**
	 * Write products with their variants in the same block, each variant marked
	 * as a variant so that a search can tell the two apart.
	 */
	static void nested(Path path, Catalog catalog, int size) throws IOException {
		try(var directory = FSDirectory.open(path); var writer = writer(directory)) {
			for(var ordinal = 0; ordinal < size; ordinal++) {
				var product = catalog.product(ordinal);
				var block = new ArrayList<Document>(product.variants().size() + 1);

				block.addAll(variantsOf(product));

				var parent = new Document();
				Fields.product(parent, product);
				block.add(parent);

				writer.addDocuments(block);
			}

			writer.commit();
		}
	}

	/**
	 * Write one document per variant, each carrying the text and refinements of
	 * the product it belongs to as well as its own values.
	 */
	static void variants(Path path, Catalog catalog, int size) throws IOException {
		try(var directory = FSDirectory.open(path); var writer = writer(directory)) {
			for(var ordinal = 0; ordinal < size; ordinal++) {
				var product = catalog.product(ordinal);

				for(var variant : product.variants()) {
					writer.addDocument(variantDocument(product, variant));
				}
			}

			writer.commit();
		}
	}

	/**
	 * Write one document per product, holding every value of every variant it
	 * has.
	 */
	static void rolledUp(Path path, Catalog catalog, int size) throws IOException {
		try(var directory = FSDirectory.open(path); var writer = writer(directory)) {
			for(var ordinal = 0; ordinal < size; ordinal++) {
				var product = catalog.product(ordinal);

				writer.addDocument(rolledUpDocument(product));
			}

			writer.commit();
		}
	}

	/**
	 * Write the products into one index and the variants into another, joined by
	 * the identifier of the product and sharing no text.
	 */
	static void split(Path path, Catalog catalog, int size) throws IOException {
		try(
			var productDirectory = FSDirectory.open(path.resolve(PRODUCTS));
			var variantDirectory = FSDirectory.open(path.resolve(VARIANTS));
			var productWriter = writer(productDirectory);
			var variantWriter = writer(variantDirectory)
		) {
			for(var ordinal = 0; ordinal < size; ordinal++) {
				var product = catalog.product(ordinal);

				productWriter.addDocument(productDocument(product));

				for(var variant : product.variants()) {
					variantWriter.addDocument(loneVariantDocument(product, variant));
				}
			}

			productWriter.commit();
			variantWriter.commit();
		}
	}

	/**
	 * Build the documents holding the variants of a product for a layout that
	 * writes them into the same block as the product, each marked as a variant
	 * and named by the product so that rewriting the product replaces them too.
	 */
	static ArrayList<Document> variantsOf(Catalog.Product product) {
		var documents = new ArrayList<Document>(product.variants().size());

		for(var variant : product.variants()) {
			var document = new Document();
			Fields.variant(document, variant);
			Fields.name(document, product);
			document.add(new StringField(Fields.NESTED, VARIANTS, Field.Store.NO));
			document.add(new SortedDocValuesField(Fields.NESTED, new BytesRef(VARIANTS)));
			documents.add(document);
		}

		return documents;
	}

	/**
	 * Build the document holding one variant for a layout that keeps a variant
	 * as a document of the index, carrying the text and refinements of the
	 * product it belongs to.
	 */
	static Document variantDocument(Catalog.Product product, Catalog.Variant variant) {
		var document = new Document();
		Fields.product(document, product);
		Fields.variant(document, variant);
		return document;
	}

	/**
	 * Build the document holding one product with every value of every variant
	 * on it.
	 */
	static Document rolledUpDocument(Catalog.Product product) {
		var document = new Document();
		Fields.product(document, product);
		Fields.variants(document, product.variants());
		return document;
	}

	/**
	 * Build the document holding one product on its own, for a layout that keeps
	 * the variants elsewhere.
	 */
	static Document productDocument(Catalog.Product product) {
		var document = new Document();
		Fields.product(document, product);
		return document;
	}

	/**
	 * Build the document holding one variant on its own, for a layout that keeps
	 * the product elsewhere.
	 */
	static Document loneVariantDocument(Catalog.Product product, Catalog.Variant variant) {
		var document = new Document();
		Fields.identify(document, product);
		Fields.variant(document, variant);
		return document;
	}

	/**
	 * Open a writer over a directory holding an index already, adding to it
	 * rather than replacing it.
	 */
	static IndexWriter appending(Directory directory) throws IOException {
		var config = new IndexWriterConfig(new StandardAnalyzer());
		config.setOpenMode(IndexWriterConfig.OpenMode.APPEND);
		config.setRAMBufferSizeMB(256);

		return new IndexWriter(directory, config);
	}

	/**
	 * Copy a directory and everything below it.
	 */
	static void copy(Path from, Path to) throws IOException {
		try(var paths = Files.walk(from)) {
			for(var path : paths.toList()) {
				var target = to.resolve(from.relativize(path).toString());
				if(Files.isDirectory(path)) {
					Files.createDirectories(target);
				} else {
					Files.copy(path, target);
				}
			}
		}
	}

	/**
	 * Get how many bytes a directory holds, following into the directories of a
	 * layout that keeps more than one index.
	 */
	static long bytes(Directory directory) throws IOException {
		var total = 0L;
		for(var file : directory.listAll()) {
			total += directory.fileLength(file);
		}

		return total;
	}

	/**
	 * Get how many bytes everything below a path holds.
	 */
	static long bytes(Path path) throws IOException {
		if(!Files.isDirectory(path)) {
			return 0;
		}

		try(var files = Files.walk(path)) {
			var total = 0L;
			for(var file : files.toList()) {
				if(Files.isRegularFile(file)) {
					total += Files.size(file);
				}
			}

			return total;
		}
	}

	private static IndexWriter writer(Directory directory) throws IOException {
		var config = new IndexWriterConfig(new StandardAnalyzer());
		config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
		config.setRAMBufferSizeMB(256);

		return new IndexWriter(directory, config);
	}
}
