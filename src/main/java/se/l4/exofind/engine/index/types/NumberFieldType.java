package se.l4.exofind.engine.index.types;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DoubleValuesSource;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.BytesRef;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.FacetCounter;
import se.l4.exofind.engine.index.FieldNames;
import se.l4.exofind.engine.index.IndexEncounter;
import se.l4.exofind.engine.index.IndexFieldUsageException;
import se.l4.exofind.engine.index.IndexInvalidQueryTypeException;
import se.l4.exofind.engine.index.IndexInvalidQueryValueException;
import se.l4.exofind.engine.index.NumberSortField;
import se.l4.exofind.engine.index.RangeFacetCounter;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.RankingSignal;
import se.l4.exofind.engine.query.SaturationSignal;
import se.l4.exofind.engine.query.matchers.AnyMatcher;
import se.l4.exofind.engine.query.matchers.EqualsMatcher;
import se.l4.exofind.engine.query.matchers.InMatcher;
import se.l4.exofind.engine.query.matchers.Matcher;
import se.l4.exofind.engine.query.matchers.RangeMatcher;
import se.l4.exofind.engine.query.matchers.RangesMatcher;

/**
 * Base for the fields that hold a number.
 *
 * Numbers are indexed as points rather than as one term per value, which is
 * what makes {@code filter} on a number mean range as well as equality - the
 * {@code range} matcher walks the point tree instead of every term between the
 * bounds. Each width of number is its own type in the schema, so what a field
 * can hold is part of its definition rather than discovered from the values;
 * the subclasses only say how their width reaches Lucene, everything a matcher
 * or a bound means is decided here.
 *
 * A value is taken as it can be read without changing it: an integer field
 * takes any integral number that fits its width, a floating point field also
 * narrows - the field is declared to hold that precision, so giving it more is
 * not a mistake the way giving an integer field a fraction is. Nothing takes
 * NaN or an infinity; neither can be ordered against the values around it, and
 * neither can cross a JSON API.
 */
public abstract class NumberFieldType implements FieldType {
	private static final ErrorType COLLATION_NOT_SUPPORTED = ErrorType
		.withCode("index:field:sort:collation_not_supported")
		.withMessage("Collation means nothing when sorting a number field");

	private static final ErrorType INVALID_BOUND = ErrorType
		.withCode("index:field:number:invalid_bound")
		.withMessage("A validation bound has to be a finite number");

	private static final ErrorType INVALID_BOUNDS = ErrorType
		.withCode("index:field:number:invalid_bounds")
		.withMessage("The `min` of the validation can not be above its `max`");

	private static final ErrorType INVALID_VALUE = ErrorType
		.withCode("index:update:number:invalid_value")
		.withArguments("name", "type")
		.withMessage("Field `{{name}}` holds a `{{type}}`, which this value can not be read as");

	private static final ErrorType OUT_OF_BOUNDS = ErrorType
		.withCode("index:update:number:out_of_bounds")
		.withArguments("name", "value")
		.withMessage(
			"Field `{{name}}` was given `{{value}}`, which is outside the bounds its definition declares"
		);

	@Override
	public boolean isSortingSupported() {
		return true;
	}

	@Override
	public boolean isDocValuesSupported() {
		return true;
	}

	@Override
	public ListIterable<ErrorMessage> validate(
		ObjectLocation location,
		FieldDef def,
		ResourcesDef resources
	) {
		var errors = Lists.mutable.<ErrorMessage>empty();

		if(def.getSort().hasCollation()) {
			errors.add(COLLATION_NOT_SUPPORTED.toMessage(location));
		}

		var min = declaredMin(def.getType());
		var max = declaredMax(def.getType());

		var usable = true;
		if(min != null && !isUsableBound(min)) {
			errors.add(INVALID_BOUND.toMessage(location));
			usable = false;
		}
		if(max != null && !isUsableBound(max)) {
			errors.add(INVALID_BOUND.toMessage(location));
			usable = false;
		}

		if(usable && min != null && max != null && compare(min, max) > 0) {
			errors.add(INVALID_BOUNDS.toMessage(location));
		}

		return errors;
	}

