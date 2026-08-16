package se.l4.exofind.engine.api.v1alpha1.search.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One step in the order results come back in, as it is written on the wire.
 *
 * The same tagged union trick as {@link Clause}: no {@code type} means a
 * field sort, as it is the only kind carrying {@code field}. Leaving
 * {@code order} out takes the engine's default for the kind - score
 * descending, field ascending.
 *
 * <pre>
 * { "type": "score" }
 * { "field": "name", "order": "asc" }
 * </pre>
 */
@JsonTypeInfo(
	use = JsonTypeInfo.Id.NAME,
	include = JsonTypeInfo.As.PROPERTY,
	property = "type",
	defaultImpl = Sort.Field.class
)
@JsonSubTypes({
	@JsonSubTypes.Type(value = Sort.Field.class, name = "field"),
	@JsonSubTypes.Type(value = Sort.Score.class, name = "score"),
	@JsonSubTypes.Type(value = Sort.Distance.class, name = "distance")
})
public sealed interface Sort permits Sort.Field, Sort.Score, Sort.Distance {
	/**
	 * Which way values are ordered.
	 */
	enum Order {
		@JsonProperty("asc")
		ASC,

		@JsonProperty("desc")
		DESC
	}

	/**
	 * Order by the value of a field, which has to be defined for sorting.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Field(
		/**
		 * Name of the field, as it is called in the definition of the index.
		 */
		String field,

		Order order
	) implements Sort {
	}

	/**
	 * Order by how well documents match.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Score(
		Order order
	) implements Sort {
	}

	/**
	 * Order by how far a geo point field's value is from an origin, nearest
	 * first. There is no farthest first, so this kind carries no order.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Distance(
		/**
		 * Name of the field, as it is called in the definition of the index.
		 */
		String field,

		/**
		 * Degrees north of the equator the origin sits at, {@code -90} to
		 * {@code 90}.
		 */
		Double lat,

		/**
		 * Degrees east of the prime meridian the origin sits at, {@code -180}
		 * to {@code 180}.
		 */
		Double lon
	) implements Sort {
	}
}
