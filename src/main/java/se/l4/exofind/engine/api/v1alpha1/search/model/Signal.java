package se.l4.exofind.engine.api.v1alpha1.search.model;

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
public record Signal(
	/**
	 * Name of the field to read the value from, as it is called in the
	 * definition of the index. Has to be a number or a timestamp defined for
	 * sorting.
	 */
	String field,

	/**
	 * Rank by how far the value is above a pivot. For numbers.
	 */
	Saturation saturation,

	/**
	 * Rank by how long ago the value was. For timestamps.
	 */
	Decay decay,

	/**
	 * How much the signal can lift a document at most, as a share of its
	 * score. Left out for 1.
	 */
	Float weight
) {
	/**
	 * Rank by how far a value is above a pivot, as
	 * {@code value / (value + pivot)} - half at the pivot, approaching but
	 * never reaching one above it.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Saturation(
		/**
		 * The value that counts for half of what the signal can give. Has to be
		 * above zero.
		 */
		Double pivot
	) {
	}

	/**
	 * Rank by how long ago a value was, halving every half life.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Decay(
		/**
		 * How many seconds it takes for the signal to be worth half as much.
		 * Has to be above zero.
		 */
		Long halfLife
	) {
	}
}