	@Override
	public Iterable<? extends IndexableField> createFields(
		IndexEncounter encounter,
		Object value0
	) {
		var location = ObjectLocation.root().forField(encounter.getFieldName());

		var value = coerce(value0);
		if(value == null) {
			throw new ValidationException(
				INVALID_VALUE.toMessage(
					location,
					"name", encounter.getFieldName(),
					"type", typeName()
				)
			);
		}

		var type = encounter.getFieldType();
		var min = declaredMin(type);
		var max = declaredMax(type);
		if(min != null && compare(value, min) < 0 || max != null && compare(value, max) > 0) {
			throw new ValidationException(
				OUT_OF_BOUNDS.toMessage(
					location,
					"name", encounter.getFieldName(),
					"value", value
				)
			);
		}

		var results = Lists.mutable.<IndexableField>empty();

		if(encounter.isPrimaryKey()) {
			results.add(
				new StringField(
					encounter.name(FieldNames.PRIMARY_KEY),
					primaryKeyBytes(value),
					Field.Store.NO
				)
			);
		}

		if(encounter.isFiltered()) {
			results.add(pointField(encounter.name(FieldNames.FILTER), value));
		}

		if(encounter.isStored()) {
			results.add(storedField(encounter.name(FieldNames.STORED), value));
		}

		if(encounter.isSorted()) {
			results.add(sortDocValuesField(encounter.name(FieldNames.SORT), value));
		}

		if(encounter.isStoreDocValues()) {
			results.add(
				new SortedNumericDocValuesField(
					encounter.name(FieldNames.VALUES),
					sortableValue(value)
				)
			);
		}

		return results;
	}

	@Override
	public Object readStored(IndexEncounter encounter, IndexableField field) {
		return field.numericValue();
	}

	@Override
	public Query createQuery(IndexEncounter encounter, Matcher matcher) {
		if(matcher instanceof EqualsMatcher m) {
			return exactQuery(filterName(encounter), queryValue(encounter, m.value()));
		}

		if(matcher instanceof InMatcher m) {
			return setQuery(
				filterName(encounter),
				m.values().collect(v -> queryValue(encounter, v))
			);
		}

		if(matcher instanceof RangeMatcher m) {
			return rangeQuery(encounter, m);
		}

		if(matcher instanceof RangesMatcher m) {
			if(m.ranges().isEmpty()) {
				return new MatchNoDocsQuery();
			}

			var builder = new BooleanQuery.Builder();
			builder.setMinimumNumberShouldMatch(1);
			for(var range : m.ranges()) {
				builder.add(rangeQuery(encounter, range), BooleanClause.Occur.SHOULD);
			}

			return builder.build();
		}

		if(matcher instanceof AnyMatcher) {
			/*
			 * The whole range of the type, which is every document that has a
			 * value for it - nothing indexes NaN, so no value falls outside it.
			 */
			return rangeQuery(filterName(encounter), null, false, null, false);
		}

		throw new IndexInvalidQueryTypeException(typeName(), matcher.id());
	}

	private Query rangeQuery(IndexEncounter encounter, RangeMatcher m) {
		return rangeQuery(
			filterName(encounter),
			m.lower() == null ? null : queryValue(encounter, m.lower()),
			m.lowerInclusive(),
			m.upper() == null ? null : queryValue(encounter, m.upper()),
			m.upperInclusive()
		);
	}

	@Override
	public FacetCounter createFacetCounter(IndexEncounter encounter) {
		return FacetCounter.overLongs(encounter.name(FieldNames.VALUES), this::facetValue);
	}

	@Override
	public RangeFacetCounter createRangeFacetCounter(
		IndexEncounter encounter,
		ListIterable<Facet.Range> ranges
	) {
		/*
		 * A bound crosses into the encoding of the counted values, which keeps
		 * their order, so a bucket over the values is exactly a bucket over
		 * their encoding.
		 */
		return RangeFacetCounter.overLongs(
			encounter.name(FieldNames.VALUES),
			ranges,
			bound -> sortableValue(queryValue(encounter, bound))
		);
	}

	@Override
	public SortField createSortField(IndexEncounter encounter, boolean ascending) {
		// Lucene takes whether to reverse, which is the opposite of ascending
		var field = encounter.isFiltered()
			? new NumberSortField(
				encounter.name(FieldNames.SORT),
				encounter.name(FieldNames.FILTER),
				sortType(),
				!ascending
			)
			: new SortField(encounter.name(FieldNames.SORT), sortType(), !ascending);

		/*
		 * A numeric sort reads a document without a value as zero unless told
		 * otherwise, which would file it between the negative and positive
		 * values. Missing first and last are the two ends of the type instead.
		 */
		field.setMissingValue(
			encounter.getSortConfig().getMissing() == SortConfig.Missing.MISSING_FIRST
				? missingFirst()
				: missingLast()
		);

		return field;
	}

	/**
	 * A number is a count of something, so how far it is above a pivot is what
	 * a ranking reads from it. Age means nothing here - a number is not an
	 * instant, however much it looks like one.
	 */
	@Override
	public boolean isRankingSupported(RankingSignal signal) {
		return signal instanceof SaturationSignal;
	}

