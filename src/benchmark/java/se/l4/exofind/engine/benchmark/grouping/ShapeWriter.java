package se.l4.exofind.engine.benchmark.grouping;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;

/**
 * Writing one changed variant back into a layout.
 *
 * <p>What that costs is the layout's other half. A price is a value of a
 * variant everywhere, but only some layouts can write a variant on its own:
 * where the variants of a product are a block, or where their values sit on the
 * product, the whole product is rewritten - text, analysis and all - to change
 * one number on one of them.
 *
 * <p>Implementations write to the index in place and are not safe for
 * concurrent use.
 */
public interface ShapeWriter extends Closeable {
	/**
	 * Write back whatever a change to the first variant of a product forces
	 * this layout to write.
	 *
	 * <p>Buffered rather than made searchable; {@link #commit()} is what
	 * finishes it.
	 */
	void change(Catalog.Product product) throws IOException;

	void commit() throws IOException;

	/**
	 * Open a layout for writing, adding to what is already there.
	 *
	 * <p>The caller owns what comes back and has to close it.
	 */
	static ShapeWriter open(Shape shape, Path path) throws IOException {
		return switch(shape) {
			case NESTED -> nested(path);
			case BLOCKED -> blocked(path);
			case GROUPED, COLLAPSED -> variant(path);
			case ROLLED_UP -> product(path);
			case SPLIT -> split(path);
		};
	}

	/**
	 * A block cannot be written into, so a variant is changed by replacing the
	 * product and every variant under it.
	 */
	private static ShapeWriter nested(Path path) throws IOException {
		return over(path, (writer, product) -> {
			var block = Layouts.variantsOf(product);
			block.add(Layouts.productDocument(product));
			writer.updateDocuments(key(product), block);
		});
	}

	/**
	 * The variants may be written one at a time, but only where they are - a
	 * document written back lands at the end of the index, and grouping by runs
	 * would no longer find the product whole. So the run is rewritten.
	 */
	private static ShapeWriter blocked(Path path) throws IOException {
		return over(path, (writer, product) -> {
			var run = new ArrayList<org.apache.lucene.document.Document>();
			for(var variant : product.variants()) {
				run.add(Layouts.variantDocument(product, variant));
			}

			writer.updateDocuments(key(product), run);
		});
	}

	/**
	 * A variant is a document of its own, so only that document is written -
	 * carrying the text of its product, which is analyzed again with it.
	 */
	private static ShapeWriter variant(Path path) throws IOException {
		return over(path, (writer, product) -> {
			var variant = product.variants().get(0);
			writer.updateDocument(
				new Term(Fields.VARIANT_KEY, Long.toString(variant.id())),
				Layouts.variantDocument(product, variant)
			);
		});
	}

	/**
	 * The values of the variants sit on the product, so the product is written.
	 */
	private static ShapeWriter product(Path path) throws IOException {
		return over(path, (writer, product) ->
			writer.updateDocument(key(product), Layouts.rolledUpDocument(product)));
	}

	/**
	 * The variant lives in an index holding no text, so what is written is the
	 * variant and nothing that has to be analyzed.
	 */
	private static ShapeWriter split(Path path) throws IOException {
		return over(path.resolve(Layouts.VARIANTS), (writer, product) -> {
			var variant = product.variants().get(0);
			writer.updateDocument(
				new Term(Fields.VARIANT_KEY, Long.toString(variant.id())),
				Layouts.loneVariantDocument(product, variant)
			);
		});
	}

	/**
	 * What a layout writes back for one changed variant.
	 */
	interface Change {
		void write(IndexWriter writer, Catalog.Product product) throws IOException;
	}

	private static ShapeWriter over(Path path, Change change) throws IOException {
		var directory = FSDirectory.open(path);
		var writer = Layouts.appending(directory);

		return new ShapeWriter() {
			@Override
			public void change(Catalog.Product product) throws IOException {
				change.write(writer, product);
			}

			@Override
			public void commit() throws IOException {
				writer.commit();
			}

			@Override
			public void close() throws IOException {
				writer.close();
				directory.close();
			}
		};
	}

	private static Term key(Catalog.Product product) {
		return new Term(Fields.PRODUCT_KEY, Long.toString(product.id()));
	}
}
