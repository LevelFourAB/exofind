package se.l4.exofind.engine.index;

import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.search.join.BitSetProducer;

/**
 * The matches one facet counts, and what has to happen to them before they are
 * counts of documents.
 *
 * A facet over a field of the index counts documents that matched, and each of
 * them is one match. A facet over a field inside an object counts the values of
 * that object instead - Lucene documents of their own, several of which can
 * belong to the same document of the index - so counting them as they come
 * would answer how many variants are red rather than how many products have
 * one. {@link #parents()} is what says which of the two this is: present, it is
 * also what a value is rolled up through, so a document holding three red
 * variants counts once.
 *
 * @param hits
 *   what matched, collected over the scope of the facet
 * @param parents
 *   finds the documents of the index among the values of object fields, or
 *   {@code null} when the matches are documents already
 */
public record FacetMatches(
	FacetsCollector hits,
	BitSetProducer parents
) {
	/**
	 * Get if the matches are values of an object field, which have to be rolled
	 * up into the documents holding them.
	 *
	 * @return
	 */
	public boolean isNested() {
		return parents != null;
	}

	/**
	 * Matches that are documents of the index, counted as they come.
	 *
	 * @param hits
	 * @return
	 */
	public static FacetMatches of(FacetsCollector hits) {
		return new FacetMatches(hits, null);
	}
}
