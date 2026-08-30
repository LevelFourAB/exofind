package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Defines a field whose values are objects, described in {@code fields}.
 *
 * <p>Storage for multiple objects is configured using {@code mode}. A
 * {@code flattened} object indexes child fields directly into the parent
 * document structure under their dot-notation paths (such as
 * {@code dimensions.width}), where they are filtered, matched, and aggregated
 * independently. A {@code nested} object retains each object instance as an
 * isolated sub-document, allowing queries to match multiple conditions against
 * the same object value through the {@code nested} search clause. Set the field
 * to {@code multiple} to store a list of values:
 *
 * <pre>
 * {
 *   "type": "object",
 *   "multiple": true,
 *   "mode": "nested",
 *   "fields": {
 *     "color": { "type": "string", "filter": {} },
 *     "price": { "type": "double", "filter": {} }
 *   }
 * }
 * </pre>
 *
 * <p>The mode is required when the field is {@code multiple}, where the two
 * storage modes evaluate queries differently: flattened, {@code color = red}
 * and {@code price < 10} match a document where one object value is red and
 * another is cheap, whereas nested evaluates both conditions on the same object
 * value. Single object fields are always indexed as flattened objects, and
 * {@code mode} is rejected.
 *
 * <p>An array of object values can name one of its child fields as its
 * {@code key} to identify each object value: an update path targets a value as
 * {@code variants[V-2]}, and a search hit returns the key beside the position.
 * Key values must be unique within a document.
 *
 * <p>Child fields support filtering, matching, autocomplete, faceting,
 * validation, and being marked required or multiple. Sorting on child fields is
 * supported in single objects and nested mode. Primary keys, highlighting,
 * locales, stored values, and nested object fields are rejected inside object
 * fields. Object fields are returned in search results only when the index
 * preserves document sources.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	Represents structured object values containing nested field definitions, \
	referenced by dot notation (such as `variants.price`). An object field \
	cannot configure `filter`, `sort`, `facet`, `locales`, or `stored`, and \
	its name cannot use wildcards. An array of objects can specify a `key` to \
	identify each object value. Object fields are returned in search results \
	only when the index preserves document sources. See \
	[`object`](https://exofind.dev/reference/field-types/#object).""")
public record ObjectFieldDefinition(
	@Schema(description = "Not supported on an object field; setting it is rejected.")
	Boolean primaryKey,

	@Schema(description = FieldDefinition.REQUIRED_DESCRIPTION, defaultValue = "false")
	Boolean required,

	@Schema(
		description = """
			When `true`, the field holds a list of object values, and `mode` \
			is required. Single object fields are always indexed as flattened \
			objects.""",
		defaultValue = "false"
	)
	Boolean multiple,

	@Schema(description = "Not supported on an object field; setting it is rejected.")
	Boolean stored,

	@Schema(description = "Not supported on an object field; setting it is rejected.")
	Locales locales,

	@Schema(description = "Not supported on an object field; setting it is rejected.")
	Filter filter,

	@Schema(description = "Not supported on an object field; setting it is rejected.")
	Sort sort,

	@Schema(description = "Not supported on an object field; setting it is rejected.")
	Facet facet,

	/**
	 * Storage mode for multiple objects. Required when the field is
	 * {@code multiple}, and rejected when it is not.
	 */
	@Schema(description = """
		Storage mode for multiple objects. Required when `multiple` is `true` \
		(`index:field:object:mode_required`) and rejected when it is not \
		(`index:field:object:mode_without_multiple`).""")
	Mode mode,

	/**
	 * Names a child field as the unique identifier for each object value.
	 * Supported only when the field is {@code multiple}.
	 */
	@Schema(description = """
		Names a child field as the unique identifier for each object value in \
		an array. Targets object values in update paths (such as \
		`variants[V-2]`) and populates `key` on search value hits. Requires \
		`multiple: true` (`index:field:object:key_without_multiple`). Must \
		name a field defined in `fields` (`index:field:object:key_not_found`) \
		that is `required`, not `multiple`, and of type `string`, `int32`, or \
		`int64` (`index:field:object:key_not_valid`). Duplicate key values \
		within a document are rejected with \
		`index:update:object:key_duplicate`.""")
	String key,

	/**
	 * Map of child field names to field definitions.
	 */
	@Schema(description = """
		Map of child field names to field definitions. Child fields can \
		configure `filter`, `matching`, `autocomplete`, `facet`, `validation`, \
		`required`, and `multiple`. Setting `primaryKey`, `highlight`, \
		`locales`, `stored`, wildcard names, or nested `object` types is \
		rejected. Sorting on a child field is supported in single objects and \
		in `nested` mode, and is rejected in `flattened` mode with \
		`index:field:object:flattened_sort`.""")
	Map<String, FieldDefinition> fields
) implements FieldDefinition {
	/**
	 * Defines how object values are indexed relative to the parent document.
	 */
	@Schema(description = """
		Storage mode for multiple objects. `nested` retains each object \
		instance as an isolated sub-document for use with the `nested` clause, \
		matched values, and value hits. `flattened` indexes child fields \
		directly into the parent document structure under their dot-notation \
		paths, and object boundaries are not preserved.""")
	public enum Mode {
		/**
		 * Every value is one unit, so a search can ask that several
		 * conditions hold inside the same value.
		 */
		@JsonProperty("nested")
		NESTED,

		/**
		 * The fields of every value belong to the document itself, matched
		 * independently of which value they sit in.
		 */
		@JsonProperty("flattened")
		FLATTENED
	}
}
