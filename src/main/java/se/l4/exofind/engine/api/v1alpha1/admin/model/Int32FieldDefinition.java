package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.media.SchemaProperty;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Defines a field containing a 32 bit signed integer.
 *
 * <p>Numeric fields do not support text analysis and are searched by filtering,
 * which supports exact matches and range queries:
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
@Schema(
	description = """
		Represents a 32-bit signed integer. Numeric fields do not support text \
		analysis and are searched by filtering, which supports exact matches \
		and range queries.""",
	examples = Int32FieldDefinition.EXAMPLE,
	properties = @SchemaProperty(
		name = "type",
		type = SchemaType.STRING,
		enumeration = "int32",
		description = FieldDefinition.TYPE_DESCRIPTION
	),
	requiredProperties = "type"
)
public record Int32FieldDefinition(
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

	@Schema(description = FieldDefinition.SIGNAL_DESCRIPTION)
	Signal signal,

	@Schema(description = """
		Sets allowed numeric bounds. Documents containing values outside these \
		bounds are rejected.""")
	Validation validation,

	@Schema(description = FieldDefinition.UNIT_DESCRIPTION, examples = "SEK")
	String unit
) implements FieldDefinition {
	/** The example field, as the JSON a caller writes. */
	public static final String EXAMPLE = """
		{ "type": "int32", "filter": {}, "validation": { "min": 0 } }""";

	/**
	 * The values the field accepts. Documents containing values outside these
	 * bounds are rejected.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "Int32Validation",
		description = """
			Sets the allowed numeric bounds for an `int32` field. Documents \
			containing values outside these bounds are rejected.""",
		examples = Validation.EXAMPLE
	)
	public record Validation(
		@Schema(description = "Lowest value accepted.", examples = "0")
		Integer min,

		@Schema(description = "Highest value accepted.")
		Integer max
	) {
		/** The example bounds, as the JSON a caller writes. */
		public static final String EXAMPLE = """
			{ "min": 0, "max": 100 }""";
	}
}
