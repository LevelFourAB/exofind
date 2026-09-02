package se.l4.exofind.engine.index;

import java.util.List;

import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.search.join.BitSetProducer;

/**
 * The matches one facet counts, and what has to happen to them before they are
 * counts of what the search answers with.
 *
 * What was collected and what the counts should be of do not always agree,
 * and {@link Mode} is what bridges them. A facet over a field of the index
 * usually counts documents that matched, and each of them is one count -
 * {@link Mode#DOCUMENTS}. A facet over a field inside an object counts the
 * values of that object instead - Lucene documents of their own, several of
 * which can belong to the same document of the index - so counting them as
 * they come would answer how many variants are red rather than how many
 * products have one; {@link Mode#ROLLED_UP} is what folds them back into
 * documents.
 *
 * Rolling up has a shorter road where the scope asks nothing of the values
 * themselves: it then matches every value of each document it matches, so
 * which documents hold a value is a question about the documents alone.
 * Such a scope is collected as the documents rather than their values -
 * {@link Mode#EVERY_VALUE} - and a facet over a field inside the object
 * counts each document once per value any of its values holds, either by
 * reading the block of values below the document or, over a wide scope,
 * off postings built in terms of the documents - see
 * {@link FacetColumns#rolledUpOrdPostings}. A scope with a {@code nested}
 * clause on the path matches only the values that satisfied it, and a
 * document then counts for a value only when one of those values holds it,
 * which nothing but a walk of the values can tell; that is what keeps
 * {@link Mode#ROLLED_UP} walking.
 *
 * A search whose hits are the matched values of an object field turns this
 * around: the counts should be of values, because that is what the hits are.
 * The collected values are then counted as they come - {@link Mode#VALUES} -
 * and a facet over a field of the index counts each collected value into what
 * the document holding it says, {@link Mode#PARENTS_BY_VALUE}, so a brand
 * facet answers how many matching variants each brand has.
 *
 * A scope that nothing narrows is everything the reader holds, and says so
 * through {@link #whole()}: what each segment counts for it is as fixed as
 * the segment, so a count over it is kept per segment and answered from
 * there the next time the segment is walked - see {@link FacetStates}. Any
 * narrower scope is counted whole every time it is walked.
 *
 * @param hits
 *   what matched, collected over the scope of the facet, one entry per
 *   segment that holds a match
 * @param parents
 *   finds the documents of the index among the values of object fields, for
 *   the modes that walk between a value and its document - {@code null} for
 *   the modes that read each match on its own
 * @param mode
 *   what the matches are and what the counts should be of
 * @param whole
 *   what everything the reader holds the matches are, where the scope is
 *   nothing narrower than that - {@code null} for any scope that is
 */
public record FacetMatches(
	List<FacetsCollector.MatchingDocs> hits,
	BitSetProducer parents,
	Mode mode,
	Whole whole
) {
	/**
	 * What the collected matches are, and what a count of them answers.
	 */
	public enum Mode {
		/**
		 * The matches are documents of the index and each is one count, read
		 * off the match itself.
		 */
		DOCUMENTS,

		/**
		 * The matches are documents of the index and each is one count, but
		 * the field counted is inside an object, so a document counts once for
		 * each value any of its values holds. What rolling up every value a
		 * document holds comes to, collected as the documents rather than the
		 * values.
		 */
		EVERY_VALUE,

		/**
		 * The matches are values of an object field and the counts are of the
		 * documents holding them, so a value counts the first time one of a
		 * document's values holds it and never again.
		 */
		ROLLED_UP,

		/**
		 * The matches are values of an object field and so are the counts:
		 * each value is one count, read off the value itself.
		 */
		VALUES,

		/**
		 * The matches are values of an object field but the field being
		 * counted is one of the index, so each value counts into what the
		 * document holding it says there.
		 */
		PARENTS_BY_VALUE
	}

	/**
	 * A scope that is everything the reader holds, and of what: every
	 * document of the index, or every value of one object field.
	 *
	 * @param path
	 *   the object field whose every value the matches are, or {@code null}
	 *   where they are every document of the index
	 */
	public record Whole(String path) {
	}

	/**
	 * Get whether the matches are values of an object field whose documents
	 * have to be found above them - what forces one walk of the matches to
	 * feed every facet of the scope, see {@link FacetWalk}.
	 *
	 * @return
	 */
	public boolean resolvesDocuments() {
		return mode == Mode.ROLLED_UP || mode == Mode.PARENTS_BY_VALUE;
	}

	/**
	 * Mark the matches as every document the reader holds, so that what each
	 * segment counts for them is kept per segment.
	 *
	 * @return
	 */
	public FacetMatches wholeDocuments() {
		return new FacetMatches(hits, parents, mode, new Whole(null));
	}

	/**
	 * Mark the matches as every value of the given object field the reader
	 * holds, so that what each segment counts for them is kept per segment.
	 *
	 * @param path
	 *   the object field
	 * @return
	 */
	public FacetMatches wholeValues(String path) {
		return new FacetMatches(hits, parents, mode, new Whole(path));
	}

	/**
	 * Matches that are documents of the index, counted as they come.
	 *
	 * @param hits
	 * @return
	 */
	public static FacetMatches of(List<FacetsCollector.MatchingDocs> hits) {
		return new FacetMatches(hits, null, Mode.DOCUMENTS, null);
	}

	/**
	 * Matches that are documents of the index, counted once per value any of
	 * their values of an object field holds.
	 *
	 * @param hits
	 * @param parents
	 * @return
	 */
	public static FacetMatches everyValue(
		List<FacetsCollector.MatchingDocs> hits,
		BitSetProducer parents
	) {
		return new FacetMatches(hits, parents, Mode.EVERY_VALUE, null);
	}

	/**
	 * Matches that are values of an object field, counted as the documents
	 * holding them.
	 *
	 * @param hits
	 * @param parents
	 * @return
	 */
	public static FacetMatches rolledUp(
		List<FacetsCollector.MatchingDocs> hits,
		BitSetProducer parents
	) {
		return new FacetMatches(hits, parents, Mode.ROLLED_UP, null);
	}

	/**
	 * Matches that are values of an object field, counted as they come.
	 *
	 * @param hits
	 * @return
	 */
	public static FacetMatches values(List<FacetsCollector.MatchingDocs> hits) {
		return new FacetMatches(hits, null, Mode.VALUES, null);
	}

	/**
	 * Matches that are values of an object field, counted into what the
	 * document holding each one says in a field of the index.
	 *
	 * @param hits
	 * @param parents
	 * @return
	 */
	public static FacetMatches parentsByValue(
		List<FacetsCollector.MatchingDocs> hits,
		BitSetProducer parents
	) {
		return new FacetMatches(hits, parents, Mode.PARENTS_BY_VALUE, null);
	}
}
