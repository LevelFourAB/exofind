package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * A request for what to search for, from the text typed so far.
 *
 * The answer is picked from the values of the fields the search settings of
 * the index opt in, see {@code SuggestFields}: every value that starts with
 * the text, counted under the filters, the most common first. The text and
 * the values are compared folded, in case and Unicode form, by the normalize
 * step of the autocomplete chain of each field, the way a facet prefix is.
 *
 * With {@link #typos()} on, a text that finds fewer values than the limit is
 * also looked up one mistake away, and the values found that way come after
 * the ones the text starts. A text shorter than {@link #MIN_LENGTH_TYPOS}
 * code points is never looked up that way.
 *
 * @param text
 *   what has been typed so far. Blank answers the most common values
 * @param locale
 *   BCP-47 tag of the locale the values are read and labelled in, or
 *   {@code null} for the default locale of each field
 * @param filters
 *   clauses that a counted document must satisfy, in the shape
 *   {@link SearchRequest#filters()} takes them. A filter on a suggested
 *   field is left out of that field's own counts, the way a facet leaves
 *   its own filter out
 * @param limit
 *   how many suggestions to bring back at most, between 1 and
 *   {@link #MAX_LIMIT}
 * @param typos
 *   whether a value one mistake away from the text may be suggested when
 *   fewer values than the limit start with it
 */
public record SuggestRequest(
	String text,
	String locale,
	ImmutableList<Query> filters,
	int limit,
	boolean typos
) {
	/**
	 * How many suggestions a request brings back when nothing else is asked
	 * for.
	 */
	public static final int DEFAULT_LIMIT = 5;

	/**
	 * The most suggestions a request may bring back. Every suggested field
	 * keeps a candidate set of this size, so the cap is what keeps one
	 * request from asking for every value of every field.
	 */
	public static final int MAX_LIMIT = 100;

	/**
	 * The shortest text, in code points, that is looked up one mistake away.
	 * A shorter text is near too many values for a suggestion to mean much.
	 */
	public static final int MIN_LENGTH_TYPOS = 5;

	public SuggestRequest {
		if(text == null) {
			text = "";
		}

		text = text.strip();

		if(filters == null) {
			filters = Lists.immutable.empty();
		}

		if(limit < 1 || limit > MAX_LIMIT) {
			throw new IllegalArgumentException(
				"A suggest request brings back between 1 and " + MAX_LIMIT + " suggestions"
			);
		}
	}

	/**
	 * Suggest from the given text with the defaults: no filters, the default
	 * limit and typos on.
	 *
	 * @param text
	 * @return
	 */
	public static SuggestRequest of(String text) {
		return new SuggestRequest(text, null, null, DEFAULT_LIMIT, true);
	}

	/**
	 * Read and label the values in the given locale.
	 *
	 * @param locale
	 * @return
	 */
	public SuggestRequest withLocale(String locale) {
		return new SuggestRequest(text, locale, filters, limit, typos);
	}

	/**
	 * Count under the given filters, replacing any set before.
	 *
	 * @param filters
	 * @return
	 */
	public SuggestRequest withFilters(Query... filters) {
		return new SuggestRequest(text, locale, Lists.immutable.of(filters), limit, typos);
	}

	/**
	 * Count under the given filters, replacing any set before.
	 *
	 * @param filters
	 * @return
	 */
	public SuggestRequest withFilters(Iterable<? extends Query> filters) {
		return new SuggestRequest(text, locale, Lists.immutable.ofAll(filters), limit, typos);
	}

	/**
	 * Set how many suggestions to bring back at most.
	 *
	 * @param limit
	 * @return
	 */
	public SuggestRequest withLimit(int limit) {
		return new SuggestRequest(text, locale, filters, limit, typos);
	}

	/**
	 * Set whether a value one mistake away may be suggested.
	 *
	 * @param typos
	 * @return
	 */
	public SuggestRequest withTypos(boolean typos) {
		return new SuggestRequest(text, locale, filters, limit, typos);
	}
}
