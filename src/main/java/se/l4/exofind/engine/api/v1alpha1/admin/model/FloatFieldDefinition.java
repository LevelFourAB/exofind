package se.l4.exofind.engine.api.v1alpha1.admin.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Definition of a field containing a 32 bit floating point number.
 *
 * A number has nothing to analyze, so it is searched by filtering - which for
 * a number means ranges as well as equality:
 *
 * <pre>
 * {
 *   "type": "float",
 *   "filter": {},
 *   "sort": {}
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FloatFieldDefinition(
	Boolean primaryKey,
	Boolean required,
	Boolean multiple,
	Boolean stored,
	Locales locales,
	Filter filter,
	Sort sort,
	Facet facet,
	Validation validation
) implements FieldDefinition {
	/**
	 * The values the field accepts. A value outside the bounds is refused when
	 * a document is added.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Validation(
		Float min,
		Float max
	) {
	}
}
