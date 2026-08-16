package se.l4.exofind.engine.index.types;

import java.util.Locale;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DoubleValuesSource;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.index.FacetCounter;
import se.l4.exofind.engine.index.HierarchyFacetCounter;
import se.l4.exofind.engine.index.IndexEncounter;
import se.l4.exofind.engine.index.IndexFieldUsageException;
import se.l4.exofind.engine.index.IndexInvalidQueryTypeException;
import se.l4.exofind.engine.index.RangeFacetCounter;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.RankingSignal;
import se.l4.exofind.engine.query.matchers.Matcher;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Type definition for fields that can be used in indexes.
 *
 * Implementations are stateless and shared, all of the configuration for a
 * single field arrives via the {@link IndexEncounter} passed to each method.
 * {@link FieldTypes} is where an implementation is looked up from a
 * {@link FieldTypeDef}.
 */
public interface FieldType {
	/**
	 * Get if sorting is supported on fields of this type.
	 *
	 * @return
	 */
	boolean isSortingSupported();

	/**
	 * Get if {@link DocValues} are supported for fields of this type.
	 *
	 * @return
	 */
	boolean isDocValuesSupported();

	/**
	 * Get if a field of this type can be the primary key of an index. Types
	 * that do not identify a document on their own, such as booleans, can not.
	 *
	 * @return
	 */
	default boolean isPrimaryKeySupported() {
		return false;
	}

	/**
	 * Get if a field can take part in a search for text somebody typed.
	 *
	 * Unlike the capabilities above this one depends on the field rather than
	 * on the type alone, as matching is something a definition turns on. It is
	 * what decides which fields a search covers when it does not name any.
	 *
	 * @param encounter
	 * @return
	 */
	default boolean isTextSearchable(IndexEncounter encounter) {
		return false;
	}

	/**
	 * Get if a field can answer for its words in the order they were typed.
	 *
	 * Narrower than {@link #isTextSearchable} because a field written only for
	 * autocomplete completes text but holds every prefix of a word at one
	 * position, so order means nothing in it. It is what decides which fields
	 * a phrase search covers when it does not name any.
	 *
	 * @param encounter
	 * @return
	 */
	default boolean isPhraseSearchable(IndexEncounter encounter) {
		return false;
	}

	/**
	 * Get how much a hit in this field counts relative to hits in other fields
	 * when text is searched across several. This is the weight the definition
	 * declared, used whenever a search does not give one of its own.
	 *
	 * @param encounter
	 * @return
	 */
	default float getTextWeight(IndexEncounter encounter) {
		return 1f;
	}

	/**
	 * Check the parts of a field definition that only this type can judge, such
	 * as whether an option on one of its usages means anything for it.
	 *
	 * Rules that hold for every type, such as a sortable field not being able
	 * to hold several values, are checked by
	 * {@link se.l4.exofind.engine.index.schema.Field} instead.
	 *
	 * @param location
	 *   where the field is, used to point at the error
	 * @param def
	 *   the field being validated
	 * @param resources
	 *   what the index shares between fields, for checking that what the
	 *   field refers to by name exists
	 * @return
	 *   the problems found, empty if the definition is valid
	 */
	default ListIterable<ErrorMessage> validate(
		ObjectLocation location,
		FieldDef def,
		ResourcesDef resources
	) {
		return Lists.immutable.<ErrorMessage>empty().toList();
	}

	Iterable<? extends IndexableField> createFields(IndexEncounter encounter, Object value);

	/**
	 * Read a value back out of a stored field, in the shape it was given in.
	 *
	 * How a value is stored is up to the type - a boolean is a byte on disk -
	 * so reading it back has to be as well, or results would come back looking
	 * nothing like the documents that were indexed.
	 *
	 * @param encounter
	 * @param field
	 * @return
	 */
	default Object readStored(IndexEncounter encounter, IndexableField field) {
		return field.stringValue();
	}

	/**
	 * Create the term that identifies a document by the value of its primary
	 * key. Only called for types where {@link #isPrimaryKeySupported()} is
	 * {@code true}.
	 *
	 * @param encounter
	 * @param value
	 * @return
	 */
	default Term createPrimaryKeyTerm(IndexEncounter encounter, Object value) {
		throw new UnsupportedOperationException(
			"This field type can not be used as a primary key"
		);
	}

