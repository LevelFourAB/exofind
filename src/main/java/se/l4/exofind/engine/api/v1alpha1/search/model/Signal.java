package se.l4.exofind.engine.api.v1alpha1.search.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Document ranking signals used to adjust relevance scoring.
 *
 * <pre>
 * { "field": "purchases", "saturation": { "pivot": 50 } }
 * { "field": "published", "decay": { "halfLife": 2592000 }, "weight": 0.5 }
 * </pre>
 *
 * <p>Signals modify relevance scores by evaluating document field values.
 * Request signals are added to ranking signals configured on the index unless
 * {@code signalsMode} replaces them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	Document ranking signal used to adjust relevance scoring. Signals apply \
	only when results are ordered by relevance, so an explicit `sort` \
	overrides them. Targeting an unknown field returns \
	`index:query:field_not_found`; targeting a field without sorting enabled \
	returns `index:query:usage_not_enabled`; a signal function unsupported by \
	the field type returns `index:invalid-query-type`. See \
	[Signals](https://exofind.dev/reference/search-api/#signals).""")
public record Signal(
	/**
	 * Field to read the value from, as named in the index definition. Must be a
	 * numeric or timestamp field with sorting enabled.
	 */
	@Schema(
		description = """
			Field to read the value from, as named in the index definition. \
			Must be a numeric or timestamp field with sorting enabled.""",
		required = true,
		examples = "purchases"
	)
	String field,

	/**
	 * Ranks by how far the value is above a pivot. For number fields.
	 */
	@Schema(description = "Ranks by how far the value rises above a pivot. For number fields.")
	Saturation saturation,

	/**
	 * Ranks by how long ago the value was. For timestamp fields.
	 */
	@Schema(description = "Ranks by how long ago the value was. For timestamp fields.")
	Decay decay,

	/**
	 * How much the signal can lift a document at most, as a share of its score.
	 * Defaults to 1.
	 */
	@Schema(
		description = """
			How much the signal can lift a document at most, as a share of its \
			score.""",
		defaultValue = "1"
	)
	Float weight
) {
	/**
	 * Ranks by how far a value is above a pivot, as
	 * {@code value / (value + pivot)} - half at the pivot, approaching but
	 * never reaching one above it.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Ranks a value as `value / (value + pivot)` - half at the pivot, \
		approaching but never reaching one above it.""")
	public record Saturation(
		/**
		 * The value that counts for half of what the signal can give. Must be
		 * above zero.
		 */
		@Schema(
			description = """
				The value that counts for half of what the signal can give. \
				Must be above zero.""",
			required = true,
			exclusiveMinimum = true,
			minimum = "0",
			examples = "50"
		)
		Double pivot
	) {
	}

	/**
	 * Ranks a timestamp by how long ago it was, halving every half life.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "Ranks a timestamp by how long ago it was, halving every half life.")
	public record Decay(
		/**
		 * How many seconds it takes for the signal to be worth half as much.
		 * Must be above zero.
		 */
		@Schema(
			description = """
				How many seconds it takes for the signal to be worth half as \
				much. Must be above zero.""",
			required = true,
			exclusiveMinimum = true,
			minimum = "0",
			examples = "604800"
		)
		Long halfLife
	) {
	}
}
