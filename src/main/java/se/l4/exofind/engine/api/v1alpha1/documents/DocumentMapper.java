package se.l4.exofind.engine.api.v1alpha1.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;

import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.DocumentPatch;
import se.l4.exofind.engine.index.GeoPoint;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.schema.Field;
import se.l4.exofind.engine.index.types.GeoPointFieldType;
import se.l4.exofind.engine.index.types.VectorFieldType;

/**
 * Turns a document as it arrives over the API into the {@link Document} the
 * index takes.
 *
 * The shape a document is written in is the shape a search reads it back in,
 * which is what lets a hit be sent straight back to be indexed again: a field
 * holding one value is a scalar and one holding several is an array, a locale
 * specific field is an object keyed by locale tag, and the value of an object
 * field is a JSON object of its own.
 *
 * What a JSON object means therefore depends on the field it was given to,
 * and the definition is what says which - so the mapping is done against the
 * index rather than on the JSON alone. Only the shape is decided here;
 * whether a value belongs in the field at all is left to the index, so one
 * answer covers documents sent over the API and documents added through the
 * Java API.
 */
public class DocumentMapper {
	private DocumentMapper() {
	}

	/**
	 * Map a change to some of the fields of a document.
	 *
	 * <p>A key the JSON holds names a field the patch replaces, so a field
	 * written as {@code null} is one the patch empties - unlike a whole
	 * document, where it is a field that was not given. Everything else is read
	 * the same way as in a whole document.
	 *
	 * @param index
	 *   the index the change is meant for, whose definition decides how the
	 *   JSON is read
	 * @param json
	 *   the fields to change, keyed by field name
	 * @return
	 */
	public static DocumentPatch toPatch(Index index, Map<String, Object> json) {
		var fields = Sets.mutable.<String>ofInitialCapacity(json.size());
		var values = new ArrayList<Document.Value>(json.size());

		for(var entry : json.entrySet()) {
			var name = entry.getKey();

			fields.add(name);
			appendValues(index, index.getField(name), name, entry.getValue(), values);
		}

		return new DocumentPatch(fields.toImmutable(), Lists.immutable.ofAll(values));
	}

	/**
	 * Map a document received over the API.
	 *
	 * @param index
	 *   the index the document is meant for, whose definition decides how the
	 *   JSON is read
	 * @param json
	 *   the document, keyed by field name
	 * @return
	 */
	public static Document toEngine(Index index, Map<String, Object> json) {
		var values = new ArrayList<Document.Value>(json.size());

		for(var entry : json.entrySet()) {
			var name = entry.getKey();
			appendValues(index, index.getField(name), name, entry.getValue(), values);
		}

		return new Document(values.toArray(Document.Value[]::new));
	}

	/**
	 * Read what one field of a document was given, appending a value for
	 * every one of them.
	 *
	 * @param index
	 * @param field
	 *   the field the value was given to, empty when the definition has no
	 *   such field - the value is then passed on as it arrived, for the index
	 *   to refuse by the name it was given
	 * @param name
	 *   the name the value is added under, which for a field inside an object
	 *   is the name within that object rather than the path
	 * @param value
	 * @param target
	 */
	private static void appendValues(
		Index index,
		Optional<Field> field,
		String name,
		Object value,
		List<Document.Value> target
	) {
		if(value == null) {
			/*
			 * A field written as null is a field that was not given, so that
			 * a document can be built with a key for every field the caller
			 * knows about. A field the definition requires is still missing,
			 * and reported as missing rather than as holding nothing.
			 */
			return;
		}

		if(field.isEmpty()) {
			target.add(new Document.Value(name, value));
			return;
		}

		var field0 = field.get();

		/*
		 * A locale specific field holds a variant per locale, so its object
		 * is read as the locales rather than as a value - including for a
		 * type whose values are objects themselves, which sit one level
		 * further in. A value given without any locale keeps the field's
		 * default, which the index fills in.
		 */
		if(field0.isLocaleSpecific() && value instanceof Map<?, ?> localized) {
			for(var entry : localized.entrySet()) {
				var locale = String.valueOf(entry.getKey());

				for(var single : each(field0, entry.getValue())) {
					target.add(
						new Document.Value(name, toValue(index, field0, single), locale)
					);
				}
			}

			return;
		}

		for(var single : each(field0, value)) {
			target.add(new Document.Value(name, toValue(index, field0, single)));
		}
	}

	/**
	 * Split what was given into the individual values it holds. An array is
	 * the several values of a field declared multiple - except for a vector,
	 * which is written as an array of numbers and is one value, so only an
	 * array of arrays holds several of them.
	 *
	 * @param field
	 * @param value
	 * @return
	 */
	private static List<Object> each(Field field, Object value) {
		if(!(value instanceof List<?> list)) {
			return List.of(value);
		}

		if(field.getType() instanceof VectorFieldType && !holdsLists(list)) {
			return List.of(value);
		}

		return List.copyOf(list);
	}

	private static boolean holdsLists(List<?> list) {
		return !list.isEmpty() && list.get(0) instanceof List;
	}

	/**
	 * Turn one value into what the type of the field holds it as.
	 *
	 * @param index
	 * @param field
	 * @param value
	 * @return
	 */
	private static Object toValue(Index index, Field field, Object value) {
		if(field.isObject() && value instanceof Map<?, ?> object) {
			/*
			 * The fields inside an object are declared under the name the
			 * definition gives the object, which is what a value given to a
			 * wildcard pattern has to be looked up under.
			 */
			return toNestedDocument(index, field.getName(), object);
		}

		var type = field.getType();

		if(type instanceof GeoPointFieldType && value instanceof Map<?, ?> point) {
			var latitude = coordinate(point, "lat", "latitude");
			var longitude = coordinate(point, "lon", "longitude");

			if(latitude != null && longitude != null) {
				return new GeoPoint(latitude, longitude);
			}

			/*
			 * Passed on as it arrived when it does not read as a point, so
			 * that the field says what it holds rather than this saying that
			 * it is not a point.
			 */
			return value;
		}

		if(type instanceof VectorFieldType && value instanceof List<?> components) {
			var vector = new float[components.size()];
			for(var i = 0; i < vector.length; i++) {
				if(!(components.get(i) instanceof Number component)) {
					return value;
				}

				vector[i] = component.floatValue();
			}

			return vector;
		}

		return value;
	}

	/**
	 * Read one value of an object field, whose fields are the ones the object
	 * declares.
	 *
	 * @param index
	 * @param path
	 *   the path of the object field itself
	 * @param json
	 * @return
	 */
	private static Document toNestedDocument(Index index, String path, Map<?, ?> json) {
		var values = new ArrayList<Document.Value>(json.size());

		for(var entry : json.entrySet()) {
			var name = String.valueOf(entry.getKey());
			var nestedPath = path + '.' + name;

			/*
			 * The fields of a nested object resolve through the path; the
			 * fields of a flattened one are fields of the index under it.
			 */
			appendValues(
				index,
				index.getNestedField(nestedPath)
					.map(nested -> nested.field())
					.or(() -> index.getField(nestedPath)),
				name,
				entry.getValue(),
				values
			);
		}

		return new Document(values.toArray(Document.Value[]::new));
	}

	private static Double coordinate(Map<?, ?> point, String name, String alternative) {
		var value = point.containsKey(name) ? point.get(name) : point.get(alternative);
		return value instanceof Number number ? number.doubleValue() : null;
	}
}