	/**
	 * Read a primary key that arrived as text into the value this type holds -
	 * what a key taken from a URL has to go through, having no JSON type to
	 * carry it. Only called for types where {@link #isPrimaryKeySupported()} is
	 * {@code true}.
	 *
	 * Text that is not a value of this type is returned as it came, so that
	 * {@link #createPrimaryKeyTerm(IndexEncounter, Object)} refuses it the same
	 * way it refuses a value of the wrong type from anywhere else.
	 *
	 * @param text
	 * @return
	 */
	default Object primaryKeyFromText(String text) {
		return text;
	}

	/**
	 * Get the name of the Lucene field a highlight of this field reads its
	 * matches from, in the locale the encounter resolved.
	 *
	 * Highlighting reads back what the field's text search matched, so it is
	 * only answered for the usage that search targets, and only where the
	 * definition declared it highlightable.
	 *
	 * @param encounter
	 * @return
	 * @throws IndexFieldUsageException
	 *   if the field was not defined for highlighting
	 */
	default String getHighlightFieldName(IndexEncounter encounter) {
		throw new IndexFieldUsageException(encounter.getFieldName(), "highlight");
	}

	/**
	 * Create a query for the given field definition and matcher.
	 *
	 * @param encounter
	 * @param matcher
	 * @return
	 */
	Query createQuery(IndexEncounter encounter, Matcher matcher);

	/**
	 * Create one query per word of the given text, in the order the words were
	 * typed, for combining across fields word by word.
	 *
	 * <p>Each query matches what {@link #createQuery} would have asked of the
	 * same word alone, including how the word still being typed and typing
	 * mistakes are treated. An empty list means nothing survived analysis,
	 * such as a text of only stopwords. {@code null} means the type cannot
	 * answer per word, and the caller falls back to {@link #createQuery} for
	 * the whole text.
	 *
	 * @param encounter
	 * @param matcher
	 * @return
	 * @throws IndexFieldUsageException
	 *   if the field was not defined for matching, the same way
	 *   {@link #createQuery} refuses the matcher
	 */
	default ListIterable<Query> createTextTermQueries(
		IndexEncounter encounter,
		TextMatcher matcher
	) {
		return null;
	}

	/**
	 * Create the query that lifts a document whose value for this field is,
	 * whole, the text that was typed - already carrying what the definition
	 * says that counts.
	 *
	 * <p>Only asked of a field that answered {@link #createTextTermQueries},
	 * because that is the one path where the whole value has nowhere to go:
	 * the words of the field are combined with the words of the others, and a
	 * value taken whole is no single word. Everywhere else the query
	 * {@link #createQuery} builds carries it already.
	 *
	 * @param encounter
	 * @param matcher
	 * @return
	 *   the query, or {@code null} where the field was not defined to rank a
	 *   whole-value match above a mention
	 */
	default Query createTextExactQuery(IndexEncounter encounter, TextMatcher matcher) {
		return null;
	}

	/**
	 * Create the counter that counts search matches per value of this field,
	 * reading the doc values the field wrote for faceting.
	 *
	 * The counter is what decides how a raw counted value reads back as one a
	 * caller recognises, the same way {@link #readStored} does for stored
	 * values. A type that can not be counted per value refuses the same way it
	 * refuses a matcher that means nothing for it.
	 *
	 * @param encounter
	 * @return
	 * @throws IndexFieldUsageException
	 *   if fields of this type can not be counted per value
	 */
	default FacetCounter createFacetCounter(IndexEncounter encounter) {
		throw new IndexFieldUsageException(encounter.getFieldName(), "facet");
	}

	/**
	 * Create the counter that counts search matches into the given buckets,
	 * reading the same doc values {@link #createFacetCounter} counts per
	 * value.
	 *
	 * What a bound means is the type's to decide - an instant for a
	 * timestamp, a number of the field's width for a number. A type whose
	 * values have no order to bucket refuses the same way it refuses a
	 * matcher that means nothing for it.
	 *
	 * @param encounter
	 * @param ranges
	 *   the buckets to count into
	 * @return
	 * @throws IndexInvalidQueryTypeException
	 *   if values of this type can not be counted into ranges
	 */
	default RangeFacetCounter createRangeFacetCounter(
		IndexEncounter encounter,
		ListIterable<Facet.Range> ranges
	) {
		throw new IndexInvalidQueryTypeException(
			encounter.getFieldType().getTypeCase().name().toLowerCase(Locale.ROOT),
			"ranges"
		);
	}

