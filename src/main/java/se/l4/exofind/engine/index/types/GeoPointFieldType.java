package se.l4.exofind.engine.index.types;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.apache.lucene.document.LatLonDocValuesField;
import org.apache.lucene.document.LatLonPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.FieldNames;
import se.l4.exofind.engine.index.GeoPoint;
import se.l4.exofind.engine.index.IndexEncounter;
import se.l4.exofind.engine.index.IndexException;
import se.l4.exofind.engine.index.IndexFieldUsageException;
import se.l4.exofind.engine.index.IndexInvalidQueryTypeException;
import se.l4.exofind.engine.index.IndexInvalidQueryValueException;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.query.matchers.AnyMatcher;
import se.l4.exofind.engine.query.matchers.DistanceMatcher;
import se.l4.exofind.engine.query.matchers.Matcher;

/**
 * A field holding a point on the earth, as a WGS 84 latitude and longitude.
 *
 * A place is searched by nearness rather than by value - the {@code distance}
 * matcher narrows to values within a radius of an origin, and ordering is by
 * distance from an origin, nearest first. That origin is why ordering goes
 * through {@link #createDistanceSortField} rather than the plain sort, which
 * has no place to carry one; declaring {@code sort} on the field is still what
 * writes the doc values distance is ordered by.
 */
public class GeoPointFieldType implements FieldType {
	private static final ErrorType COLLATION_NOT_SUPPORTED = ErrorType
		.withCode("index:field:sort:collation_not_supported")
		.withMessage("Collation means nothing when sorting a geo point field");

	private static final ErrorType INVALID_VALUE = ErrorType
		.withCode("index:update:geo_point:invalid_value")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` holds a geo point, which has to be given as a latitude and a longitude"
		);

	private static final ErrorType OUT_OF_RANGE = ErrorType
		.withCode("index:update:geo_point:out_of_range")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` was given a point that is not on the earth - latitude is -90 to 90, longitude -180 to 180"
		);

	private static final ErrorType SORT_NEEDS_ORIGIN = ErrorType
		.withCode("index:query:geo_point:sort_needs_origin")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` holds a geo point, which is ordered by distance from an origin - use a distance sort"
		);

	@Override
	public boolean isSortingSupported() {
		return true;
	}

	@Override
	public boolean isDocValuesSupported() {
		return false;
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
		var location = ObjectLocation.root().forField(encounter.getFieldName());

		if(!(value0 instanceof GeoPoint value)) {
			throw new ValidationException(
				INVALID_VALUE.toMessage(location, "name", encounter.getFieldName())
			);
		}

		if(!onEarth(value.latitude(), value.longitude())) {
			throw new ValidationException(
				OUT_OF_RANGE.toMessage(location, "name", encounter.getFieldName())
			);
		}

		var results = Lists.mutable.<IndexableField>empty();

		if(encounter.isFiltered()) {
			results.add(
				new LatLonPoint(
					encounter.name(FieldNames.FILTER),
					value.latitude(),
					value.longitude()
				)
			);
		}

		if(encounter.isSorted()) {
			results.add(
				new LatLonDocValuesField(
					encounter.name(FieldNames.SORT),
					value.latitude(),
					value.longitude()
				)
			);
		}

		if(encounter.isStored()) {
			results.add(new StoredField(encounter.name(FieldNames.STORED), pack(value)));
		}

		return results;
	}

	@Override
	public Object readStored(IndexEncounter encounter, IndexableField field) {
		var bytes = field.binaryValue();
		var buffer = ByteBuffer.wrap(bytes.bytes, bytes.offset, bytes.length)
			.order(ByteOrder.LITTLE_ENDIAN);
		return new GeoPoint(buffer.getDouble(), buffer.getDouble());
	}

	@Override
	public Query createQuery(IndexEncounter encounter, Matcher matcher) {
		if(matcher instanceof DistanceMatcher m) {
			if(!onEarth(m.latitude(), m.longitude())) {
				throw new IndexInvalidQueryValueException(
					encounter.getFieldName(),
					"origin on the earth"
				);
			}

			if(!(m.radius() > 0) || !Double.isFinite(m.radius())) {
				throw new IndexInvalidQueryValueException(
					encounter.getFieldName(),
					"radius in meters above zero"
				);
			}

			return LatLonPoint.newDistanceQuery(
				filterName(encounter),
				m.latitude(),
				m.longitude(),
				m.radius()
			);
		}

		if(matcher instanceof AnyMatcher) {
			// The whole earth, which is every document that has a value
			return LatLonPoint.newBoxQuery(filterName(encounter), -90, 90, -180, 180);
		}

		throw new IndexInvalidQueryTypeException("geo_point", matcher.id());
	}

	@Override
	public SortField createSortField(IndexEncounter encounter, boolean ascending) {
		/*
		 * Reached by an ordinary field sort, which carries no origin to
		 * measure from. Refused with directions rather than answered with an
		 * order that means nothing.
		 */
		throw new IndexException(
			SORT_NEEDS_ORIGIN,
			"name", encounter.getFieldName()
		);
	}

	@Override
	public SortField createDistanceSortField(
		IndexEncounter encounter,
		double latitude,
		double longitude
	) {
		if(!encounter.isSorted()) {
			throw new IndexFieldUsageException(encounter.getFieldName(), "sort");
		}

		if(!onEarth(latitude, longitude)) {
			throw new IndexInvalidQueryValueException(
				encounter.getFieldName(),
				"origin on the earth"
			);
		}

		return LatLonDocValuesField.newDistanceSort(
			encounter.name(FieldNames.SORT),
			latitude,
			longitude
		);
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

	private static boolean onEarth(double latitude, double longitude) {
		return latitude >= -90 && latitude <= 90
			&& longitude >= -180 && longitude <= 180;
	}

	/**
	 * Get a point as the bytes it is stored as - two little-endian doubles,
	 * latitude first. Stored explicitly so the field can be retrieved on its
	 * own even when the index keeps no source.
	 */
	private static byte[] pack(GeoPoint value) {
		return ByteBuffer.allocate(2 * Double.BYTES)
			.order(ByteOrder.LITTLE_ENDIAN)
			.putDouble(value.latitude())
			.putDouble(value.longitude())
			.array();
	}
}
