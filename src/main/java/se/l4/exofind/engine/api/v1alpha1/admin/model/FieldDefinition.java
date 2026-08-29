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
 * Fields are represented as a tagged union, where {@code type} selects the
 * type of the field and the properties available on it:
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
 * A way of using a field is turned on by including its configuration rather
 * than by a flag, so that options can be added to it without changing the
 * shape of what callers already send. An empty object enables it with the
 * defaults of the engine.
 *
 * The ways that work the same whatever the field holds - filtering, sorting,
 * faceting - are on this interface. The ones that depend on how text is
 * analyzed are on the types that have them.
 *
 * Properties are nullable so that the API can tell a value that was left out
 * from one that was explicitly set to its default. Only what the caller sends
 * is stored, which keeps defaults owned by the engine.
 *
 * Adding a field type is done by adding a record here, permitting it in this
 * interface and extending the mapping in
 * {@code se.l4.exofind.engine.api.v1alpha1.admin.IndexDefinitionMapper}. The
 * types available are the ones the engine can index, which is currently
 * strings, booleans, numbers and vectors.
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
	Definition of one field, structured as a tagged union where `type` selects \
	the field type and the properties available on it. Field usages are \
	opt-in: an empty configuration object enables a usage with engine \
	defaults, and only explicitly configured properties are stored, so \
	defaults stay owned by the engine across upgrades. See [Field \
	types](https://exofind.dev/reference/field-types/).""")
public sealed interface FieldDefinition
	permits StringFieldDefinition, BooleanFieldDefinition, VectorFieldDefinition,
		Int32FieldDefinition, Int64FieldDefinition, FloatFieldDefinition,
		DoubleFieldDefinition, TimestampFieldDefinition, GeoPointFieldDefinition,
		ObjectFieldDefinition {
	/**
	 * Descriptions of the properties every field type carries, kept here so
	 * that the ten records implementing this interface describe them the same
	 * way. An annotation takes a constant expression, and a text block is one,
	 * so each record refers to these rather than repeating the text.
	 */
	String ROLE_DESCRIPTION = """
		What the field is for, as a name that stands for a combination of \
		usages. The combination is expanded into the definition before it is \
		stored, and anything set beside the role is kept as given. Which roles \
		a type accepts is listed under [Field \
		roles](https://exofind.dev/reference/field-types/#field-roles).""";

	String PRIMARY_KEY_DESCRIPTION = """
		Marks the field as the unique document identifier. Documents with \
		matching primary keys overwrite existing documents, and an index can \
		have at most one primary key. A primary key must be `required` and \
		cannot be `multiple`, locale-specific, or a wildcard field.""";

	String REQUIRED_DESCRIPTION = """
		When `true`, documents that lack a value for this field are \
		rejected.""";

	String MULTIPLE_DESCRIPTION = """
		When `true`, the field accepts several values in one document. When \
		`false`, a document carrying several values for it is rejected.""";

	String STORED_DESCRIPTION = """
		When `true`, values are stored so they can be returned in search \
		results. Only matters on an index whose `source` is `none`, since a \
		document is otherwise kept whole.""";

	String LOCALES_DESCRIPTION = """
		Makes values locale-specific, so analysis and collation follow the \
		locale each value carries.""";

	String FILTER_DESCRIPTION = """
		Enables narrowing results to the documents holding a given value. \
		Filtering is exact whatever the type; on numeric and timestamp fields \
		it also enables range matching.""";

	String SORT_DESCRIPTION = "Enables ordering results by the value of this field.";

	String FACET_DESCRIPTION = """
		Enables counting how many documents share each value of this field. On \
		numeric and timestamp fields it also enables range buckets.""";

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
	 * What a field is for, standing for the combination of usages that serves
	 * it.
	 *
	 * <p>A role is expanded into the field before the definition is stored, so
	 * reading the definition back shows the usages rather than the role, and
	 * what a role stands for can not shift under an index that already exists.
	 * Anything given beside the role is kept as it was given, whether it is
	 * part of what the role turns on or not.
	 *
	 * <p>Each role belongs to the field types that can answer for it, and
	 * naming one on another type is refused with
	 * {@code index:field:role:not_valid_for_type}. Inside an object field a
	 * role turns on only what an object field accepts, so it leaves
	 * {@code stored} and {@code highlight} alone, and leaves {@code sort} alone
	 * where the object is a flattened list.
	 */
	@Schema(description = """
		What a field is for, standing for the combination of usages that serves \
		it. The combination is expanded before the definition is stored, so \
		reading it back shows the usages rather than the role. Each role \
		belongs to the field types that can answer for it.""")
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
	 * How values of a field being locale specific behaves.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Configures locale-specific field values. See [Localize \
		fields](https://exofind.dev/how-to/localize-fields/).""")
	record Locales(
		/**
		 * The locale assumed for a value that does not carry one (BCP-47).
		 */
		@Schema(
			description = """
				BCP-47 locale assumed for a value that carries none.""",
			examples = "sv"
		)
		String defaultLocale,

		/**
		 * The locales the field holds values in, besides the default. A value
		 * carrying a locale that is not listed here (or the default) is
		 * refused, and these are the locales a search can ask the field for.
		 */
		@Schema(description = """
			The locales the field holds values in, besides the default. A value \
			carrying a locale named neither here nor as the default is \
			refused, and these are the locales a search can ask the field \
			for.""")
		List<String> locales,

		/**
		 * Whether this field takes part in the {@code localeFallback} of the
		 * index. Left out to take part, the same as {@code enabled}.
		 *
		 * Only read for an index that declares a fallback. Saying
		 * {@code enabled} where it does not is refused, as nothing would fill
		 * anything.
		 */
		@Schema(
			description = """
				Whether this field takes part in the index's `localeFallback`. \
				Only read on an index that declares a fallback; saying \
				`enabled` where it does not is refused, as nothing would fill \
				anything.""",
			defaultValue = "enabled"
		)
		Fallback fallback
	) {
		@Schema(description = """
			Whether a field takes part in the index's locale fallback: \
			`enabled` fills the locales it holds no value in, `disabled` \
			leaves them empty.""")
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
	 * How filtering on a field behaves. Filtering is exact whatever the type;
	 * how a string is normalized before it is compared exactly is the
	 * {@code keyword} config of the string type.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Enables filtering. Carries no options - filtering is exact whatever the \
		type, and how a string is normalized before it is compared is the \
		`keyword` config of the string type.""")
	record Filter() {
	}

	/**
	 * How ordering by a field behaves.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "Enables sorting, and says how values compare.")
	record Sort(
		/**
		 * How two values are ordered relative to each other. Only meaningful
		 * for strings, which default to {@code locale}.
		 */
		@Schema(
			description = """
				How two values compare. Only meaningful for strings.""",
			defaultValue = "locale"
		)
		Collation collation,

		/**
		 * Where documents without a value are placed when ordering ascending.
		 * Defaults to {@code last}.
		 */
		@Schema(
			description = """
				Where documents holding no value are placed when ordering \
				ascending.""",
			defaultValue = "last"
		)
		Missing missing
	) {
		@Schema(description = """
			How two string values compare: `locale` orders by the rules of the \
			locale, so `å` sorts where a reader of that locale expects it; \
			`binary` orders by bytes, which is faster but only reads correctly \
			for plain ASCII.""")
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
			Where documents holding no value are placed when ordering \
			ascending: `first` or `last`.""")
		public enum Missing {
			@JsonProperty("first")
			FIRST,

			@JsonProperty("last")
			LAST
		}
	}

	/**
	 * How counting documents per value of a field behaves.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Enables counting documents per value of the field. Carries no \
		options.""")
	record Facet() {
	}
}
