package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Definition of a single field in an {@link IndexDefinition}.
 *
 * <p>Fields are structured as a tagged union, where {@code type} selects the
 * field type and the properties available on it:
 *
 * <pre>
 * {
 *   "type": "string",
 *   "stored": true,
 *   "filter": {},
 *   "matching": { "highlight": {} }
 * }
 * </pre>
 *
 * <p>Field usages are opt-in. Adding an empty configuration object enables a
 * usage with engine defaults, allowing options to be added without changing
 * existing request payloads.
 *
 * <p>Common usages such as filtering, sorting, and faceting apply across field
 * types, while text analysis options are defined on the types that support
 * them.
 *
 * <p>Properties are optional so omitted values are distinguished from explicit
 * defaults. The engine stores only explicitly configured properties, preserving
 * default values across engine updates.
 */
@JsonTypeInfo(
	use = JsonTypeInfo.Id.NAME,
	include = JsonTypeInfo.As.PROPERTY,
	property = "type"
)
@JsonSubTypes({
	@JsonSubTypes.Type(value = StringFieldDefinition.class, name = "string"),
	@JsonSubTypes.Type(value = BooleanFieldDefinition.class, name = "boolean"),
	@JsonSubTypes.Type(value = VectorFieldDefinition.class, name = "vector"),
	@JsonSubTypes.Type(value = Int32FieldDefinition.class, name = "int32"),
	@JsonSubTypes.Type(value = Int64FieldDefinition.class, name = "int64"),
	@JsonSubTypes.Type(value = FloatFieldDefinition.class, name = "float"),
	@JsonSubTypes.Type(value = DoubleFieldDefinition.class, name = "double"),
	@JsonSubTypes.Type(value = TimestampFieldDefinition.class, name = "timestamp"),
	@JsonSubTypes.Type(value = GeoPointFieldDefinition.class, name = "geo_point"),
	@JsonSubTypes.Type(value = ObjectFieldDefinition.class, name = "object")
})
@Schema(description = """
	Definition of a field, structured as a tagged union where `type` selects \
	the field type and the properties available on it. Field usages are \
	opt-in: adding an empty configuration object enables a usage with engine \
	defaults, and only explicitly configured properties are stored, preserving \
	default values across engine updates. See [Field \
	types](https://exofind.dev/reference/field-types/).""")
