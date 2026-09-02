package se.l4.exofind.engine.index;

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
 * @param hits
 *   what matched, collected over the scope of the facet
 * @param parents
 *   finds the documents of the index among the values of object fields, for
 *   the modes that walk between a value and its document - {@code null} for
 *   the modes that read each match on its own
 * @param mode
 *   what the matches are and what the counts should be of
 */
public record FacetMatches(
	FacetsCollector hits,
	BitSetProducer parents,
	Mode mode
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
	 * Matches that are documents of the index, counted as they come.
	 *
	 * @param hits
	 * @return
	 */
	public static FacetMatches of(FacetsCollector hits) {
		return new FacetMatches(hits, null, Mode.DOCUMENTS);
	}

	/**
	 * Matches that are documents of the index, counted once per value any of
	 * their values of an object field holds.
	 *
	 * @param hits
	 * @param parents
	 * @return
	 */
	public static FacetMatches everyValue(FacetsCollector hits, BitSetProducer parents) {
		return new FacetMatches(hits, parents, Mode.EVERY_VALUE);
	}

	/**
	 * Matches that are values of an object field, counted as the documents
	 * holding them.
	 *
	 * @param hits
	 * @param parents
	 * @return
	 */
	public static FacetMatches rolledUp(FacetsCollector hits, BitSetProducer parents) {
		return new FacetMatches(hits, parents, Mode.ROLLED_UP);
	}

	/**
	 * Matches that are values of an object field, counted as they come.
	 *
	 * @param hits
	 * @return
	 */
	public static FacetMatches values(FacetsCollector hits) {
		return new FacetMatches(hits, null, Mode.VALUES);
	}

	/**
	 * Matches that are values of an object field, counted into what the
	 * document holding each one says in a field of the index.
	 *
	 * @param hits
	 * @param parents
	 * @return
	 */
	public static FacetMatches parentsByValue(FacetsCollector hits, BitSetProducer parents) {
		return new FacetMatches(hits, parents, Mode.PARENTS_BY_VALUE);
	}
}
