package se.l4.exofind.engine.api.v1alpha1.admin.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Definition of a field containing a boolean.
 *
 * A boolean has nothing to analyze, so the only way to search it is to filter
 * on it:
 *
 * <pre>
 * {
 *   "type": "boolean",
 *   "filter": {}
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BooleanFieldDefinition(
	Boolean primaryKey,
	Boolean required,
	Boolean multiple,
	Boolean stored,
	Locales locales,
	Filter filter,
	Sort sort,
	Facet facet
) implements FieldDefinition {
}
