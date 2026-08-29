package se.l4.exofind.engine.index.schema;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.lucene.index.DocValues;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.SetIterable;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.schema.FieldTypeDef.TypeCase;
import se.l4.exofind.engine.index.types.FieldType;
import se.l4.exofind.engine.index.types.FieldTypes;

public class Field {
	public static final Pattern VALID_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_\\.\\*]+");

	private static ErrorType INVALID_NAME = ErrorType.withCode("index:field:invalid_name")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` should only contain letters, numbers, underscores, dots, and wildcards"
		);

	private static ErrorType INVALID_PRIMARY_KEY_WILDCARD = ErrorType
		.withCode("index:field:invalid_name:primary_key_wildcard")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` is marked as a primary key, but names with wildcards can not be primary keys"
		);

	private static ErrorType INVALID_OBJECT_WILDCARD = ErrorType
		.withCode("index:field:object:wildcard_name")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` is an object, and names with wildcards can not be objects"
		);

	private static ErrorType PRIMARY_KEY_NOT_REQUIRED =
		ErrorType.withCode("index:schema:primary_key_not_required")
			.withArguments("name")
			.withMessage(
				"Primary key field `{{name}}` is explicitly marked as not required, but the primary key must be required"
			);

	private static ErrorType INVALID_PRIMARY_KEY_LOCALE_SPECIFIC =
		ErrorType.withCode("index:schema:primary_key_locale_specific")
			.withArguments("name")
			.withMessage(
				"Field `{{name}}` is marked as a primary key, but locale specific fields can not be primary keys"
			);

	private static ErrorType UNSUPPORTED_LOCALE = ErrorType
		.withCode("index:field:locales:unsupported_locale")
		.withArguments("name", "locale")
		.withMessage(
			"Field `{{name}}` names locale `{{locale}}` which this version of the engine does not support"
		);

	private static ErrorType INVALID_PRIMARY_KEY_MULTIPLE = ErrorType
		.withCode("index:field:invalid_primary_key_multiple")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` is marked as a primary key and multiple, primary keys can not have multiple values"
		);

	private static ErrorType INVALID_REQUIRED = ErrorType.withCode("index:field:invalid_required")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` is marked as required, but names with wildcards can not be required"
		);

	private static ErrorType INVALID_SORTABLE = ErrorType.withCode("index:field:invalid_sortable")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` is sortable and multiple, sortable fields can not have multiple values"
		);

	private static ErrorType SORTING_NOT_SUPPORTED = ErrorType
		.withCode("index:field:sorting_not_supported")
		.withArguments("name", "type")
		.withMessage(
			"Field `{{name}}` is sortable, but `{{type}}` fields can not be sorted on"
		);

	private static ErrorType FACETING_NOT_SUPPORTED = ErrorType
		.withCode("index:field:faceting_not_supported")
		.withArguments("name", "type")
		.withMessage(
			"Field `{{name}}` is faceted, but `{{type}}` fields can not be counted per value"
		);

	private static ErrorType MISSING_TYPE = ErrorType.withCode("index:field:missing_type")
		.withArguments("name")
		.withMessage(
			"Field `{{name}}` is missing a type"
		);

	private static ErrorType UNSUPPORTED_TYPE = ErrorType.withCode("index:field:unsupported_type")
		.withArguments("name", "type")
		.withMessage(
			"Field `{{name}}` has type `{{type}}` which this version of the engine can not index"
		);

	private static ErrorType INVALID_PRIMARY_KEY_TYPE = ErrorType
		.withCode("index:field:invalid_primary_key_type")
		.withArguments("name", "type")
		.withMessage(
			"Field `{{name}}` is marked as a primary key, but `{{type}}` fields can not be primary keys"
		);

	private final FieldDef def;
	private final FieldType type;
	private final String name;
	private final Pattern namePattern;
	private final boolean hasWildcard;

	private final String defaultLocale;
	private final ImmutableSet<String> locales;

	public Field(String name, FieldDef def) {
		this.name = name;
		this.def = def;

		if(def.hasLocales()) {
			var config = def.getLocales();
			this.defaultLocale = config.hasDefaultLocale()
				? config.getDefaultLocale()
				: Locales.getDefault().getLocale();
			this.locales = Sets.mutable.of(this.defaultLocale)
				.withAll(config.getLocalesList())
				.toImmutable();
		} else {
			this.defaultLocale = null;
			this.locales = Sets.immutable.empty();
		}

		String regex = "^" +
			name.replace(".", "\\.")
				.replace("*", "[^.]+")
			+ "$";
		this.namePattern = Pattern.compile(regex);
		this.hasWildcard = name.contains("*");

		this.type = FieldTypes.forDef(def.getType())
			.orElseThrow(
				() -> new EngineException(
					UNSUPPORTED_TYPE,
					"name", name,
					"type", typeName(def.getType())
				)
			);
	}

	/**
	 * Get the name a field type goes by in messages, such as {@code string}.
	 *
	 * @param type
	 * @return
	 */
	private static String typeName(FieldTypeDef type) {
		return type.getTypeCase().name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Validate the settings of the field.
	 *
	 * @param name
	 * @param def
	 * @param resources
	 *   what the index shares between fields, for checking that what the
	 *   field refers to by name exists
	 * @return
	 */
	public static ListIterable<ErrorMessage> validate(
		String name,
		FieldDef def,
		ResourcesDef resources
	) {
		return validate(ObjectLocation.root().forField(name), name, def, resources);
	}

	/**
	 * Validate the settings of a field that sits somewhere other than the root
	 * of a definition, such as inside an object, pointing errors at where it
	 * actually is.
	 *
	 * @param location
	 *   where the field is, used to point at the errors
	 * @param name
	 * @param def
	 * @param resources
	 *   what the index shares between fields, for checking that what the
	 *   field refers to by name exists
	 * @return
	 */
	public static ListIterable<ErrorMessage> validate(
		ObjectLocation location,
		String name,
		FieldDef def,
		ResourcesDef resources
	) {
		var errors = Lists.mutable.<ErrorMessage>empty();

		if(!VALID_NAME_PATTERN.matcher(name).matches()) {
			errors.add(INVALID_NAME.toMessage(location, "name", name));
		}

		if(def.getRequired() && name.contains("*")) {
			errors.add(INVALID_REQUIRED.toMessage(location, "name", name));
		}

		if(def.getPrimaryKey() && name.contains("*")) {
			errors.add(INVALID_PRIMARY_KEY_WILDCARD.toMessage(location, "name", name));
		}

		/*
		 * A pattern stands for fields that only exist once documents name them,
		 * and the fields inside an object are addressed by the dotted path
		 * through it - a path through a field with no settled name never
		 * resolves.
		 */
		if(def.getType().getTypeCase() == TypeCase.OBJECT && name.contains("*")) {
			errors.add(INVALID_OBJECT_WILDCARD.toMessage(location, "name", name));
		}

		if(def.getPrimaryKey() && def.getMultiple()) {
			errors.add(INVALID_PRIMARY_KEY_MULTIPLE.toMessage(location, "name", name));
		}

		if(def.getPrimaryKey() && def.hasRequired() && !def.getRequired()) {
			errors.add(PRIMARY_KEY_NOT_REQUIRED.toMessage(location, "name", name));
		}

		if(def.getPrimaryKey() && def.hasLocales()) {
			errors.add(INVALID_PRIMARY_KEY_LOCALE_SPECIFIC.toMessage(location, "name", name));
		}

		if(def.hasLocales()) {
			/*
			 * Every locale the field names has to be one this build has rules
			 * for, both the default and the declared ones, so that a value in
			 * any of them can actually be analyzed.
			 */
			var config = def.getLocales();
			var named = Lists.mutable.<String>empty();
			if(config.hasDefaultLocale()) {
				named.add(config.getDefaultLocale());
			}
			named.addAllIterable(config.getLocalesList());

			for(var locale : named.distinct()) {
				if(!Locales.isSupported(locale)) {
					errors.add(
						UNSUPPORTED_LOCALE.toMessage(location, "name", name, "locale", locale)
					);
				}
			}
		}

		if(def.hasSort() && def.getMultiple()) {
			errors.add(INVALID_SORTABLE.toMessage(location, "name", name));
		}

		var type = def.getType();
		if(!def.hasType() || type.getTypeCase() == TypeCase.TYPE_NOT_SET) {
			// Without a type there is nothing type specific left to check
			errors.add(MISSING_TYPE.toMessage(location, "name", name));
			return errors;
		}

		var fieldType = FieldTypes.forDef(type);
		if(fieldType.isEmpty()) {
			/*
			 * The type is one this build does not handle, which happens when a
			 * definition was written by a newer version of the engine. Reported
			 * as a validation error so it points at the field rather than
			 * failing the whole index with an unhandled exception.
			 */
			errors.add(
				UNSUPPORTED_TYPE.toMessage(location, "name", name, "type", typeName(type))
			);
			return errors;
		}

		if(def.getPrimaryKey() && !fieldType.get().isPrimaryKeySupported()) {
			errors.add(
				INVALID_PRIMARY_KEY_TYPE
					.toMessage(location, "name", name, "type", typeName(type))
			);
		}

		if(def.hasSort() && !fieldType.get().isSortingSupported()) {
			errors.add(
				SORTING_NOT_SUPPORTED.toMessage(location, "name", name, "type", typeName(type))
			);
		}

		if(def.hasFacet() && !fieldType.get().isDocValuesSupported()) {
			errors.add(
				FACETING_NOT_SUPPORTED.toMessage(location, "name", name, "type", typeName(type))
			);
		}

		errors.addAllIterable(fieldType.get().validate(location, def, resources));

		return errors;
	}

	/**
	 * Get the underlying definition for this field.
	 *
	 * @return
	 */
	public FieldDef getDef() {
		return def;
	}

	/**
	 * Get the type of this field.
	 *
	 * @return
	 */
	public FieldType getType() {
		return type;
	}

	/**
	 * Get the name of the field.
	 *
	 * @return
	 */
	public String getName() {
		return name;
	}

	/**
	 * If the field name contains a wildcard.
	 *
	 * @return
	 */
	public boolean nameHasWildcard() {
		return hasWildcard;
	}

	/**
	 * Check if the given name matches the field name.
	 *
	 * @param name
	 * @return
	 */
	public boolean nameMatches(String name) {
		return namePattern.matcher(name).matches();
	}

	/**
	 * Check if any name this field accepts starts with the given prefix.
	 * {@code false} only when no accepted name can; a name with a wildcard
	 * answers for every name its pattern could match.
	 *
	 * @param prefix
	 * @return
	 */
	public boolean nameCanStartWith(String prefix) {
		var matcher = namePattern.matcher(prefix);

		/*
		 * hitEnd() after a failed match says the input ran out while the
		 * pattern could still have continued - exactly when a longer name
		 * could match.
		 */
		return matcher.matches() || matcher.hitEnd();
	}

	/**
	 * Get if the field is required to be present when a document is added.
	 * Can not be {@code true} if the field name contains a wildcard.
	 *
	 * @return
	 */
	public boolean isRequired() {
		return def.getRequired();
	}

	/**
	 * Get if field can be added multiple times to a document.
	 *
	 * @return
	 */
	public boolean isMultiple() {
		return def.getMultiple();
	}

	/**
	 * Get if the field is stored.
	 *
	 * @return
	 */
	public boolean isStored() {
		return def.getStored();
	}

	/**
	 * Get if the field is locale specific.
	 *
	 * @return
	 */
	public boolean isLocaleSpecific() {
		return def.hasLocales();
	}

	/**
	 * Get if values of this field are objects. Such a field is a container
	 * rather than a value: what can be asked of it is asked of the fields
	 * inside it, addressed by the dotted path through this one.
	 *
	 * @return
	 */
	public boolean isObject() {
		return def.getType().getTypeCase() == TypeCase.OBJECT;
	}

	/**
	 * Get if values of this field are kept as documents of their own, so that
	 * a search can ask that several conditions hold inside the same value.
	 * Only meaningful for an object field; every other object field is
	 * flattened, its values' fields belonging to the document itself.
	 *
	 * @return
	 */
	public boolean isNestedObject() {
		return isObject()
			&& def.getType().getObject().getMode() == ObjectFieldTypeDef.Mode.MODE_NESTED;
	}

	/**
	 * Get the field inside a value of this object field that says which value
	 * it is. Two values of one document may not read the same here, so a value
	 * can be pointed at by what it is rather than by where it sits.
	 *
	 * @return
	 *   the name a value goes by inside the object, or {@code null} when the
	 *   field is not an object or declares no key
	 */
	public String getObjectKey() {
		if(!isObject() || !def.getType().getObject().hasKey()) {
			return null;
		}

		return def.getType().getObject().getKey();
	}

	/**
	 * Get the locale a value of this field is in when it does not carry one.
	 * Only meaningful for a locale specific field.
	 *
	 * @return
	 *   BCP-47 tag, or {@code null} when the field is not locale specific
	 */
	public String getDefaultLocale() {
		return defaultLocale;
	}

	/**
	 * Get every locale this field holds values in - the declared ones and the
	 * default. This is what decides which variants of the field exist, so it
	 * is also every locale a value may carry and a search may ask for.
	 *
	 * @return
	 *   the tags, empty when the field is not locale specific
	 */
	public SetIterable<String> getLocales() {
		return locales;
	}

	/**
	 * Resolve a tag to the variant of this field that answers it, matching as
	 * closely as the declared locales tell apart - a field holding {@code no}
	 * answers {@code nb-NO}. See {@link Locales#resolve(String, SetIterable)}.
	 *
	 * @param locale
	 *   BCP-47 tag
	 * @return
	 *   the declared locale to read, or empty when the field holds no variant
	 *   the tag names
	 */
	public Optional<String> resolveLocale(String locale) {
		return Locales.resolve(locale, locales);
	}

	/**
	 * Get if this field is filled from other locales where it holds no value,
	 * for an index that says to fill them at all.
	 *
	 * @return
	 */
	public boolean isLocaleFallbackEnabled() {
		return def.getLocales().getFallback()
			!= FieldDef.LocaleConfig.Fallback.FALLBACK_DISABLED;
	}

	/**
	 * Get if documents can be narrowed down by the value of this field.
	 *
	 * @return
	 */
	public boolean isFiltered() {
		return def.hasFilter();
	}

	/**
	 * Get if results can be ordered by this field.
	 *
	 * @return
	 */
	public boolean isSorted() {
		return def.hasSort();
	}

	/**
	 * Get if documents are counted per value of this field.
	 *
	 * @return
	 */
	public boolean isFaceted() {
		return def.hasFacet();
	}

	/**
	 * Get if {@link DocValues} should be stored for the field, which is what
	 * counting documents per value is built on.
	 *
	 * @return
	 */
	public boolean isStoreDocValues() {
		return def.hasFacet();
	}
}
