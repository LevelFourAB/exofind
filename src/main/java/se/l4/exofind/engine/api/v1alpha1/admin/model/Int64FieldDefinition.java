package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Defines a field containing a 64 bit signed integer.
 *
 * <p>Numeric fields do not support text analysis and are searched by filtering,
 * which supports exact matches and range queries:
 *
 * <pre>
 * {
 *   "type": "int64",
 *   "filter": {},
 *   "sort": {}
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	Represents a 64-bit signed integer. Numeric fields do not support text \
	analysis and are searched by filtering, which supports exact matches and \
	range queries.""")
public record Int64FieldDefinition(
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
		Sets allowed numeric bounds. Documents containing values outside these \
		bounds are rejected.""")
	Validation validation
) implements FieldDefinition {
	/**
	 * The values the field accepts. Documents containing values outside these
	 * bounds are rejected.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "Int64Validation",
		description = """
			Sets the allowed numeric bounds for an `int64` field. Documents \
			containing values outside these bounds are rejected."""
	)
	public record Validation(
		@Schema(description = "Lowest value accepted.")
		Long min,

		@Schema(description = "Highest value accepted.")
		Long max
	) {
	}
}