	/**
	 * Get whether values of this field are read as paths through a tree, which
	 * is what makes a facet on it count a level at a time.
	 *
	 * A property of how the field was defined rather than of the type, as the
	 * same type holds paths in one field and plain values in the next.
	 *
	 * @param encounter
	 * @return
	 */
	default boolean isHierarchical(IndexEncounter encounter) {
		return false;
	}

	/**
	 * Create the counter that counts search matches per level of this field,
	 * reading the levels the field wrote for a value that is a path.
	 *
	 * The counter is what knows how a path is taken apart - what separates its
	 * levels and how two of them are compared - so the counting itself never
	 * has to. A field that was not defined to hold paths refuses the same way
	 * one that was never defined for faceting does.
	 *
	 * @param encounter
	 * @return
	 * @throws IndexFieldUsageException
	 *   if the field does not hold values that are paths
	 */
	default HierarchyFacetCounter createHierarchyFacetCounter(IndexEncounter encounter) {
		throw new IndexFieldUsageException(encounter.getFieldName(), "hierarchy");
	}

	/**
	 * Create a nearest-neighbour query for the field.
	 *
	 * This is not a {@link Matcher} because its pre-filter arrives already
	 * compiled - the clauses it is built from need the query compiler, which a
	 * matcher has no channel for. A type that has no notion of nearness refuses
	 * it the same way it refuses a matcher that means nothing for it.
	 *
	 * @param encounter
	 * @param vector
	 *   the vector to find the neighbours of
	 * @param k
	 *   how many neighbours to return
	 * @param filter
	 *   which documents may be neighbours, {@code null} for all of them
	 * @return
	 */
	default Query createKnnQuery(
		IndexEncounter encounter,
		float[] vector,
		int k,
		Query filter
	) {
		throw new IndexInvalidQueryTypeException(
			encounter.getFieldType().getTypeCase().name().toLowerCase(Locale.ROOT),
			"knn"
		);
	}

	default SortField createSortField(IndexEncounter encounter, boolean ascending) {
		throw new UnsupportedOperationException("Sorting is not supported for this field type");
	}

	/**
	 * Get if a ranking signal of the given shape means anything for this type.
	 *
	 * Shapes are as particular about the type as matchers are: how far a value
	 * is above a pivot says something about a count and nothing about an
	 * instant, whose age is what a ranking reads instead. A type answering
	 * {@code false} refuses the signal the same way it refuses a matcher that
	 * means nothing for it.
	 *
	 * @param signal
	 * @return
	 */
	default boolean isRankingSupported(RankingSignal signal) {
		return false;
	}

	/**
	 * Create the source that reads the value a ranking signal shapes, from the
	 * doc values the field wrote for sorting.
	 *
	 * Only ever called for a signal {@link #isRankingSupported} accepted, so
	 * what is left to refuse here is a field that was never written for
	 * sorting and therefore has no value to read.
	 *
	 * @param encounter
	 * @return
	 * @throws IndexFieldUsageException
	 *   if the field was not defined for sorting
	 */
	default DoubleValuesSource createRankingSource(IndexEncounter encounter) {
		throw new IndexFieldUsageException(encounter.getFieldName(), "sort");
	}

	/**
	 * Create a sort by how far the value of the field is from an origin,
	 * nearest first.
	 *
	 * Separate from {@link #createSortField} because it carries an origin,
	 * which ordering by a value has no place for. A type with no notion of
	 * place refuses it the same way it refuses a matcher that means nothing
	 * for it.
	 *
	 * @param encounter
	 * @param latitude
	 *   degrees north of the equator the origin sits at
	 * @param longitude
	 *   degrees east of the prime meridian the origin sits at
	 * @return
	 */
	default SortField createDistanceSortField(
		IndexEncounter encounter,
		double latitude,
		double longitude
	) {
		throw new IndexInvalidQueryTypeException(
			encounter.getFieldType().getTypeCase().name().toLowerCase(Locale.ROOT),
			"distance"
		);
	}
}
