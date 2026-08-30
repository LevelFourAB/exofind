package se.l4.exofind.engine.api.v1alpha1.search.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Specifies the ordering of search results.
 *
 * <p>Structured as a tagged union like {@link Clause}: an omitted {@code type}
 * defaults to a field sort, as it is the only kind containing {@code field}. If
 * {@code order} is omitted, score sorts default to descending and field sorts
 * default to ascending.
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
	 * Direction values are ordered in.
	 */
	@Schema(description = "Direction values are ordered in: `asc` or `desc`.")
	enum Order {
		@JsonProperty("asc")
		ASC,

		@JsonProperty("desc")
		DESC
	}

	/**
	 * Sorts by field value. The target field must have sorting enabled.
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
		 * Target field, as named in the index definition.
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
	 * Sorts by document relevance score.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(name = "ScoreSort", description = "Sorts by document relevance score.")
	record Score(
		@Schema(description = "Direction to order in.", defaultValue = "desc")
		Order order
	) implements Sort {
	}

	/**
	 * Sorts by distance from the specified geographic coordinate, nearest
	 * first. Accepts no order property.
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
		 * Target geopoint field, as named in the index definition.
		 */
		@Schema(
			description = "Target geopoint field, as named in the index definition.",
			required = true,
			examples = "location"
		)
		String field,

		/**
		 * Latitude of the origin in degrees north of the equator, {@code -90}
		 * to {@code 90}.
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
		 * Longitude of the origin in degrees east of the prime meridian,
		 * {@code -180} to {@code 180}.
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
