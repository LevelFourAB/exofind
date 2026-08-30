package se.l4.exofind.engine.index.types;

import java.util.Locale;
import java.util.Set;

import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.Query;
import org.eclipse.collections.api.collection.MutableCollection;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.impl.factory.Lists;

import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.index.IndexEncounter;
import se.l4.exofind.engine.index.IndexInvalidQueryTypeException;
import se.l4.exofind.engine.index.schema.Field;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.query.matchers.Matcher;

/**
 * Field type whose values are objects, described by the fields of an
 * {@code ObjectFieldTypeDef}.
 *
 * The type is a container rather than a value: it can not be filtered, sorted
 * or matched on itself, only through the fields inside it. How those fields
 * are kept is the {@code mode} of the definition. Flattened folds them into
 * the document itself, so they behave as fields of the index named by the
 * dotted path; nested keeps every value as a document of its own, which is
 * what lets a search ask that several conditions hold inside the same value.
 * A single value is one unit either way, so the mode is required exactly when
 * the field is {@code multiple} and refused when it is not - flattened is
 * what a single value always is. The indexing of both modes lives in
 * {@code Index}, so the two field-producing methods here are never reached;
 * what this class owns is judging the definition.
 *
 * The name of a field inside an object may hold a wildcard, and so may the
 * name of the object itself. Both stand for names that only exist once a
 * document gives them, and a path through either resolves by the rule the root
 * resolves by - the settled name first, then the patterns, the most specific
 * winning. An object whose name is a pattern keeps the values of each name it
 * matched apart, so a {@code nested} clause naming one of them never reaches
 * the values of another. What a pattern can not be is whatever has to name one
 * settled field: a primary key, a required field, or the {@code key} below.
 *
 * A list of values may name one of its own fields as its {@code key}, which is
 * what a value is called rather than where it sits. Two values of one document
 * reading the same under it are refused when the document is indexed, and that
 * refusal is what everything reading a key relies on - an update path naming
 * one value, a value hit saying which value it is. What the name may point at
 * is {@link #validateKey}.
 *
 * In the nested mode the fields inside are held to the usages that work
 * across a join to the document a value belongs to. Filtering, matching,
 * sorting and faceting all do: a value says whether its document matches, how
 * well, where it is ordered and what it is counted under, and which values
 * answer is what the {@code nested} clauses of a search decide. Refused are
 * the usages that only mean something for a document of the index - being its
 * primary key, being highlighted, which reads the fields of the document
 * rather than of a value - as are locale variants, stored values and objects
 * inside objects. A flattened list refuses sorting besides, because its
 * values are independent and no one of them stands for the document.
 */
public class ObjectFieldType implements FieldType {
	/**
	 * The types a {@code key} may name. Every one of them reads back as the
	 * text it was written as, which is what a key is compared by.
	 */
	private static final Set<FieldTypeDef.TypeCase> KEY_TYPES = Set.of(
		FieldTypeDef.TypeCase.STRING,
		FieldTypeDef.TypeCase.INT32,
		FieldTypeDef.TypeCase.INT64
	);

	private static final ErrorType NO_FIELDS = ErrorType
		.withCode("index:field:object:no_fields")
		.withMessage("An object needs at least one field");

	private static final ErrorType USAGE_NOT_SUPPORTED = ErrorType
		.withCode("index:field:object:usage_not_supported")
		.withArguments("usage")
		.withMessage(
			"An object holds no value of its own, so it can not be defined for `{{usage}}`"
		);

	private static final ErrorType INNER_USAGE_NOT_SUPPORTED = ErrorType
		.withCode("index:field:object:inner_usage_not_supported")
		.withArguments("name", "usage")
		.withMessage(
			"Field `{{name}}` is inside an object, where `{{usage}}` is not supported"
		);

