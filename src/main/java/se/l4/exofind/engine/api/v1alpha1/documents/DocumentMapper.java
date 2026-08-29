package se.l4.exofind.engine.api.v1alpha1.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.DocumentPatch;
import se.l4.exofind.engine.index.DocumentPath;
import se.l4.exofind.engine.index.GeoPoint;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.schema.Field;
import se.l4.exofind.engine.index.schema.IndexSchema;
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
	private static final ErrorType UNKNOWN_FIELD = ErrorType
		.withCode("request:update:path_unknown_field")
		.withArguments("path", "field")
		.withMessage(
			"`{{path}}` reaches into the field `{{field}}`, which the index does not have"
		);

	private static final ErrorType SELECTOR_NOT_SUPPORTED = ErrorType
		.withCode("request:update:selector_not_supported")
		.withArguments("path", "field")
		.withMessage(
			"`{{path}}` names one value of `{{field}}`, which holds neither locale "
			+ "variants nor objects"
		);

	private static final ErrorType MATCH_NOT_AN_OBJECT = ErrorType
		.withCode("request:update:match_not_an_object")
		.withArguments("path", "field")
		.withMessage(
			"`{{path}}` matches on a field inside `{{field}}`, whose values are not objects"
		);

	private static final ErrorType KEY_NOT_DECLARED = ErrorType
		.withCode("request:update:key_not_declared")
		.withArguments("path", "field")
		.withMessage(
			"`{{path}}` names a value of `{{field}}` by a key, which the field declares "
			+ "none of - name a field inside the value instead, as `{{field}}[field=value]`"
		);

	private static final ErrorType LOCALE_UNKNOWN = ErrorType
		.withCode("request:update:locale_unknown")
		.withArguments("path", "field", "locale")
		.withMessage("Field `{{field}}` holds no variant for the locale `{{locale}}`");

	private static final ErrorType ADD_NOT_MULTIPLE = ErrorType
		.withCode("request:update:add_not_multiple")
		.withArguments("path", "field")
		.withMessage(
			"`{{path}}` adds a value to `{{field}}`, which holds a single value - "
			+ "name the field on its own to replace it"
		);

	private static final ErrorType ADD_REACHES_INSIDE = ErrorType
		.withCode("request:update:add_reaches_inside")
		.withArguments("path")
		.withMessage(
			"`{{path}}` reaches inside a value that is being added, which does not exist yet - "
			+ "give the whole value instead"
		);

	private static final ErrorType NOT_AN_OBJECT = ErrorType
		.withCode("request:update:not_an_object")
		.withArguments("path", "field")
		.withMessage("`{{path}}` reaches inside `{{field}}`, whose values are not objects");

	private static final ErrorType VALUE_REQUIRED = ErrorType
		.withCode("request:update:value_required")
		.withArguments("path", "field", "how")
		.withMessage(
			"`{{field}}` holds a list of values, so `{{path}}` has to say which one, "
			+ "as {{how}}"
		);

	private DocumentMapper() {
	}

	/**
	 * Map a change to some of the fields of a document.
	 *
	 * <p>Every key of the JSON is a path naming a place in the document, and
	 * what it maps to is what that place becomes - so a path written as
	 * {@code null} is one the patch empties, unlike in a whole document, where
	 * it is a field that was not given. How a path is written is
	 * {@link DocumentPath}, and what one means is {@link DocumentPatch}.
	 * Everything else is read the same way as in a whole document.
	 *
	 * @param index
	 *   the index the change is meant for, whose definition decides how the
	 *   paths and the JSON are read
	 * @param json
	 *   the places to change, keyed by path
	 * @return
	 * @throws ValidationException
	 *   if a key is not a path, or names a place the definition does not allow
	 *   to be named that way
	 */
	public static DocumentPatch toPatch(Index index, Map<String, Object> json) {
		var changes = Lists.mutable.<DocumentPatch.Change>ofInitialCapacity(json.size());

		for(var entry : json.entrySet()) {
			changes.add(toChange(index, entry.getKey(), entry.getValue()));
		}

		return new DocumentPatch(changes.toImmutable());
	}

	/**
	 * Map one key of a change into the place it names and what that place
	 * becomes.
	 */
	private static DocumentPatch.Change toChange(Index index, String text, Object value) {
		var path = DocumentPath.parse(text);

		if(path.selectorValue() == null) {
			return withoutSelector(index, text, value);
		}

		var field = index.getField(path.field()).orElseThrow(
			() -> new ValidationException(
				UNKNOWN_FIELD.toMessage(at(text), "path", text, "field", path.field())
			)
		);

		if(path.selectorValue().isEmpty() && path.selectorField() == null) {
			return added(index, field, path, text, value);
		}

		/*
		 * What a selector means is the field's to say, not the path's. An
		 * object field is never locale specific - `locales` is refused on one -
		 * so a single word is a key on an object and a locale tag everywhere
		 * else, with nothing to tell apart.
		 */
		if(field.isObject()) {
			return matching(index, field, path, text, value);
		}

		if(path.selectorField() != null) {
			throw new ValidationException(
				MATCH_NOT_AN_OBJECT.toMessage(at(text), "path", text, "field", field.getName())
			);
		}

		return inLocale(index, field, path, text, value);
	}

	/**
	 * Map a path with no selector, which is a whole field unless it reaches
	 * into an object field of the index.
	 */
	private static DocumentPatch.Change withoutSelector(Index index, String text, Object value) {
		/*
		 * Tried from the last dot back, so that a field whose own name holds
		 * dots is preferred over reading that name as a path through an object.
		 */
		for(var dot = text.lastIndexOf('.'); dot > 0; dot = text.lastIndexOf('.', dot - 1)) {
			var outer = index.getField(text.substring(0, dot));

			if(outer.isPresent() && outer.get().isObject()) {
				return insideObject(
					index,
					outer.get(),
					null,
					text.substring(dot + 1),
					text,
					value
				);
			}
		}

		return new DocumentPatch.Change(
			text,
			DocumentPatch.Selector.ALL,
			null,
			mapped(index, index.getField(text), text, null, value)
		);
	}

	/**
	 * Map a path that adds a value to the ones a field holds.
	 */
	private static DocumentPatch.Change added(
		Index index,
		Field field,
		DocumentPath path,
		String text,
		Object value
	) {
		if(path.inner() != null) {
			throw new ValidationException(
				ADD_REACHES_INSIDE.toMessage(at(text), "path", text)
			);
		}

		if(!field.isMultiple()) {
			throw new ValidationException(
				ADD_NOT_MULTIPLE.toMessage(at(text), "path", text, "field", field.getName())
			);
		}

		return new DocumentPatch.Change(
			field.getName(),
			DocumentPatch.Selector.ADDED,
			null,
			mapped(index, Optional.of(field), field.getName(), null, value)
		);
	}

	/**
	 * Map a path naming object values, either by a field inside them or by the
	 * key the definition declares.
	 */
	private static DocumentPatch.Change matching(
		Index index,
		Field field,
		DocumentPath path,
		String text,
		Object value
	) {
		DocumentPatch.Selector selector;
		if(path.selectorField() != null) {
			selector = new DocumentPatch.Selector.Matching(
				path.selectorField(),
				path.selectorValue()
			);
		} else {
			var key = field.getObjectKey();
			if(key == null) {
				throw new ValidationException(
					KEY_NOT_DECLARED.toMessage(
						at(text),
						"path", text,
						"field", field.getName()
					)
				);
			}

			selector = new DocumentPatch.Selector.ByKey(key, path.selectorValue());
		}

		if(path.inner() != null) {
			return insideObject(index, field, selector, path.inner(), text, value);
		}

		return new DocumentPatch.Change(
			field.getName(),
			selector,
			null,
			mapped(index, Optional.of(field), field.getName(), null, value)
		);
	}

	/**
	 * Map a path naming one locale variant of a field.
	 */
	private static DocumentPatch.Change inLocale(
		Index index,
		Field field,
		DocumentPath path,
		String text,
		Object value
	) {
		if(!field.isLocaleSpecific()) {
			throw new ValidationException(
				SELECTOR_NOT_SUPPORTED.toMessage(at(text), "path", text, "field", field.getName())
			);
		}

		if(path.inner() != null) {
			throw new ValidationException(
				NOT_AN_OBJECT.toMessage(at(text), "path", text, "field", field.getName())
			);
		}

		/*
		 * Resolved to the variant the field declares rather than kept as it was
		 * written, because that is the locale the values are held under - a
		 * field holding `no` takes a change written for `nb-NO`.
		 */
		var locale = field.resolveLocale(path.selectorValue()).orElseThrow(
			() -> new ValidationException(
				LOCALE_UNKNOWN.toMessage(
					at(text),
					"path", text,
					"field", field.getName(),
					"locale", path.selectorValue()
				)
			)
		);

		return new DocumentPatch.Change(
			field.getName(),
			new DocumentPatch.Selector.InLocale(locale),
			null,
			mapped(index, Optional.of(field), field.getName(), locale, value)
		);
	}

	/**
	 * Map a path that reaches into the values of an object field.
	 *
	 * @param selector
	 *   which values, {@code null} for a path that named none - which a field
	 *   holding a list of values refuses
	 */
	private static DocumentPatch.Change insideObject(
		Index index,
		Field object,
		DocumentPatch.Selector selector,
		String inner,
		String text,
		Object value
	) {
		if(selector == null) {
			if(object.isMultiple()) {
				var key = object.getObjectKey();

				throw new ValidationException(
					VALUE_REQUIRED.toMessage(
						at(text),
						"path", text,
						"field", object.getName(),
						"how", key == null
							? "`" + object.getName() + "[field=value]`"
							: "`" + object.getName() + "[" + key + " of the value]`"
					)
				);
			}

			selector = DocumentPatch.Selector.ALL;
		}

		var path = object.getName() + '.' + inner;

		/*
		 * The fields of a nested object resolve through the path; the fields of
		 * a flattened one are fields of the index under it.
		 */
		var field = index.getNestedField(path)
			.map(IndexSchema.NestedField::field)
			.or(() -> index.getField(path));

		return new DocumentPatch.Change(
			object.getName(),
			selector,
			inner,
			mapped(index, field, inner, null, value)
		);
	}

	/**
	 * Read what one place of a change was given, as the values that place
	 * becomes.
	 *
	 * @param locale
	 *   the locale to hold the values under, {@code null} to read the locales
	 *   out of the value the way a whole document does
	 */
	private static ListIterable<Document.Value> mapped(
		Index index,
		Optional<Field> field,
		String name,
		String locale,
		Object value
	) {
		var values = new ArrayList<Document.Value>();

		if(locale == null) {
			appendValues(index, field, name, value, values);
		} else {
			appendIn(index, field.get(), name, locale, value, values);
		}

		return Lists.immutable.ofAll(values);
	}

	private static ObjectLocation at(String path) {
		return ObjectLocation.root().forField(path);
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
				appendIn(
					index,
					field0,
					name,
					String.valueOf(entry.getKey()),
					entry.getValue(),
					target
				);
			}

			return;
		}

		appendIn(index, field0, name, null, value, target);
	}

	/**
	 * Read what one field was given in one locale.
	 *
	 * @param locale
	 *   the locale the values are held under, {@code null} for a field that is
	 *   the same in every language, or for a value that keeps the field's
	 *   default
	 */
	private static void appendIn(
		Index index,
		Field field,
		String name,
		String locale,
		Object value,
		List<Document.Value> target
	) {
		if(value == null) {
			return;
		}

		for(var single : each(field, value)) {
			target.add(new Document.Value(name, toValue(index, field, single), locale));
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

		return new Document(values.toArray(new Document.Value[0]));
	}

	private static Double coordinate(Map<?, ?> point, String name, String alternative) {
		var value = point.containsKey(name) ? point.get(name) : point.get(alternative);
		return value instanceof Number number ? number.doubleValue() : null;
	}
}
