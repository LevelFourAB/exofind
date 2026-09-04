package se.l4.exofind.engine.index.types;

import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.BytesRef;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.index.schema.FieldTypeDef;

/**
 * A field holding a 64 bit signed integer.
 */
public class Int64FieldType extends NumberFieldType {
	@Override
	public boolean isPrimaryKeySupported() {
		return true;
	}

	@Override
	protected String typeName() {
		return "int64";
	}

	@Override
	protected Number declaredMin(FieldTypeDef type) {
		var validation = type.getInt64().getValidation();
		return validation.hasMin() ? validation.getMin() : null;
	}

	@Override
	protected Number declaredMax(FieldTypeDef type) {
		var validation = type.getInt64().getValidation();
		return validation.hasMax() ? validation.getMax() : null;
	}

	@Override
	protected String declaredUnit(FieldTypeDef type) {
		return type.getInt64().hasUnit() ? type.getInt64().getUnit() : null;
	}

	@Override
	protected int compare(Number a, Number b) {
		return Long.compare(a.longValue(), b.longValue());
	}

	@Override
	protected Number coerce(Object value) {
		if(value instanceof Long l) {
			return l;
		}

		if(value instanceof Integer i) {
			return (long) i;
		}

		return null;
	}

	@Override
	protected IndexableField pointField(String name, Number value) {
		return new LongPoint(name, value.longValue());
	}

	@Override
	protected IndexableField storedField(String name, Number value) {
		return new StoredField(name, value.longValue());
	}

	@Override
	protected IndexableField sortDocValuesField(String name, Number value) {
		return new NumericDocValuesField(name, value.longValue());
	}

	@Override
	protected long sortableValue(Number value) {
		return value.longValue();
	}

	@Override
	protected Number facetValue(long value) {
		return value;
	}

	@Override
	protected Query exactQuery(String name, Number value) {
		return LongPoint.newExactQuery(name, value.longValue());
	}

	@Override
	protected Query setQuery(String name, ListIterable<Number> values) {
		var array = new long[values.size()];
		var i = 0;
		for(var value : values) {
			array[i++] = value.longValue();
		}

		return LongPoint.newSetQuery(name, array);
	}

	@Override
	protected Query rangeQuery(
		String name,
		Number lower,
		boolean lowerInclusive,
		Number upper,
		boolean upperInclusive
	) {
		var low = lower == null ? Long.MIN_VALUE : lower.longValue();
		if(lower != null && !lowerInclusive) {
			if(low == Long.MAX_VALUE) {
				return new MatchNoDocsQuery();
			}
			low++;
		}

		var high = upper == null ? Long.MAX_VALUE : upper.longValue();
		if(upper != null && !upperInclusive) {
			if(high == Long.MIN_VALUE) {
				return new MatchNoDocsQuery();
			}
			high--;
		}

		return LongPoint.newRangeQuery(name, low, high);
	}

	@Override
	protected SortField.Type sortType() {
		return SortField.Type.LONG;
	}

	@Override
	protected Object missingFirst() {
		return Long.MIN_VALUE;
	}

	@Override
	protected Object missingLast() {
		return Long.MAX_VALUE;
	}

	@Override
	protected BytesRef primaryKeyBytes(Number value) {
		return LongPoint.pack(value.longValue());
	}
}
