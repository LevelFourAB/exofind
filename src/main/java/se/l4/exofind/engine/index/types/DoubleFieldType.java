package se.l4.exofind.engine.index.types;

import org.apache.lucene.document.DoubleDocValuesField;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.NumericUtils;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.index.schema.FieldTypeDef;

/**
 * A field holding a 64 bit floating point number.
 */
public class DoubleFieldType extends NumberFieldType {
	@Override
	protected String typeName() {
		return "double";
	}

	@Override
	protected Number declaredMin(FieldTypeDef type) {
		var validation = type.getDouble().getValidation();
		return validation.hasMin() ? validation.getMin() : null;
	}

	@Override
	protected Number declaredMax(FieldTypeDef type) {
		var validation = type.getDouble().getValidation();
		return validation.hasMax() ? validation.getMax() : null;
	}

	@Override
	protected boolean isUsableBound(Number bound) {
		return Double.isFinite(bound.doubleValue());
	}

	@Override
	protected int compare(Number a, Number b) {
		return Double.compare(a.doubleValue(), b.doubleValue());
	}

	@Override
	protected Number coerce(Object value) {
		double coerced;
		if(value instanceof Double d) {
			coerced = d;
		} else if(value instanceof Float f) {
			coerced = f;
		} else if(value instanceof Integer i) {
			coerced = i;
		} else if(value instanceof Long l) {
			coerced = l;
		} else {
			return null;
		}

		return Double.isFinite(coerced) ? coerced : null;
	}

	@Override
	protected IndexableField pointField(String name, Number value) {
		return new DoublePoint(name, value.doubleValue());
	}

	@Override
	protected IndexableField storedField(String name, Number value) {
		return new StoredField(name, value.doubleValue());
	}

	@Override
	protected IndexableField sortDocValuesField(String name, Number value) {
		return new DoubleDocValuesField(name, value.doubleValue());
	}

	@Override
	protected long sortableValue(Number value) {
		return NumericUtils.doubleToSortableLong(value.doubleValue());
	}

	@Override
	protected Number facetValue(long value) {
		return NumericUtils.sortableLongToDouble(value);
	}

	@Override
	protected Query exactQuery(String name, Number value) {
		return DoublePoint.newExactQuery(name, value.doubleValue());
	}

	@Override
	protected Query setQuery(String name, ListIterable<Number> values) {
		var array = new double[values.size()];
		var i = 0;
		for(var value : values) {
			array[i++] = value.doubleValue();
		}

		return DoublePoint.newSetQuery(name, array);
	}

	@Override
	protected Query rangeQuery(
		String name,
		Number lower,
		boolean lowerInclusive,
		Number upper,
		boolean upperInclusive
	) {
		var low = lower == null ? Double.NEGATIVE_INFINITY : lower.doubleValue();
		if(lower != null && !lowerInclusive) {
			low = DoublePoint.nextUp(low);
		}

		var high = upper == null ? Double.POSITIVE_INFINITY : upper.doubleValue();
		if(upper != null && !upperInclusive) {
			high = DoublePoint.nextDown(high);
		}

		return DoublePoint.newRangeQuery(name, low, high);
	}

	@Override
	protected SortField.Type sortType() {
		return SortField.Type.DOUBLE;
	}

	@Override
	protected Object missingFirst() {
		return Double.NEGATIVE_INFINITY;
	}

	@Override
	protected Object missingLast() {
		return Double.POSITIVE_INFINITY;
	}
}