	private static final ErrorType INNER_OBJECT = ErrorType
		.withCode("index:field:object:inner_object")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` is an object inside an object, which is not supported"
		);

	private static final ErrorType MODE_REQUIRED = ErrorType
		.withCode("index:field:object:mode_required")
		.withMessage(
			"A list of objects needs a `mode`: `nested` when a search must be able to "
			+ "ask that several conditions hold inside the same value, `flattened` when "
			+ "the values are only structure and their fields match independently"
		);

	private static final ErrorType MODE_WITHOUT_MULTIPLE = ErrorType
		.withCode("index:field:object:mode_without_multiple")
		.withMessage(
			"A single object is always flattened, so `mode` only applies together with `multiple`"
		);

	private static final ErrorType KEY_WITHOUT_MULTIPLE = ErrorType
		.withCode("index:field:object:key_without_multiple")
		.withMessage(
			"A single object has no other value to be told apart from, so `key` only "
			+ "applies together with `multiple`"
		);

	private static final ErrorType KEY_NOT_FOUND = ErrorType
		.withCode("index:field:object:key_not_found")
		.withArguments("key")
		.withMessage(
			"`key` names `{{key}}`, which is not one of the fields the object holds"
		);

	private static final ErrorType KEY_NOT_VALID = ErrorType
		.withCode("index:field:object:key_not_valid")
		.withArguments("key", "reason")
		.withMessage(
			"`key` names `{{key}}`, which can not say which value is which: {{reason}}"
		);

	private static final ErrorType FLATTENED_SORT = ErrorType
		.withCode("index:field:object:flattened_sort")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` is inside a flattened list of objects, where no single "
			+ "value stands for the document, so it can not be defined for `sort` - "
			+ "keeping the values apart is the `nested` mode"
		);

	@Override
	public boolean isSortingSupported() {
		return false;
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

		if(def.hasFilter()) {
			errors.add(USAGE_NOT_SUPPORTED.toMessage(location, "usage", "filter"));
		}

		if(def.hasStored() && def.getStored()) {
			errors.add(USAGE_NOT_SUPPORTED.toMessage(location, "usage", "stored"));
		}

		if(def.hasLocales()) {
			errors.add(USAGE_NOT_SUPPORTED.toMessage(location, "usage", "locales"));
		}

		var objectType = def.getType().getObject();
		if(objectType.getFieldsCount() == 0) {
			errors.add(NO_FIELDS.toMessage(location));
		}

		/*
		 * The mode is asked for exactly where the two modes answer searches
		 * differently. A single value is one unit whichever way it is kept, so
		 * a mode on it can only be a leftover from a `multiple` that was
		 * dropped - refused so the definition never says something it does not
		 * mean.
		 */
		if(def.getMultiple() && !objectType.hasMode()) {
			errors.add(MODE_REQUIRED.toMessage(location.forField("mode")));
		} else if(!def.getMultiple() && objectType.hasMode()) {
			errors.add(MODE_WITHOUT_MULTIPLE.toMessage(location.forField("mode")));
		}

		if(objectType.hasKey()) {
			validateKey(location.forField("key"), def, objectType, errors);
		}

		var flattenedList = def.getMultiple()
			&& objectType.getMode() != ObjectFieldTypeDef.Mode.MODE_NESTED;

		for(var entry : objectType.getFieldsMap().entrySet()) {
			validateInner(
				location.forField("fields").forField(entry.getKey()),
				entry.getKey(),
				entry.getValue(),
				flattenedList,
				resources,
				errors
			);
		}

		return errors;
	}

	/**
	 * Judge the field a {@code key} names, which has to be able to say which
	 * value is which for every value the field will ever hold.
	 *
	 * <p>That asks three things of it. It has to exist, or the key names
	 * nothing. It has to be required and hold one value, or a value can arrive
	 * with no key or with several and there is no one thing it is called. And
	 * it has to be a type whose values read back as the text they were written
	 * as, because a key is compared as text wherever it is used - a
	 * {@code double} that holds {@code 1} and reads back {@code 1.0} would be
	 * a name that answers to neither spelling.
	 */
	private static void validateKey(
		ObjectLocation location,
		FieldDef def,
		ObjectFieldTypeDef objectType,
		MutableCollection<ErrorMessage> errors
	) {
		var key = objectType.getKey();

		if(!def.getMultiple()) {
			errors.add(KEY_WITHOUT_MULTIPLE.toMessage(location));
			return;
		}

		var keyField = objectType.getFieldsMap().get(key);
		if(keyField == null) {
			errors.add(KEY_NOT_FOUND.toMessage(location, "key", key));
			return;
		}

		/*
		 * Judged before the settings below, which a pattern can not carry
		 * anyway - being told it is not `required` would point at a setting
		 * rather than at the name being the wrong kind of name.
		 */
		if(key.contains("*")) {
			errors.add(KEY_NOT_VALID.toMessage(
				location,
				"key", key,
				"reason", "it is a pattern, which stands for whichever fields "
					+ "documents name rather than for one every value holds"
			));
			return;
		}

		if(!keyField.getRequired()) {
			errors.add(KEY_NOT_VALID.toMessage(
				location,
				"key", key,
				"reason", "it is not `required`, so a value could arrive without one"
			));
		}

		if(keyField.getMultiple()) {
			errors.add(KEY_NOT_VALID.toMessage(
				location,
				"key", key,
				"reason", "it is `multiple`, so a value could hold several"
			));
		}

		if(!KEY_TYPES.contains(keyField.getType().getTypeCase())) {
			errors.add(KEY_NOT_VALID.toMessage(
				location,
				"key", key,
				"reason", "a key is compared as text, which `"
					+ keyField.getType().getTypeCase().name().toLowerCase(Locale.ROOT)
					+ "` values do not read back as"
			));
		}
	}

	private static void validateInner(
		ObjectLocation location,
		String name,
		FieldDef def,
		boolean flattenedList,
		ResourcesDef resources,
		MutableCollection<ErrorMessage> errors
	) {
		if(def.getType().getTypeCase() == FieldTypeDef.TypeCase.OBJECT) {
			/*
			 * Refused before the general validation runs, which would recurse
			 * into it and report its fields as if they could exist.
			 */
			errors.add(INNER_OBJECT.toMessage(location, "name", name));
			return;
		}

		if(def.getPrimaryKey()) {
			errors.add(INNER_USAGE_NOT_SUPPORTED.toMessage(
				location, "name", name, "usage", "primary_key"
			));
		}

		if(def.hasStored() && def.getStored()) {
			errors.add(INNER_USAGE_NOT_SUPPORTED.toMessage(
				location, "name", name, "usage", "stored"
			));
		}

		if(def.hasLocales()) {
			errors.add(INNER_USAGE_NOT_SUPPORTED.toMessage(
				location, "name", name, "usage", "locales"
			));
		}

		if(flattenedList && def.hasSort()) {
			errors.add(FLATTENED_SORT.toMessage(location, "name", name));
		}

		/*
		 * Highlighting reads the text a search matched back out of the document
		 * it is shown for, and a value is not that document. Refused rather
		 * than written and never readable.
		 */
		if(def.getType().getTypeCase() == FieldTypeDef.TypeCase.STRING) {
			var string = def.getType().getString();
			if(string.getMatching().hasHighlight() || string.getAutocomplete().hasHighlight()) {
				errors.add(INNER_USAGE_NOT_SUPPORTED.toMessage(
					location, "name", name, "usage", "highlight"
				));
			}
		}

		errors.addAllIterable(Field.validate(location, name, def, resources));
	}

	@Override
	public Iterable<? extends IndexableField> createFields(IndexEncounter encounter, Object value) {
		/*
		 * An object value is taken apart before any type is asked for fields:
		 * Index writes it as a document of its own or folds its fields into the
		 * document, per the mode, before this could be reached.
		 */
		throw new UnsupportedOperationException(
			"Object values are indexed through the fields inside them"
		);
	}

	@Override
	public Query createQuery(IndexEncounter encounter, Matcher matcher) {
		throw new IndexInvalidQueryTypeException("object", matcher.id());
	}
}
