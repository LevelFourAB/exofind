package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Criteria evaluated against field values in a field clause.
 *
 * <p>Matchers are structured as a tagged union where {@code type} selects the
 * matcher type, using the
 * {@link se.l4.exofind.engine.query.matchers.Matcher#id() identifier} assigned
 * by the engine. If {@code type} is omitted, the matcher defaults to
 * {@code equals}:
 *
 * <pre>
 * { "value": "fiction" }
 * { "type": "prefix", "value": "EX-" }
 * { "type": "under", "path": "Men/Shoes" }
 * { "type": "range", "gte": 10, "lt": 20 }
 * </pre>
 */
@JsonTypeInfo(
	use = JsonTypeInfo.Id.NAME,
	include = JsonTypeInfo.As.PROPERTY,
	property = "type",
	defaultImpl = Matcher.Equals.class
)
@JsonSubTypes({
	@JsonSubTypes.Type(value = Matcher.Equals.class, name = "equals"),
	@JsonSubTypes.Type(value = Matcher.In.class, name = "in"),
	@JsonSubTypes.Type(value = Matcher.Any.class, name = "any"),
	@JsonSubTypes.Type(value = Matcher.Prefix.class, name = "prefix"),
	@JsonSubTypes.Type(value = Matcher.Under.class, name = "under"),
	@JsonSubTypes.Type(value = Matcher.Range.class, name = "range"),
	@JsonSubTypes.Type(value = Matcher.Ranges.class, name = "ranges"),
	@JsonSubTypes.Type(value = Matcher.Text.class, name = "text"),
	@JsonSubTypes.Type(value = Matcher.Distance.class, name = "distance")
})
@Schema(description = """
	Criteria evaluated against field values in a field clause, structured as a \
	tagged union where `type` selects the matcher type. If `type` is omitted, \
	the matcher defaults to `equals`. Specifying a matcher unsupported by the \
	target field type returns an error. See \
	[Matchers](https://exofind.dev/reference/search-api/#matchers).""")
public sealed interface Matcher
	permits Matcher.Equals, Matcher.In, Matcher.Any, Matcher.Prefix, Matcher.Under,
		Matcher.Range, Matcher.Ranges, Matcher.Text, Matcher.Distance {
	/**
	 * Matches field values equal to the specified value.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "EqualsMatcher",
		description = "Matches field values equal to `value`."
	)
	record Equals(
		@Schema(
			description = "The value that the field value must equal.",
			required = true,
			examples = "fiction"
		)
		Object value
	) implements Matcher {
	}

	/**
	 * Matches field values equal to any of the specified values. An empty
	 * collection matches no documents.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "InMatcher",
		description = """
			Matches field values equal to any value in `values`. An empty \
			array matches no documents."""
	)
	record In(
		@Schema(description = "The values that a field value may equal.", required = true)
		List<Object> values
	) implements Matcher {
	}

	/**
	 * Matches any document that contains a value for the field.
	 */
	@Schema(
		name = "AnyMatcher",
		description = "Matches any document that contains a value for the field."
	)
	record Any() implements Matcher {
	}

	/**
	 * Matches string field values starting with the specified prefix, evaluated
	 * against the entire field value.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "PrefixMatcher",
		description = """
			Matches string field values starting with `value`, evaluated \
			against the entire field value."""
	)
	record Prefix(
		@Schema(
			description = "The prefix that a field value must start with.",
			required = true,
			examples = "EX-"
		)
		String value
	) implements Matcher {
	}

	/**
	 * Matches values at or below the specified path in a hierarchical tree.
	 * Requires a field configured with hierarchy support. Path segments must
	 * match complete levels: `Men/Sho` matches nothing where a `prefix` matcher
	 * matches.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "UnderMatcher",
		description = """
			Matches values at or below the specified path in a hierarchical \
			tree. Requires a field configured with \
			[`hierarchy`](https://exofind.dev/reference/field-types/#string). \
			Path segments must match complete levels, so `Men/Sho` matches \
			nothing where a `prefix` matcher matches."""
	)
	record Under(
		@Schema(
			description = "Path in the hierarchical tree to match at or below.",
			required = true,
			examples = "Men/Shoes"
		)
		String path
	) implements Matcher {
	}

	/**
	 * Matches values within bounds. Accepts inclusive and exclusive bounds;
	 * either side may be left open, and at least one bound is required.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "RangeMatcher",
		description = """
			Matches values within bounds. Accepts inclusive (`gte`, `lte`) and \
			exclusive (`gt`, `lt`) bounds; either side may be left open, and \
			at least one bound is required (`search:matcher:range_empty`)."""
	)
	record Range(
		/**
		 * Lower bound, inclusive.
		 */
		@Schema(description = "Lower bound, the value itself included.", examples = "10")
		Object gte,

		/**
		 * Lower bound, exclusive.
		 */
		@Schema(description = "Lower bound, the value itself excluded.")
		Object gt,

		/**
		 * Upper bound, inclusive.
		 */
		@Schema(description = "Upper bound, the value itself included.")
		Object lte,

		/**
		 * Upper bound, exclusive.
		 */
		@Schema(description = "Upper bound, exclusive.", examples = "20")
		Object lt
	) implements Matcher {
	}

	/**
	 * Matches values falling within any of the specified ranges. An empty array
	 * matches no documents, matching the behavior of an empty `in` matcher.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "RangesMatcher",
		description = """
			Matches values falling within any of the specified range objects. \
			An empty array matches no documents, matching the behavior of an \
			empty `in` matcher."""
	)
	record Ranges(
		/**
		 * The ranges to evaluate, where a value matches when any range contains
		 * it. Each follows the `range` matcher format and requires at least one
		 * bound; a bucket returned by a range facet sets `from` as `gte` and
		 * `to` as `lt`.
		 */
		@Schema(
			description = """
				The ranges to evaluate, each requiring at least one bound. A \
				bucket returned by a range facet sets `from` as `gte` and `to` \
				as `lt`.""",
			required = true
		)
		List<Range> values
	) implements Matcher {
		/**
		 * One range, bounded on each side by an inclusive or exclusive bound.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "MatcherRange",
			description = """
				One range of a `ranges` matcher, bounded on each side by an \
				inclusive or exclusive bound. At least one bound is required."""
		)
		public record Range(
			/**
			 * Lower bound, inclusive.
			 */
			@Schema(description = "Lower bound, the value itself included.", examples = "10")
			Object gte,

			/**
			 * Lower bound, exclusive.
			 */
			@Schema(description = "Lower bound, the value itself excluded.")
			Object gt,

			/**
			 * Upper bound, inclusive.
			 */
			@Schema(description = "Upper bound, the value itself included.")
			Object lte,

			/**
			 * Upper bound, exclusive.
			 */
			@Schema(description = "Upper bound, exclusive.", examples = "20")
			Object lt
		) {
		}
	}

	/**
	 * Matches geopoint field values within a specified distance of the origin
	 * coordinates.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "DistanceMatcher",
		description = """
			Matches geopoint values within `radius` meters of the specified \
			latitude and longitude coordinates."""
	)
	record Distance(
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
		Double lon,

		/**
		 * Maximum distance from the origin in meters.
		 */
		@Schema(
			description = "Maximum distance from the origin in meters.",
			required = true,
			examples = "5000"
		)
		Double radius
	) implements Matcher {
	}

	/**
	 * Matches text within a single field using field-level analysis.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "TextMatcher",
		description = """
			Matches text within a single field using field-level analysis."""
	)
	record Text(
		/**
		 * The query text to match.
		 */
		@Schema(description = "The query text to match.", required = true)
		String text,

		/**
		 * Term matching mode. Defaults to {@code all}.
		 */
		@Schema(
			description = """
				Term matching mode. `phrase` requires terms to appear in exact \
				order and adjacent; `user` parses search syntax such as quotes \
				and negation.""",
			defaultValue = "all"
		)
		Match match,

		/**
		 * Prefix matching behavior on the final query term. Defaults to
		 * {@code last_token}.
		 */
		@Schema(
			description = """
				Prefix matching behavior on the final query term. \
				`last_token` matches the trailing word as a prefix; `off` \
				requires an exact word match.""",
			defaultValue = "last_token"
		)
		Prefix prefix,

		/**
		 * Typo tolerance handling. Defaults to {@code auto}, which follows the
		 * field's `typoTolerance` configuration; `off` disables typo tolerance
		 * for the matcher.
		 */
		@Schema(
			description = """
				Typo tolerance handling. `auto` follows the field's \
				`typoTolerance` configuration; `off` disables typo tolerance \
				for the matcher.""",
			defaultValue = "auto"
		)
		Typos typos,

		/**
		 * Number of intervening words permitted between terms in a phrase,
		 * defaulting to none. Only applies to a {@code phrase} query or the
		 * quoted parts of {@code user} text.
		 */
		@Schema(
			description = """
				Number of intervening words permitted between terms in a \
				phrase, without changing their relative order. Only applies to \
				`phrase` queries or quoted phrases in `user` mode.""",
			defaultValue = "0"
		)
		Integer slop,

		/**
		 * Query relaxation strategy when no documents match. Defaults to
		 * {@code unmatched}.
		 */
		@Schema(
			description = """
				Query relaxation strategy applied only when the query returns \
				zero matches. See [Finding something rather than \
				nothing](https://exofind.dev/reference/search-api/#finding-something-rather-than-nothing).""",
			defaultValue = "unmatched"
		)
		Relax relax
	) implements Matcher {
		/**
		 * Term matching mode: how terms in the query text are combined.
		 */
		@Schema(description = """
			Term matching mode: `all` requires every term, `any` requires one, \
			`phrase` requires them in order and adjacent, and `user` parses \
			search syntax such as quotes and negation.""")
		public enum Match {
			@JsonProperty("all")
			ALL,

			@JsonProperty("any")
			ANY,

			@JsonProperty("phrase")
			PHRASE,

			@JsonProperty("user")
			USER
		}

		/**
		 * Prefix matching behavior on the final query term.
		 */
		@Schema(description = """
			Prefix matching behavior on the final query term: `last_token` \
			matches the trailing word as a prefix, `off` requires an exact \
			word match.""")
		public enum Prefix {
			@JsonProperty("last_token")
			LAST_TOKEN,

			@JsonProperty("off")
			OFF
		}

		/**
		 * Typo tolerance handling for query terms.
		 */
		@Schema(description = """
			Typo tolerance handling: `auto` follows each field's \
			`typoTolerance` configuration, `off` disables it.""")
		public enum Typos {
			@JsonProperty("auto")
			AUTO,

			@JsonProperty("off")
			OFF
		}

		/**
		 * Query relaxation strategy when no documents match. Applied only when
		 * the initial query returns zero matches, and dropped terms are
		 * reported in {@code relaxed} beside the results.
		 */
		@Schema(description = """
			Query relaxation strategy: `unmatched` drops words that do not \
			exist in the index, `words` also drops the most common remaining \
			words one by one until results are found, and `off` returns an \
			empty result set. Whatever was dropped is reported as `relaxed` \
			beside the results.""")
		public enum Relax {
			@JsonProperty("off")
			OFF,

			@JsonProperty("unmatched")
			UNMATCHED,

			@JsonProperty("words")
			WORDS
		}
	}
}
