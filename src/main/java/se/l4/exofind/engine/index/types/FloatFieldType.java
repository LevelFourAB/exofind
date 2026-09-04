package se.l4.exofind.engine.index.types;

import org.apache.lucene.document.FloatDocValuesField;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.NumericUtils;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.index.schema.FieldTypeDef;

/**
 * A field holding a 32 bit floating point number.
 */
public class FloatFieldType extends NumberFieldType {
	@Override
	protected String typeName() {
		return "float";
	}

	@Override
	protected Number declaredMin(FieldTypeDef type) {
		var validation = type.getFloat().getValidation();
		return validation.hasMin() ? validation.getMin() : null;
	}

	@Override
	protected Number declaredMax(FieldTypeDef type) {
		var validation = type.getFloat().getValidation();
		return validation.hasMax() ? validation.getMax() : null;
	}

	@Override
	protected String declaredUnit(FieldTypeDef type) {
		return type.getFloat().hasUnit() ? type.getFloat().getUnit() : null;
	}

	@Override
	protected boolean isUsableBound(Number bound) {
		return Float.isFinite(bound.floatValue());
	}

	@Override
	protected int compare(Number a, Number b) {
		return Float.compare(a.floatValue(), b.floatValue());
	}

	@Override
	protected Number coerce(Object value) {
		float coerced;
		if(value instanceof Float f) {
			coerced = f;
		} else if(value instanceof Double d) {
			coerced = (float) (double) d;
		} else if(value instanceof Integer i) {
			coerced = i;
		} else if(value instanceof Long l) {
			coerced = l;
		} else {
			return null;
		}

		// Also refuses a double the width of the field can not hold
		return Float.isFinite(coerced) ? coerced : null;
	}

	@Override
	protected IndexableField pointField(String name, Number value) {
		return new FloatPoint(name, value.floatValue());
	}

	@Override
	protected IndexableField storedField(String name, Number value) {
		return new StoredField(name, value.floatValue());
	}

	@Override
	protected IndexableField sortDocValuesField(String name, Number value) {
		return new FloatDocValuesField(name, value.floatValue());
	}

	@Override
	protected long sortableValue(Number value) {
		return NumericUtils.floatToSortableInt(value.floatValue());
	}

	@Override
	protected Number facetValue(long value) {
		return NumericUtils.sortableIntToFloat((int) value);
	}

	@Override
	protected Query exactQuery(String name, Number value) {
		return FloatPoint.newExactQuery(name, value.floatValue());
	}

	@Override
	protected Query setQuery(String name, ListIterable<Number> values) {
		var array = new float[values.size()];
		var i = 0;
		for(var value : values) {
			array[i++] = value.floatValue();
		}

		return FloatPoint.newSetQuery(name, array);
	}

	@Override
	protected Query rangeQuery(
		String name,
		Number lower,
		boolean lowerInclusive,
		Number upper,
		boolean upperInclusive
	) {
		var low = lower == null ? Float.NEGATIVE_INFINITY : lower.floatValue();
		if(lower != null && !lowerInclusive) {
			low = FloatPoint.nextUp(low);
		}

		var high = upper == null ? Float.POSITIVE_INFINITY : upper.floatValue();
		if(upper != null && !upperInclusive) {
			high = FloatPoint.nextDown(high);
		}

		return FloatPoint.newRangeQuery(name, low, high);
	}

	@Override
	protected SortField.Type sortType() {
		return SortField.Type.FLOAT;
	}

	@Override
	protected Object missingFirst() {
		return Float.NEGATIVE_INFINITY;
	}

	@Override
	protected Object missingLast() {
		return Float.POSITIVE_INFINITY;
	}
}
