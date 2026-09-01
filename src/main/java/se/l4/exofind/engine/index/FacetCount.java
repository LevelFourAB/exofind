package se.l4.exofind.engine.index;

import java.io.IOException;

import org.apache.lucene.index.LeafReaderContext;

import se.l4.exofind.engine.query.SearchResult;

/**
 * One facet being counted over one scope, fed by a shared walk of the
 * matches.
 *
 * <p>Every facet of a scope reads the same matches, so the iteration is paid
 * once: {@link FacetWalk} walks the matches of the scope and hands each one to
 * every facet counting it. A count only reads the doc values of its own field.
 * The walk owns the iteration order and, where the matches are values of an
 * object field, finding the document above each one.
 *
 * <p>A count accumulates across segments and answers once through
 * {@link #result()}, after every segment was walked. Counts are not thread
 * safe; one count belongs to one search.
 */
public interface FacetCount {
	/**
	 * The walk of the scope is about to start. Called once, before any
	 * segment.
	 *
	 * @param totalMatches
	 *   how many matches the walk holds, across every segment - what sizing
	 *   a counting structure can go on
	 * @throws IOException
	 */
	default void begin(int totalMatches) throws IOException {
	}

	/**
	 * Start counting one segment.
	 *
	 * @param context
	 *   the segment about to be walked
	 * @param matches
	 *   how many matches the walk holds in this segment
	 * @return
	 *   the leaf the walk feeds, or {@code null} when the segment holds
	 *   nothing this facet can count
	 * @throws IOException
	 */
	Leaf leaf(LeafReaderContext context, int matches) throws IOException;

	/**
	 * Get what was counted, shaped as the facet answers.
	 *
	 * @return
	 *   the counts, never {@code null} - a facet nothing matched answers
	 *   empty
	 * @throws IOException
	 */
	SearchResult.Facet result() throws IOException;

	/**
	 * The per-segment part of a count, fed one match at a time in document
	 * order.
	 */
	interface Leaf {
		/**
		 * The document above the matches changed. Called before
		 * {@link #count(int)} for the first match of each document, and only
		 * where the matches are values of an object field.
		 *
		 * @param document
		 *   the document holding the matches that follow
		 * @throws IOException
		 */
		default void beginDocument(int document) throws IOException {
		}

		/**
		 * Count one match.
		 *
		 * @param doc
		 *   the match - a document of the index, or a value of an object
		 *   field, depending on the mode of the scope
		 * @throws IOException
		 */
		void count(int doc) throws IOException;

		/**
		 * The segment is done. Fold what was counted into the whole.
		 *
		 * @throws IOException
		 */
		default void finish() throws IOException {
		}
	}
}
