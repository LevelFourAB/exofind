package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What a field clause looks for in a field, as it is written on the wire.
 *
 * Matchers are a tagged union where {@code type} selects the kind, using the
 * {@link se.l4.exofind.engine.query.matchers.Matcher#id() identifier} the
 * engine gives each matcher. A matcher with no {@code type} is
 * {@code equals}, keeping the common case down to the value being looked for:
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
	Criteria evaluated against the values of a field, structured as a tagged \
	union where `type` selects the matcher type. If `type` is omitted, the \
	matcher defaults to `equals`. Specifying a matcher the target field type \
	does not support returns an error. See \
	[Matchers](https://exofind.dev/reference/search-api/#matchers).""")
public sealed interface Matcher
	permits Matcher.Equals, Matcher.In, Matcher.Any, Matcher.Prefix, Matcher.Under,
		Matcher.Range, Matcher.Ranges, Matcher.Text, Matcher.Distance {
	/**
	 * Match values equal to the given one.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "EqualsMatcher",
		description = "Matches field values equal to `value`."
	)
	record Equals(
		@Schema(
			description = "The value a field value has to equal.",
			required = true,
			examples = "fiction"
		)
		Object value
	) implements Matcher {
	}

	/**
	 * Match values equal to any of the given ones. An empty list matches
	 * nothing, the way a filter nobody has picked a value in does.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "InMatcher",
		description = """
			Matches field values equal to any value in `values`. An empty \
			array matches no documents, the way a filter nobody has ticked a \
			value in does."""
	)
	record In(
		@Schema(description = "The values a field value may equal.", required = true)
		List<Object> values
	) implements Matcher {
	}

	/**
	 * Match documents that have any value for the field at all.
	 */
	@Schema(
		name = "AnyMatcher",
		description = "Matches any document that contains a value for the field."
	)
	record Any() implements Matcher {
	}

	/**
	 * Match values starting with the given prefix, compared against the whole
	 * value rather than the words inside it.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "PrefixMatcher",
		description = """
			Matches string field values starting with `value`, evaluated \
			against the entire field value rather than the words inside it."""
	)
	record Prefix(
		@Schema(
			description = "The prefix a field value has to start with.",
			required = true,
			examples = "EX-"
		)
		String value
	) implements Matcher {
	}

	/**
	 * Match values sitting at or below a path of a tree, which is what
	 * choosing a category asks for. Only a field whose values are read as
	 * paths can answer it, and levels are matched whole - `Men/Sho` is not a
	 * level, so it finds nothing where a `prefix` would have found the shoes.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "UnderMatcher",
		description = """
			Matches values at or below the specified path in a hierarchical \
			tree. Requires a field configured with \
			[`hierarchy`](https://exofind.dev/reference/field-types/#string). \
			Path segments must match complete levels, so `Men/Sho` matches \
			nothing where a `prefix` matcher would have matched the shoes."""
	)
	record Under(
		@Schema(
			description = "Path in the tree to match at or below.",
			required = true,
			examples = "Men/Shoes"
		)
		String path
	) implements Matcher {
	}

	/**
	 * Match values between two bounds, either of which may be left out to
	 * leave that side open. Each side is written as one of an inclusive and
	 * an exclusive bound, and at least one side has to be given.
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
		 * Values have to be this or above it.
		 */
		@Schema(description = "Lower bound, the value itself included.", examples = "10")
		Object gte,

		/**
		 * Values have to be above this.
		 */
		@Schema(description = "Lower bound, the value itself excluded.")
		Object gt,

		/**
		 * Values have to be this or below it.
		 */
		@Schema(description = "Upper bound, the value itself included.")
		Object lte,

		/**
		 * Values have to be below this.
		 */
		@Schema(description = "Upper bound, the value itself excluded.", examples = "20")
		Object lt
	) implements Matcher {
	}

	/**
	 * Match values inside any one of several ranges - what the ticked buckets
	 * of a range facet turn into, the way ticked values are an `in`. An empty
	 * list matches nothing, like an empty `in` does.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "RangesMatcher",
		description = """
			Matches values falling within any of the specified range objects - \
			what the ticked buckets of a range facet turn into, the way ticked \
			values become an `in`. An empty array matches no documents."""
	)
	record Ranges(
		/**
		 * The ranges to look in, a value matching when any one of them holds
		 * it. Each is written the way a `range` matcher is and needs at least
		 * one bound - a bucket sent back from a range facet is its `from` as
		 * `gte` and its `to` as `lt`.
		 */
		@Schema(
			description = """
				The ranges to look in, each requiring at least one bound. A \
				bucket sent back by a range facet is its `from` as `gte` and \
				its `to` as `lt`.""",
			required = true
		)
		List<Range> values
	) implements Matcher {
		/**
		 * One range, each side one of an inclusive and an exclusive bound.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "MatcherRange",
			description = """
				One range of a `ranges` matcher, each side written as one of \
				an inclusive and an exclusive bound. At least one bound is \
				required."""
		)
		public record Range(
			/**
			 * Values have to be this or above it.
			 */
			@Schema(description = "Lower bound, the value itself included.", examples = "10")
			Object gte,

			/**
			 * Values have to be above this.
			 */
			@Schema(description = "Lower bound, the value itself excluded.")
			Object gt,

			/**
			 * Values have to be this or below it.
			 */
			@Schema(description = "Upper bound, the value itself included.")
			Object lte,

			/**
			 * Values have to be below this.
			 */
			@Schema(description = "Upper bound, the value itself excluded.", examples = "20")
			Object lt
		) {
		}
	}

	/**
	 * Match values within a distance of an origin - what "near me" asks for.
	 * Only a geo point field can answer it.
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
		Double lon,

		/**
		 * How far from the origin a value may be, in meters.
		 */
		@Schema(
			description = "How far from the origin a value may be, in meters.",
			required = true,
			examples = "5000"
		)
		Double radius
	) implements Matcher {
	}

	/**
	 * Match text that someone typed, analyzed the same way the field was.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(
		name = "TextMatcher",
		description = """
			Matches text within a single field, analyzed the same way the \
			field was."""
	)
	record Text(
		/**
		 * What was typed.
		 */
		@Schema(description = "The query text to match.", required = true)
		String text,

		/**
		 * How the words are combined, left out for {@code all}.
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
		 * How the word still being typed is treated, left out for
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
		 * Whether words may contain typing mistakes, left out for
		 * {@code auto}.
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
		 * How many other words may sit between the words of a phrase, left
		 * out for none. Only means something for a {@code phrase} or the
		 * quoted parts of a {@code user} text.
		 */
		@Schema(
			description = """
				Number of intervening words permitted between terms in a \
				phrase, without changing their relative order. Only means \
				something for `phrase` or for the quoted parts of a `user` \
				text.""",
			defaultValue = "0"
		)
		Integer slop,

		/**
		 * What may be let go of rather than find nothing, left out for
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
		 * How the words of the text are combined.
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
		 * How the last word of the text is treated.
		 */
		@Schema(description = """
			Prefix matching behavior on the final term: `last_token` matches \
			the trailing word as a prefix, `off` requires an exact word \
			match.""")
		public enum Prefix {
			@JsonProperty("last_token")
			LAST_TOKEN,

			@JsonProperty("off")
			OFF
		}

		/**
		 * Whether the words of the text may contain typing mistakes.
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
		 * What the words of the text may be let go of rather than find
		 * nothing. Only ever happens on a search that came back empty, and
		 * whatever went is answered as {@code relaxed} beside the results.
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
