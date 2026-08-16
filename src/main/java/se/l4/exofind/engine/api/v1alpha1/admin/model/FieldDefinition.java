package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

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
public sealed interface FieldDefinition
	permits StringFieldDefinition, BooleanFieldDefinition, VectorFieldDefinition,
		Int32FieldDefinition, Int64FieldDefinition, FloatFieldDefinition,
		DoubleFieldDefinition, TimestampFieldDefinition, GeoPointFieldDefinition,
		ObjectFieldDefinition {
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
	 * How values of a field being locale specific behaves.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Locales(
		/**
		 * The locale assumed for a value that does not carry one (BCP-47).
		 */
		String defaultLocale,

		/**
		 * The locales the field holds values in, besides the default. A value
		 * carrying a locale that is not listed here (or the default) is
		 * refused, and these are the locales a search can ask the field for.
		 */
		List<String> locales,

		/**
		 * Whether this field takes part in the {@code localeFallback} of the
		 * index. Left out to take part, the same as {@code enabled}.
		 *
		 * Only read for an index that declares a fallback. Saying
		 * {@code enabled} where it does not is refused, as nothing would fill
		 * anything.
		 */
		Fallback fallback
	) {
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
	record Filter() {
	}

	/**
	 * How ordering by a field behaves.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Sort(
		/**
		 * How two values are ordered relative to each other. Only meaningful
		 * for strings, which default to {@code locale}.
		 */
		Collation collation,

		/**
		 * Where documents without a value are placed when ordering ascending.
		 * Defaults to {@code last}.
		 */
		Missing missing
	) {
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
	record Facet() {
	}
}
