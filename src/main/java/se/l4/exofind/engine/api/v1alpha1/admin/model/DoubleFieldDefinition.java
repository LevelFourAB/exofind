package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Definition of a field containing a 64 bit floating point number.
 *
 * A number has nothing to analyze, so it is searched by filtering - which for
 * a number means ranges as well as equality:
 *
 * <pre>
 * {
 *   "type": "double",
 *   "filter": {},
 *   "sort": {}
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	A 64-bit floating point number. A number has nothing to analyze, so it is \
	searched by filtering - which for a number means ranges as well as \
	equality.""")
public record DoubleFieldDefinition(
	@Schema(description = FieldDefinition.PRIMARY_KEY_DESCRIPTION, defaultValue = "false")
	Boolean primaryKey,

	@Schema(description = FieldDefinition.REQUIRED_DESCRIPTION, defaultValue = "false")
	Boolean required,

	@Schema(description = FieldDefinition.MULTIPLE_DESCRIPTION, defaultValue = "false")
	Boolean multiple,

	@Schema(description = FieldDefinition.STORED_DESCRIPTION, defaultValue = "false")
	Boolean stored,

	@Schema(description = FieldDefinition.LOCALES_DESCRIPTION)
	Locales locales,

	@Schema(description = FieldDefinition.FILTER_DESCRIPTION)
	Filter filter,

	@Schema(description = FieldDefinition.SORT_DESCRIPTION)
	Sort sort,

	@Schema(description = FieldDefinition.FACET_DESCRIPTION)
	Facet facet,

	@Schema(description = """
		Bounds the values the field accepts. A document holding a value \
		outside them is rejected.""")
	Validation validation
) implements FieldDefinition {
	/**
	 * The values the field accepts. A value outside the bounds is refused when
	 * a document is added.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "DoubleValidation",
		description = "The values a `double` field accepts."
	)
	public record Validation(
		@Schema(description = "Lowest value accepted.")
		Double min,

		@Schema(description = "Highest value accepted.")
		Double max
	) {
	}
}
