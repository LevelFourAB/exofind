package se.l4.exofind.engine.index;

/**
 * Suffixes used when a schema field is written into Lucene.
 *
 * A single field in the schema turns into several Lucene fields, one for every
 * way the field can be used. The suffix is what keeps them apart, see
 * {@link IndexEncounter#name(String)} for how a full name is built.
 *
 * Indexing and querying have to agree on these, so they are named here rather
 * than spelled out where they are used.
 */
public final class FieldNames {
	/**
	 * Holds the value exactly as it was given, for looking a document up by its
	 * primary key.
	 *
	 * Kept apart from {@link #FILTER} because filtering may normalize the value,
	 * and a primary key that stopped telling {@code ABC} and {@code abc} apart
	 * would merge two documents into one.
	 */
	public static final String PRIMARY_KEY = "pk";

	/**
	 * Holds the value as a single term, for narrowing results down to the
	 * documents that have it.
	 */
	public static final String FILTER = "filter";

	/**
	 * Holds the value analyzed into terms, for searching with a query.
	 */
	public static final String MATCHING = "matching";

	/**
	 * Holds the value analyzed for completing what a user has typed so far.
	 */
	public static final String AUTOCOMPLETE = "autocomplete";

	/**
	 * Holds the whole value as a single term, normalized the way {@link
	 * #MATCHING} normalizes one, so that a value a search matched whole can be
	 * told from one that merely holds the same words.
	 *
	 * Written only where the definition asked for it, as the term is worth
	 * nothing unless something reads it.
	 */
	public static final String MATCHING_EXACT = "matching_exact";

	/**
	 * The same whole-value term for {@link #AUTOCOMPLETE}. A field of its own
	 * rather than one shared with matching, because the two usages normalize
	 * through chains of their own and a term written by one would not be the
	 * term the other looks up.
	 */
	public static final String AUTOCOMPLETE_EXACT = "autocomplete_exact";

	/**
	 * Holds the value as it was given, so it can be returned in results.
	 */
	public static final String STORED = "stored";

	/**
	 * Holds the value as doc values, for ordering results by it.
	 */
	public static final String SORT = "sort";

	/**
	 * Holds the value as doc values, for counting and aggregating over it.
	 */
	public static final String VALUES = "values";

	/**
	 * Holds the value and every level above it, for a field whose values are
	 * paths through a tree.
	 *
	 * Written twice over, the way {@link #FILTER} and {@link #VALUES} are for
	 * the value as a whole: as terms normalized the way filtering normalizes
	 * one, so that narrowing to a subtree finds every path below it, and as
	 * doc values holding the levels as they were given, so that counting a
	 * level answers what a reader would recognise. Both under this one name,
	 * as neither structure can be mistaken for the other.
	 */
	public static final String HIERARCHY = "hierarchy";

	/**
	 * Holds the value as a KNN vector, for similarity search.
	 */
	public static final String VECTOR = "vector";

	/**
	 * Stands in for the locale of a value that is the same in every language.
	 */
	public static final String NO_LOCALE = "_";

	/**
	 * Holds the whole document as it was given, so it can be returned in
	 * results and indexed again after its definition has changed.
	 *
	 * This is a name of its own rather than a suffix, as it belongs to the
	 * document rather than to any one field of it. Every name
	 * {@link #name(String, String, String)} builds carries two separators, so
	 * no field of the schema can ever be written under this one.
	 */
	public static final String SOURCE = "_source";

	/**
	 * Marks a Lucene document that holds one value of an object field, carrying
	 * the name of that field. A document without it is a document of the index;
	 * one with it belongs to the document indexed right after it, and never
	 * comes back as a hit of its own.
	 *
	 * Like {@link #SOURCE} this is a name of its own rather than a suffix, and
	 * carries no separators, so no field of the schema can ever be written
	 * under it.
	 */
	public static final String NESTED = "_nested";

	private static final char SEPARATOR = ':';

	private FieldNames() {
	}

	/**
	 * Build the name a value is written under.
	 *
	 * Field names are checked to hold nothing but letters, numbers, underscores,
	 * dots and wildcards, so the separator can never turn up inside one and the
	 * name can always be taken apart again.
	 *
	 * @param field
	 *   name of the field in the schema
	 * @param locale
	 *   the locale the value is in, or {@code null} when it is the same in
	 *   every language
	 * @param suffix
	 *   what the value is written for, one of the constants here
	 * @return
	 */
	public static String name(String field, String locale, String suffix) {
		return field + SEPARATOR + (locale == null ? NO_LOCALE : locale) + SEPARATOR + suffix;
	}

	/**
	 * Take a name apart into the field, locale and usage it was built from.
	 *
	 * @param name
	 * @return
	 *   the parts, or {@code null} if the name was not built by
	 *   {@link #name(String, String, String)}
	 */
	public static Parsed parse(String name) {
		var field = name.indexOf(SEPARATOR);
		if(field < 0) {
			return null;
		}

		var locale = name.indexOf(SEPARATOR, field + 1);
		if(locale < 0) {
			return null;
		}

		var localeId = name.substring(field + 1, locale);

		return new Parsed(
			name.substring(0, field),
			NO_LOCALE.equals(localeId) ? null : localeId,
			name.substring(locale + 1)
		);
	}

	/**
	 * The parts of a name that a value was written under.
	 *
	 * @param field
	 *   name of the field in the schema
	 * @param locale
	 *   the locale the value is in, or {@code null} when it is the same in
	 *   every language
	 * @param suffix
	 *   what the value was written for
	 */
	public record Parsed(
		String field,
		String locale,
		String suffix
	) {
	}
}
