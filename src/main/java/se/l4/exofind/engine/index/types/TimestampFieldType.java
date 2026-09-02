package se.l4.exofind.engine.index.types;

import java.time.format.DateTimeParseException;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DoubleValuesSource;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
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
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.query.DecaySignal;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.RankingSignal;
import se.l4.exofind.engine.query.matchers.AnyMatcher;
import se.l4.exofind.engine.query.matchers.EqualsMatcher;
import se.l4.exofind.engine.query.matchers.InMatcher;
import se.l4.exofind.engine.query.matchers.Matcher;
import se.l4.exofind.engine.query.matchers.RangeMatcher;
import se.l4.exofind.engine.query.matchers.RangesMatcher;

/**
 * A field holding a point in time.
 *
 * A value is an ISO 8601 date and time with an offset - {@code Z} or one like
 * {@code +02:00} - and everything that compares values compares the instant
 * they name, at millisecond precision: the offset says where the clock was
 * read, not what the value is, so {@code 12:00+02:00} and {@code 10:00Z} are
 * the same value. Requiring the offset is what keeps that well defined -
 * without one the instant would depend on whichever zone some node assumed.
 *
 * The string as it was given is what is kept and returned, so what a caller
 * reads back is what they wrote, offset and all. Anything past the
 * millisecond takes part in nothing - two values within the same millisecond
 * filter and order as the same instant.
 */
public class TimestampFieldType implements FieldType {
	private static final ErrorType COLLATION_NOT_SUPPORTED = ErrorType
		.withCode("index:field:sort:collation_not_supported")
		.withMessage("Collation means nothing when sorting a timestamp field");

