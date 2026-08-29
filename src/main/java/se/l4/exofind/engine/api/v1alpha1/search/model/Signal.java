package se.l4.exofind.engine.api.v1alpha1.search.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One value of the documents themselves, taken into their relevance, as it is
 * written on the wire.
 *
 * <pre>
 * { "field": "purchases", "saturation": { "pivot": 50 } }
 * { "field": "published", "decay": { "halfLife": 2592000 }, "weight": 0.5 }
 * </pre>
 *
 * A search carrying signals ranks by those instead of the ones the index
 * declares, which is how a ranking is tried out before it is adopted. An empty
 * list ranks by how well documents match alone.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	A document value taken into relevance scoring. Signals apply only when \
	results are ordered by relevance, so an explicit `sort` overrides them. \
	Targeting an unknown field returns `index:query:field_not_found`; \
	targeting a field without sorting enabled returns \
	`index:query:usage_not_enabled`; a signal function the field type does not \
	support returns `index:invalid-query-type`. See \
	[Signals](https://exofind.dev/reference/search-api/#signals).""")
public record Signal(
	/**
	 * Name of the field to read the value from, as it is called in the
	 * definition of the index. Has to be a number or a timestamp defined for
	 * sorting.
	 */
	@Schema(
		description = """
			Field to read the value from, as named in the index definition. \
			Must be a number or timestamp field with sorting enabled.""",
		required = true,
		examples = "purchases"
	)
	String field,

	/**
	 * Rank by how far the value is above a pivot. For numbers.
	 */
	@Schema(description = "Ranks by how far the value rises above a pivot. For number fields.")
	Saturation saturation,

	/**
	 * Rank by how long ago the value was. For timestamps.
	 */
	@Schema(description = "Ranks by how long ago the value was. For timestamp fields.")
	Decay decay,

	/**
	 * How much the signal can lift a document at most, as a share of its
	 * score. Left out for 1.
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
	 * Rank by how far a value is above a pivot, as
	 * {@code value / (value + pivot)} - half at the pivot, approaching but
	 * never reaching one above it.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Ranks a value as `value / (value + pivot)` - half at the pivot, \
		approaching but never reaching one above it.""")
	public record Saturation(
		/**
		 * The value that counts for half of what the signal can give. Has to be
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
	 * Rank by how long ago a value was, halving every half life.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "Ranks a timestamp by how long ago it was, halving every half life.")
	public record Decay(
		/**
		 * How many seconds it takes for the signal to be worth half as much.
		 * Has to be above zero.
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
