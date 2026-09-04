package se.l4.exofind.engine.index;

import java.util.function.Function;
import java.util.function.LongFunction;

import org.apache.lucene.analysis.Analyzer;

import se.l4.exofind.engine.query.Facet;

/**
 * Counts the matches of a search per value of one field, reading the doc
 * values the field was written under {@link FieldNames#VALUES}.
 *
 * A counter is created by the type of the field through
 * {@link se.l4.exofind.engine.index.types.FieldType#createFacetCounter}, which
 * is what decides how the raw doc values read back as values a caller
 * recognises - a boolean field counts {@code T} and {@code F} but answers
 * {@code true} and {@code false}. The factories here choose the counting -
 * {@link StringFacetCount} or {@link LongFacetCount} - so a type only says
 * which field to read and how to decode one value.
 *
 * A counter answers a {@link FacetCount}, fed by the shared walk of the
 * facet's scope - see {@link FacetWalk}. What the matches are and what one
 * count means is decided by the mode of that scope, so a type stays out of it.
 */
public interface FacetCounter {
	/**
	 * Prepare to count one scope.
	 *
	 * @param scope
	 *   what the matches of the scope are and what the counts should be of
	 * @param limit
	 *   how many values to bring back at most
	 * @param order
	 *   the order values come back in
	 * @param prefix
	 *   what the answered values have to start with, or {@code null} to
	 *   answer every value - see {@link Facet#prefix()}
	 * @return
	 *   the count to feed through {@link FacetWalk}, never {@code null}
	 */
	FacetCount prepare(FacetMatches scope, int limit, Facet.Order order, String prefix);

	/**
	 * Count a field written as sorted set doc values, decoding each counted
	 * term through the given function.
	 *
	 * @param field
	 *   the Lucene field the values were written under
	 * @param decode
	 *   how a counted term reads back as a value
	 * @param normalizer
	 *   the analyzer whose {@link Analyzer#normalize(String, String)} folds a
	 *   term and a prefix before they are compared, or {@code null} to compare
	 *   a prefix with the decoded value ignoring case
	 * @return
	 */
	static FacetCounter overStrings(
		String field,
		Function<String, Object> decode,
		Analyzer normalizer
	) {
		return (scope, limit, order, prefix) ->
			new StringFacetCount(field, scope, limit, order, decode, normalizer, prefix);
	}

	/**
	 * Count a field written as sorted numeric doc values, decoding each
	 * counted number through the given function.
	 *
	 * @param field
	 *   the Lucene field the values were written under
	 * @param decode
	 *   how a counted number reads back as a value
	 * @return
	 */
	static FacetCounter overLongs(String field, LongFunction<Object> decode) {
		return (scope, limit, order, prefix) ->
			new LongFacetCount(field, scope, limit, order, decode, prefix);
	}
}