	@Override
	public DoubleValuesSource createRankingSource(IndexEncounter encounter) {
		if(!encounter.isSorted()) {
			throw new IndexFieldUsageException(encounter.getFieldName(), "sort");
		}

		/*
		 * The doc values of a sortable field hold the value in the width the
		 * type writes it, which is what decides how it reads back as a number.
		 */
		var name = encounter.name(FieldNames.SORT);
		return switch(sortType()) {
			case FLOAT -> DoubleValuesSource.fromFloatField(name);
			case DOUBLE -> DoubleValuesSource.fromDoubleField(name);
			default -> DoubleValuesSource.fromLongField(name);
		};
	}

	@Override
	public Term createPrimaryKeyTerm(IndexEncounter encounter, Object value) {
		return new Term(
			encounter.name(FieldNames.PRIMARY_KEY),
			primaryKeyBytes(queryValue(encounter, value))
		);
	}

	/**
	 * The types that can be a primary key hold whole numbers, so text is read
	 * as one and left to {@link #coerce(Object)} to fit into the width of this
	 * type.
	 */
	@Override
	public Object primaryKeyFromText(String text) {
		try {
			return Long.parseLong(text);
		} catch(NumberFormatException e) {
			return text;
		}
	}

	/**
	 * Get the name of the field values are looked up in, refusing fields that
	 * were never written for it.
	 *
	 * @param encounter
	 * @return
	 */
	private static String filterName(IndexEncounter encounter) {
		if(!encounter.isFiltered()) {
			throw new IndexFieldUsageException(encounter.getFieldName(), "filter");
		}

		return encounter.name(FieldNames.FILTER);
	}

	/**
	 * Get a value from a query as the number this type holds.
	 *
	 * @param encounter
	 * @param value
	 * @return
	 */
	protected Number queryValue(IndexEncounter encounter, Object value) {
		var coerced = coerce(value);
		if(coerced == null) {
			throw new IndexInvalidQueryValueException(encounter.getFieldName(), typeName());
		}

		return coerced;
	}

	/**
	 * Get the name this type goes by in messages, such as {@code int32}.
	 */
	protected abstract String typeName();

	/**
	 * Get the lowest value the definition allows, or {@code null} when it
	 * declares none.
	 */
	protected abstract Number declaredMin(FieldTypeDef type);

	/**
	 * Get the highest value the definition allows, or {@code null} when it
	 * declares none.
	 */
	protected abstract Number declaredMax(FieldTypeDef type);

	/**
	 * Get if a declared bound is one a value can be compared against. The
	 * floating point types refuse NaN and the infinities here.
	 */
	protected boolean isUsableBound(Number bound) {
		return true;
	}

	/**
	 * Compare two values of this type.
	 */
	protected abstract int compare(Number a, Number b);

	/**
	 * Get a value as the number this type holds, or {@code null} when it can
	 * not be read as one without changing it.
	 */
	protected abstract Number coerce(Object value);

	/**
	 * The field a value is filtered through, indexed as a point.
	 */
	protected abstract IndexableField pointField(String name, Number value);

	/**
	 * The field that keeps a value so it can be returned in results.
	 */
	protected abstract IndexableField storedField(String name, Number value);

	/**
	 * The doc values field results are ordered by.
	 */
	protected abstract IndexableField sortDocValuesField(String name, Number value);

	/**
	 * Encode a value as the long it is counted per value and into buckets
	 * through, in an encoding that keeps the order of the values.
	 * {@link #facetValue} undoes it.
	 */
	protected abstract long sortableValue(Number value);

	/**
	 * Read a counted value back as the number this type holds, undoing
	 * {@link #sortableValue}.
	 */
	protected abstract Number facetValue(long value);

	/**
	 * Match documents whose value is exactly the given one.
	 */
	protected abstract Query exactQuery(String name, Number value);

	/**
	 * Match documents whose value is any of the given ones.
	 */
	protected abstract Query setQuery(String name, ListIterable<Number> values);

	/**
	 * Match documents whose value falls between the bounds, either of which
	 * may be {@code null} for open.
	 */
	protected abstract Query rangeQuery(
		String name,
		Number lower,
		boolean lowerInclusive,
		Number upper,
		boolean upperInclusive
	);

	/**
	 * How Lucene reads the sort field of this type.
	 */
	protected abstract SortField.Type sortType();

	/**
	 * The value a document without one sorts as when missing values go first -
	 * the low end of the type.
	 */
	protected abstract Object missingFirst();

	/**
	 * The value a document without one sorts as when missing values go last -
	 * the high end of the type.
	 */
	protected abstract Object missingLast();

	/**
	 * Get a value as the bytes that identify a document by it. Only the
	 * integer types support being a primary key, so only they provide this.
	 */
	protected BytesRef primaryKeyBytes(Number value) {
		throw new UnsupportedOperationException(
			"This field type can not be used as a primary key"
		);
	}
}
