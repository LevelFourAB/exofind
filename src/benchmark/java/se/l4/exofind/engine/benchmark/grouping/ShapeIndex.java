package se.l4.exofind.engine.benchmark.grouping;

import java.io.Closeable;
import java.io.IOException;

/**
 * One layout of a catalogue, opened and ready to be asked the same questions as
 * every other layout.
 *
 * <p>A shape that cannot answer a question at all throws
 * {@link UnsupportedOperationException} rather than answering something else,
 * so that a missing number in a comparison is a missing capability rather than
 * a benchmark that was never written. A shape that answers a different question
 * than it was asked - because its layout cannot tell two conditions inside one
 * variant from two conditions across variants - answers all the same, and
 * {@link #allProducts} is what shows the difference.
 *
 * <p>Implementations are read-only and safe for concurrent use.
 */
public interface ShapeIndex extends Closeable {
	/**
	 * The results of one search.
	 *
	 * @param ids
	 *   identifiers of what was found, best first
	 * @param total
	 *   how many answered the search in all, exactly rather than at least
	 */
	record Hits(long[] ids, long total) {
	}

	/**
	 * How many products hold each value of a field, counted once per product
	 * however many of its variants hold the value.
	 *
	 * @param values
	 *   the values, most common first
	 * @param counts
	 *   how many products hold the value at the same position
	 */
	record Counts(String[] values, long[] counts) {
	}

	/**
	 * What the layout costs to keep.
	 *
	 * @param documents
	 *   Lucene documents, which is not the number of products
	 * @param bytes
	 *   the index on disk, both directories together where a shape has two
	 */
	record Stats(long documents, int segments, long bytes) {
	}

	/**
	 * Find the products answering an ask, the best matches first.
	 */
	Hits products(Ask ask) throws IOException;

	/**
	 * Find the variants answering an ask, the best matches first.
	 *
	 * @throws UnsupportedOperationException
	 *   if the shape keeps no variant of its own to return
	 */
	default Hits variants(Ask ask) throws IOException {
		throw new UnsupportedOperationException("variants");
	}

	/**
	 * Count the products answering an ask, without ranking any of them.
	 */
	long countProducts(Ask ask) throws IOException;

	/**
	 * Find the products answering an ask, ordered by the cheapest variant of
	 * each that answered it.
	 *
	 * @throws UnsupportedOperationException
	 *   if the shape cannot order products by a value of their variants
	 */
	default Hits cheapestFirst(Ask ask) throws IOException {
		throw new UnsupportedOperationException("cheapestFirst");
	}

	/**
	 * Count how many products answering an ask come in each colour.
	 *
	 * @throws UnsupportedOperationException
	 *   if the shape cannot count products by a value of their variants
	 */
	default Counts colors(Ask ask) throws IOException {
		throw new UnsupportedOperationException("colors");
	}

	/**
	 * Get every product answering an ask, in ascending order of identifier -
	 * what the answers of two shapes are compared as.
	 */
	long[] allProducts(Ask ask) throws IOException;

	/**
	 * Get what this layout costs to keep.
	 */
	Stats stats() throws IOException;
}
