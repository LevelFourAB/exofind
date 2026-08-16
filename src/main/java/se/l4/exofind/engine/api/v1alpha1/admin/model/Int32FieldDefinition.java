package se.l4.exofind.engine.api.v1alpha1.admin.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Definition of a field containing a 32 bit signed integer.
 *
 * A number has nothing to analyze, so it is searched by filtering - which for
 * a number means ranges as well as equality:
 *
 * <pre>
 * {
 *   "type": "int32",
 *   "filter": {},
 *   "validation": { "min": 0 }
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Int32FieldDefinition(
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
		Integer min,
		Integer max
	) {
	}
}
