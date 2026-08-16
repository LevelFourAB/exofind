package se.l4.exofind.engine.index.types;

import org.apache.lucene.document.IntPoint;
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
 * A field holding a 32 bit signed integer.
 */
public class Int32FieldType extends NumberFieldType {
	@Override
	public boolean isPrimaryKeySupported() {
		return true;
	}

	@Override
	protected String typeName() {
		return "int32";
	}

	@Override
	protected Number declaredMin(FieldTypeDef type) {
		var validation = type.getInt32().getValidation();
		return validation.hasMin() ? validation.getMin() : null;
	}

	@Override
	protected Number declaredMax(FieldTypeDef type) {
		var validation = type.getInt32().getValidation();
		return validation.hasMax() ? validation.getMax() : null;
	}

	@Override
	protected int compare(Number a, Number b) {
		return Integer.compare(a.intValue(), b.intValue());
	}

	@Override
	protected Number coerce(Object value) {
		if(value instanceof Integer i) {
			return i;
		}

		if(value instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
			return (int) (long) l;
		}

		return null;
	}

	@Override
	protected IndexableField pointField(String name, Number value) {
		return new IntPoint(name, value.intValue());
	}

	@Override
	protected IndexableField storedField(String name, Number value) {
		return new StoredField(name, value.intValue());
	}

	@Override
	protected IndexableField sortDocValuesField(String name, Number value) {
		return new NumericDocValuesField(name, value.intValue());
	}

	@Override
	protected long sortableValue(Number value) {
		return value.intValue();
	}

	@Override
	protected Number facetValue(long value) {
		return (int) value;
	}

	@Override
	protected Query exactQuery(String name, Number value) {
		return IntPoint.newExactQuery(name, value.intValue());
	}

	@Override
	protected Query setQuery(String name, ListIterable<Number> values) {
		var array = new int[values.size()];
		var i = 0;
		for(var value : values) {
			array[i++] = value.intValue();
		}

		return IntPoint.newSetQuery(name, array);
	}

	@Override
	protected Query rangeQuery(
		String name,
		Number lower,
		boolean lowerInclusive,
		Number upper,
		boolean upperInclusive
	) {
		var low = lower == null ? Integer.MIN_VALUE : lower.intValue();
		if(lower != null && !lowerInclusive) {
			if(low == Integer.MAX_VALUE) {
				return new MatchNoDocsQuery();
			}
			low++;
		}

		var high = upper == null ? Integer.MAX_VALUE : upper.intValue();
		if(upper != null && !upperInclusive) {
			if(high == Integer.MIN_VALUE) {
				return new MatchNoDocsQuery();
			}
			high--;
		}

		return IntPoint.newRangeQuery(name, low, high);
	}

	@Override
	protected SortField.Type sortType() {
		return SortField.Type.INT;
	}

	@Override
	protected Object missingFirst() {
		return Integer.MIN_VALUE;
	}

	@Override
	protected Object missingLast() {
		return Integer.MAX_VALUE;
	}

	@Override
	protected BytesRef primaryKeyBytes(Number value) {
		return IntPoint.pack(value.intValue());
	}
}