public sealed interface FieldDefinition
	permits StringFieldDefinition, BooleanFieldDefinition, VectorFieldDefinition,
		Int32FieldDefinition, Int64FieldDefinition, FloatFieldDefinition,
		DoubleFieldDefinition, TimestampFieldDefinition, GeoPointFieldDefinition,
		ObjectFieldDefinition {
	/**
	 * Descriptions of common field properties shared across all field type
	 * definitions.
	 */
	String ROLE_DESCRIPTION = """
		Specifies a field role that applies a preset combination of usages. \
		The role expands into explicit field properties before the definition \
		is stored, and any property set alongside the role is preserved as \
		given. Supported roles per type are listed under [Field \
		roles](https://exofind.dev/reference/field-types/#field-roles).""";

	String PRIMARY_KEY_DESCRIPTION = """
		Marks the field as the unique document identifier. Documents with \
		matching primary keys overwrite existing documents. An index can have \
		at most one primary key. Primary key fields must be `required` and \
		cannot be `multiple`, locale-specific, or wildcard fields.""";

	String REQUIRED_DESCRIPTION = """
		When `true`, the engine rejects documents that lack a value for this \
		field.""";

	String MULTIPLE_DESCRIPTION = """
		When `true`, the field accepts multiple values in a single document. \
		If `false`, the engine rejects documents containing multiple values \
		for the field.""";

	String STORED_DESCRIPTION = """
		When `true`, the engine stores field values to return in search \
		results. This setting applies only when `source` is set to `none`, as \
		documents are otherwise preserved in full.""";

	String LOCALES_DESCRIPTION = """
		Configures locale-specific field values so that analysis and collation \
		follow the locale of each value. On an index that declares `locales`, \
		configuring `{}` gives the field every declared locale, and `only` \
		narrows the field to a subset of those locales.""";

	String FILTER_DESCRIPTION = """
		Enables filtering search results by exact field value. On numeric and \
		timestamp fields, filtering also enables range queries.""";

	String SORT_DESCRIPTION = "Enables sorting search results by field value.";

	String FACET_DESCRIPTION = """
		Enables value count aggregations. On numeric and timestamp fields, it \
		also enables range buckets.""";

	/**
	 * If this field is the primary key of the index. An index has at most one
	 * primary key, and documents with the same primary key replace each other.
	 */
	Boolean primaryKey();

	/**
	 * If this field must be present when a document is added.
	 */
	Boolean required();

	/**
	 * If this field can have several values in the same document.
	 */
	Boolean multiple();

	/**
	 * If the value of this field is kept so it can be returned in results.
	 */
	Boolean stored();

	/**
	 * Makes values of this field locale specific, so analysis and collation
	 * follow the locale each value carries.
	 */
	Locales locales();

	/**
	 * Enables narrowing results down to the documents that have a given value.
	 */
	Filter filter();

	/**
	 * Enables ordering results by this field.
	 */
	Sort sort();

	/**
	 * Enables counting how many documents share each value of this field.
	 */
	Facet facet();

	/**
	 * A role defines a preset combination of usages for a common kind of field.
	 *
	 * <p>The engine expands a role into explicit field properties before
	 * storing the index definition. When you read the definition back, the
	 * engine returns the individual usages rather than the role name, ensuring
	 * that modifying a role's meaning does not change an existing index. Any
	 * property set beside a role is kept exactly as given.
	 *
	 * <p>Each role applies only to the field types that support it; setting a
	 * role on an unsupported type is rejected with
	 * {@code index:field:role:not_valid_for_type}. Inside an object field, a
	 * role enables only what the enclosing object context accepts, omitting
	 * {@code stored} and {@code highlight}, and omitting {@code sort} inside a
	 * flattened list.
	 */
	@Schema(description = """
		Defines a preset combination of usages for a common kind of field. The \
		engine expands a role into explicit field properties before storing \
		the index definition, so reading the definition back returns the \
		individual usages rather than the role name. Each role applies only to \
		the field types that support it.""")
	enum Role {
		/**
		 * The unique key of the document, on a {@code string} field. Turns on
		 * {@code primaryKey}, {@code required}, {@code stored} and
		 * {@code filter}. Refused inside an object field, which can hold no
		 * primary key.
		 */
		@JsonProperty("id")
		ID,

		/**
		 * The name a reader identifies the document by, on a {@code string}
		 * field. Turns on {@code stored}, {@code sort}, {@code autocomplete}
		 * and a {@code matching} raised to three times the weight of an
		 * ordinary field, ranking a whole-value match above a partial one,
		 * tolerating typing mistakes, highlighting, and counting length fully.
		 */
		@JsonProperty("title")
		TITLE,

		/**
		 * Prose about the document, on a {@code string} field. Turns on
		 * {@code stored} and a {@code matching} that highlights, tolerates
		 * typing mistakes and does not count length against a longer text.
		 */
		@JsonProperty("description")
		DESCRIPTION,

		/**
		 * A label the documents are narrowed and counted by, on a
		 * {@code string} field. Turns on {@code filter}, {@code facet} and a
		 * {@code matching} that keeps each word whole, so a search for the
		 * label finds the documents carrying it.
		 */
		@JsonProperty("tag")
		TAG,

		/**
		 * A label that is a path through a tree, such as
		 * {@code Men/Shoes/Running}, on a {@code string} field. Turns on
		 * {@code filter}, {@code facet} and {@code hierarchy} with the default
		 * separator.
		 */
		@JsonProperty("path")
		PATH,

		/**
		 * An identifier a person reads and types - a SKU, an order number, a
		 * slug - on a {@code string} field. Turns on {@code stored},
		 * {@code filter}, and {@code matching} and {@code autocomplete} that
		 * keep each word whole rather than stemming it, and that do not count
		 * length. Typo tolerance is left off, because an identifier one
		 * character away is a different one.
		 */
		@JsonProperty("code")
		CODE,

		/**
		 * A point in time the documents are narrowed, ordered and counted by,
		 * on a {@code timestamp} field. Turns on {@code filter}, {@code sort}
		 * and {@code facet}.
		 */
		@JsonProperty("timestamp")
		TIMESTAMP,

		/**
		 * A place the documents are narrowed and ordered by distance from, on a
		 * {@code geo_point} field. Turns on {@code filter} and {@code sort}.
		 */
		@JsonProperty("geo")
		GEO
	}

	/**
	 * Configures locale-specific field values.
	 *
	 * <p>An index that declares {@link IndexDefinition.Locales} gives its
	 * locales to every field that opts in with {@code "locales": {}}.
	 * {@code only} narrows a field to fewer of them.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Configures locale-specific field values. On an index that declares \
		`locales`, configuring `{}` gives the field every declared locale, and \
		`only` narrows the field to a subset of those locales. See [Localize \
		fields](https://exofind.dev/how-to/localize-fields/).""")
	record Locales(
		/**
		 * The BCP-47 fallback locale assumed for values that carry no locale.
		 * Defaults to the {@code defaultLocale} of the index on an index that
		 * declares {@code locales}.
		 */
		@Schema(
			description = """
				Specifies the BCP-47 fallback locale for values that carry no \
				explicit locale. On an index that declares `locales`, this \
				property defaults to the `defaultLocale` of the index.""",
			examples = "sv"
		)
		String defaultLocale,

		/**
		 * List of supported locales for the field, in addition to the default
		 * locale. A value carrying a locale that is neither listed here nor set
		 * as default is rejected, and queries can target any listed locale.
		 *
		 * <p>Rejected on an index that declares {@code locales}, which narrows
		 * a field with {@code only} instead. Reading a definition back always
		 * returns this list.
		 */
		@Schema(description = """
			Lists the supported locales for the field, in addition to the \
			default locale. The engine rejects documents containing values \
			with unlisted locales, and queries can target any listed locale. \
			The engine rejects this property on an index that declares \
			`locales`, which narrows a field with `only` instead. Reading an \
			index definition back always returns this list on each field.""")
		List<String> locales,

		/**
		 * The locales this field holds, out of the ones the index declares.
		 * Omitted, the field holds all of them.
		 *
		 * <p>Every tag must be one the index declares, and the list must
		 * contain the locale the field defaults to. A shorter list means fewer
		 * fallback copies for a document that carries no value in every locale.
		 *
		 * <p>Expanded into {@code defaultLocale} and {@code locales} before the
		 * definition is stored. Reading a definition back returns those
		 * instead.
		 */
		@Schema(description = """
			Specifies the locales this field holds from the locales declared \
			in index-level `locales`. If omitted, the field holds all declared \
			locales. Every tag must be one the index declares, and the list \
			must contain the default locale of the field. The engine expands \
			this property into `defaultLocale` and `locales` before storing \
			the index definition, so reading a definition back returns those \
			properties instead.""")
		List<String> only,

		/**
		 * Controls whether this field participates in the
		 * {@code localeFallback} of the index. Omitted, the field participates
		 * in fallback, equivalent to {@code enabled}.
		 *
		 * <p>Evaluated only on an index that declares a fallback configuration.
		 * Setting {@code enabled} on an index without fallback is rejected.
		 */
		@Schema(
			description = """
				Controls whether this field participates in the index's \
				`localeFallback`. Only evaluated on an index that declares a \
				fallback; setting `enabled` on an index without fallback \
				configuration is rejected.""",
			defaultValue = "enabled"
		)
		Fallback fallback
	) {
		@Schema(description = """
			Controls whether a field participates in the index's locale \
			fallback: `enabled` populates missing locales from fallback \
			values, and `disabled` excludes the field from fallback \
			resolution.""")
		public enum Fallback {
			/**
			 * Fill the locales this field holds no value in, the way the index
			 * says to.
			 */
			@JsonProperty("enabled")
			ENABLED,

			/**
			 * Leave the locales this field holds no value in empty, even where
			 * the index fills them.
			 */
			@JsonProperty("disabled")
			DISABLED
		}
	}

	/**
	 * Enables filtering search results by exact field value. Filtering is exact
	 * across all types; exact-match normalization for string fields is
	 * configured under {@code keyword}.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Enables filtering search results by exact field value. Filtering is \
		exact across all types; exact-match normalization for string fields is \
		configured under `keyword`.""")
	record Filter() {
	}

	/**
	 * Enables sorting search results by field value.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Enables sorting search results by field value and configures value \
		comparison.""")
	record Sort(
		/**
		 * Collation order used when comparing values. Applies only to string
		 * fields, which default to {@code locale}.
		 */
		@Schema(
			description = """
				Collation order used when comparing values. Applies only to \
				string fields.""",
			defaultValue = "locale"
		)
		Collation collation,

		/**
		 * Places documents without values first or last when sorting in
		 * ascending order. Defaults to {@code last}.
		 */
		@Schema(
			description = """
				Places documents without values first or last when sorting in \
				ascending order.""",
			defaultValue = "last"
		)
		Missing missing
	) {
		@Schema(description = """
			Collation order for string comparisons: `locale` orders by the \
			rules of the locale so characters such as `å` sort in expected \
			language order; `binary` orders by byte order, which is faster for \
			plain ASCII text.""")
		public enum Collation {
			/**
			 * Order by the bytes of the value. Fast, but only reads correctly
			 * for plain ASCII.
			 */
			@JsonProperty("binary")
			BINARY,

			/**
			 * Order by the rules of the locale, so `å` sorts where a reader of
			 * that locale expects it rather than after `z`.
			 */
			@JsonProperty("locale")
			LOCALE
		}

		@Schema(description = """
			Placement of documents without values when sorting in ascending \
			order: `first` or `last`.""")
		public enum Missing {
			@JsonProperty("first")
			FIRST,

			@JsonProperty("last")
			LAST
		}
	}

	/**
	 * Enables value count aggregations across search results.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Enables value count aggregations across search results. Carries no \
		configuration options.""")
	record Facet() {
	}
}