	private static final ErrorType INVALID_VALUE = ErrorType
		.withCode("index:update:timestamp:invalid_value")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` holds a timestamp, which has to be an ISO 8601 date and time with an offset, such as `2024-05-01T12:00:00Z`"
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

		return errors;
	}

	@Override
	public Iterable<? extends IndexableField> createFields(
		IndexEncounter encounter,
		Object value0
	) {
		var millis = value0 instanceof String s ? parse(s) : null;
		if(millis == null) {
			throw new ValidationException(
				INVALID_VALUE.toMessage(
					ObjectLocation.root().forField(encounter.getFieldName()),
					"name", encounter.getFieldName()
				)
			);
		}

		var results = Lists.mutable.<IndexableField>empty();

		if(encounter.isFiltered()) {
			results.add(new LongPoint(encounter.name(FieldNames.FILTER), millis));
		}

		if(encounter.isStored()) {
			// The value as it was given, not the instant it was read as
			results.add(new StoredField(encounter.name(FieldNames.STORED), (String) value0));
		}

		if(encounter.isSorted()) {
			results.add(new NumericDocValuesField(encounter.name(FieldNames.SORT), millis));
		}

		if(encounter.isStoreDocValues()) {
			results.add(
				new SortedNumericDocValuesField(encounter.name(FieldNames.VALUES), millis)
			);
		}

		return results;
	}

	@Override
	public Query createQuery(IndexEncounter encounter, Matcher matcher) {
		if(matcher instanceof EqualsMatcher m) {
			return LongPoint.newExactQuery(filterName(encounter), queryValue(encounter, m.value()));
		}

		if(matcher instanceof InMatcher m) {
			var values = new long[m.values().size()];
			var i = 0;
			for(var value : m.values()) {
				values[i++] = queryValue(encounter, value);
			}

			return LongPoint.newSetQuery(filterName(encounter), values);
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
			// The whole range of the type, which is every document with a value
			return LongPoint.newRangeQuery(filterName(encounter), Long.MIN_VALUE, Long.MAX_VALUE);
		}

		throw new IndexInvalidQueryTypeException("timestamp", matcher.id());
	}

	private Query rangeQuery(IndexEncounter encounter, RangeMatcher m) {
		var low = m.lower() == null ? Long.MIN_VALUE : queryValue(encounter, m.lower());
		if(m.lower() != null && !m.lowerInclusive()) {
			if(low == Long.MAX_VALUE) {
				return new MatchNoDocsQuery();
			}
			low++;
		}

		var high = m.upper() == null ? Long.MAX_VALUE : queryValue(encounter, m.upper());
		if(m.upper() != null && !m.upperInclusive()) {
			if(high == Long.MIN_VALUE) {
				return new MatchNoDocsQuery();
			}
			high--;
		}

		return LongPoint.newRangeQuery(filterName(encounter), low, high);
	}

	@Override
	public FacetCounter createFacetCounter(IndexEncounter encounter) {
		/*
		 * Counting compares the instant a value names, like everything else on
		 * this type, so values that only differ in offset count as one - and
		 * the value read back is that instant in UTC, not any one of the
		 * strings it was given as.
		 */
		return FacetCounter.overLongs(
			encounter.name(FieldNames.VALUES),
			millis -> Instant.ofEpochMilli(millis).toString()
		);
	}

	@Override
	public RangeFacetCounter createRangeFacetCounter(
		IndexEncounter encounter,
		ListIterable<Facet.Range> ranges
	) {
		// A bound is an instant like everything else compared on this type
		return RangeFacetCounter.overLongs(
			encounter.name(FieldNames.VALUES),
			ranges,
			bound -> queryValue(encounter, bound)
		);
	}

	@Override
	public SortField createSortField(IndexEncounter encounter, boolean ascending) {
		// Lucene takes whether to reverse, which is the opposite of ascending
		var field = encounter.isFiltered()
			? new NumberSortField(
				encounter.name(FieldNames.SORT),
				encounter.name(FieldNames.FILTER),
				SortField.Type.LONG,
				!ascending
			)
			: new SortField(
				encounter.name(FieldNames.SORT),
				SortField.Type.LONG,
				!ascending
			);

		/*
		 * A numeric sort reads a document without a value as zero, which is
		 * 1970 here. Missing first and last are the two ends of time instead.
		 */
		field.setMissingValue(
			encounter.getSortConfig().getMissing() == SortConfig.Missing.MISSING_FIRST
				? Long.MIN_VALUE
				: Long.MAX_VALUE
		);

		return field;
	}

	/**
	 * What a ranking reads from an instant is how long ago it was, so decay is
	 * the shape that means anything here. How far the instant is above a pivot
	 * is a number about the epoch rather than about the document.
	 */
	@Override
	public boolean isRankingSupported(RankingSignal signal) {
		return signal instanceof DecaySignal;
	}

	@Override
	public DoubleValuesSource createRankingSource(IndexEncounter encounter) {
		if(!encounter.isSorted()) {
			throw new IndexFieldUsageException(encounter.getFieldName(), "sort");
		}

		// The doc values hold the instant as milliseconds since the epoch
		return DoubleValuesSource.fromLongField(encounter.name(FieldNames.SORT));
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
	 * Get a value from a query as the instant this type compares by.
	 *
	 * @param encounter
	 * @param value
	 * @return
	 */
	private static long queryValue(IndexEncounter encounter, Object value) {
		var millis = value instanceof String s ? parse(s) : null;
		if(millis == null) {
			throw new IndexInvalidQueryValueException(encounter.getFieldName(), "timestamp");
		}

		return millis;
	}

	/**
	 * Read a value as the milliseconds since the epoch of the instant it
	 * names, or {@code null} when it is not a timestamp - including one
	 * without an offset, which names no instant at all.
	 *
	 * @param value
	 * @return
	 */
	private static Long parse(String value) {
		try {
			return OffsetDateTime.parse(value).toInstant().toEpochMilli();
		} catch(DateTimeParseException | ArithmeticException e) {
			// Arithmetic overflow is a year the millisecond count can not hold
			return null;
		}
	}
}
