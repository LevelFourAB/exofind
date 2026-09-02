package se.l4.exofind.engine.index;

import java.io.IOException;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.util.FixedBitSet;

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
	 * The per-segment part of a count, fed the matches in document order:
	 * one at a time where a document has to be resolved above each match,
	 * and in batches otherwise.
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
		 * Count a batch of matches, the same as feeding each one through
		 * {@link #count(int)}. The walk uses this where no document needs
		 * resolving above the matches: it reads the matches of the segment
		 * once and offers each batch to every leaf, so the call into a leaf
		 * is paid per batch instead of per match.
		 *
		 * <p>The default loop is shared by every leaf that does not override
		 * it, so the calls it makes cannot be inlined. A leaf hot enough to
		 * matter overrides it with the same loop, which moves those calls into
		 * its own class.
		 *
		 * @param docs
		 *   the matches, in document order, in the first {@code length}
		 *   entries - the walk reuses the array for the next batch
		 * @param length
		 *   how many matches the batch holds
		 * @throws IOException
		 */
		default void countAll(int[] docs, int length) throws IOException {
			for(var i = 0; i < length; i++) {
				count(docs[i]);
			}
		}

		/**
		 * Count every match of the segment at once, given the matches as a
		 * bitset over it. The walk offers the matches this way before it
		 * batches them, where they were collected as a bitset - a scope wide
		 * enough to be worth counting another way is held as one. A leaf that
		 * can count such a scope without visiting every match takes it here;
		 * one that answers {@code false} is fed the same matches through
		 * {@link #countAll(int[], int)} instead, and is never fed them twice.
		 *
		 * @param matches
		 *   the matches of the segment, as long as the segment
		 * @return
		 *   whether the leaf counted the segment
		 * @throws IOException
		 */
		default boolean countWhole(FixedBitSet matches) throws IOException {
			return false;
		}

		/**
		 * The segment is done. Fold what was counted into the whole.
		 *
		 * @throws IOException
		 */
		default void finish() throws IOException {
		}
	}
}
