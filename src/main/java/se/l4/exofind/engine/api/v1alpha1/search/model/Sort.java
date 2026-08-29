package se.l4.exofind.engine.api.v1alpha1.search.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(description = """
	One step of the ordering of returned hits, structured as a tagged union \
	where `type` defaults to a `field` sort when omitted. If `order` is \
	omitted, score sorts default to descending and field sorts to ascending. \
	Configured index tie-breaker sorts are appended after the requested sorts. \
	See [Sorts](https://exofind.dev/reference/search-api/#sorts).""")
public sealed interface Sort permits Sort.Field, Sort.Score, Sort.Distance {
	/**
	 * Which way values are ordered.
	 */
	@Schema(description = "Direction values are ordered in: `asc` or `desc`.")
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
	@Schema(
		name = "FieldSort",
		description = """
			Sorts by field value. The target field must have sorting enabled. \
			A field inside a `nested` \
			[object](https://exofind.dev/reference/field-types/#object) \
			is named by its dotted path, and only the nested values that the \
			query's `nested` clauses matched are considered."""
	)
	record Field(
		/**
		 * Name of the field, as it is called in the definition of the index.
		 */
		@Schema(
			description = "Target field, as named in the index definition.",
			required = true,
			examples = "name"
		)
		String field,

		@Schema(description = "Direction to order in.", defaultValue = "asc")
		Order order
	) implements Sort {
	}

	/**
	 * Order by how well documents match.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(name = "ScoreSort", description = "Sorts by document relevance score.")
	record Score(
		@Schema(description = "Direction to order in.", defaultValue = "desc")
		Order order
	) implements Sort {
	}

	/**
	 * Order by how far a geo point field's value is from an origin, nearest
	 * first. There is no farthest first, so this kind carries no order.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "DistanceSort",
		description = """
			Sorts by distance from the specified geographic coordinate, \
			nearest first. Accepts no `order` property. A distance sort on a \
			nested object field returns \
			`index:query:nested:sort_unsupported`."""
	)
	record Distance(
		/**
		 * Name of the field, as it is called in the definition of the index.
		 */
		@Schema(
			description = "Target geopoint field, as named in the index definition.",
			required = true,
			examples = "location"
		)
		String field,

		/**
		 * Degrees north of the equator the origin sits at, {@code -90} to
		 * {@code 90}.
		 */
		@Schema(
			description = "Latitude of the origin, in degrees.",
			required = true,
			minimum = "-90",
			maximum = "90",
			examples = "59.3"
		)
		Double lat,

		/**
		 * Degrees east of the prime meridian the origin sits at, {@code -180}
		 * to {@code 180}.
		 */
		@Schema(
			description = "Longitude of the origin, in degrees.",
			required = true,
			minimum = "-180",
			maximum = "180",
			examples = "18.1"
		)
		Double lon
	) implements Sort {
	}
}
