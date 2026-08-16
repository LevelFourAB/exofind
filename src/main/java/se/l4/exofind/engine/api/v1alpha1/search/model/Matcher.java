package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;

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
	@JsonSubTypes.Type(value = Matcher.Text.class, name = "text"),
	@JsonSubTypes.Type(value = Matcher.Distance.class, name = "distance")
})
public sealed interface Matcher
	permits Matcher.Equals, Matcher.In, Matcher.Any, Matcher.Prefix, Matcher.Under,
		Matcher.Range, Matcher.Text, Matcher.Distance {
	/**
	 * Match values equal to the given one.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Equals(
		Object value
	) implements Matcher {
	}

	/**
	 * Match values equal to any of the given ones. An empty list matches
	 * nothing, the way a filter nobody has picked a value in does.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record In(
		List<Object> values
	) implements Matcher {
	}

	/**
	 * Match documents that have any value for the field at all.
	 */
	record Any() implements Matcher {
	}

	/**
	 * Match values starting with the given prefix, compared against the whole
	 * value rather than the words inside it.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Prefix(
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
	record Under(
		String path
	) implements Matcher {
	}

	/**
	 * Match values between two bounds, either of which may be left out to
	 * leave that side open. Each side is written as one of an inclusive and
	 * an exclusive bound, and at least one side has to be given.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Range(
		/**
		 * Values have to be this or above it.
		 */
		Object gte,

		/**
		 * Values have to be above this.
		 */
		Object gt,

		/**
		 * Values have to be this or below it.
		 */
		Object lte,

		/**
		 * Values have to be below this.
		 */
		Object lt
	) implements Matcher {
	}

	/**
	 * Match values within a distance of an origin - what "near me" asks for.
	 * Only a geo point field can answer it.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Distance(
		/**
		 * Degrees north of the equator the origin sits at, {@code -90} to
		 * {@code 90}.
		 */
		Double lat,

		/**
		 * Degrees east of the prime meridian the origin sits at, {@code -180}
		 * to {@code 180}.
		 */
		Double lon,

		/**
		 * How far from the origin a value may be, in meters.
		 */
		Double radius
	) implements Matcher {
	}

	/**
	 * Match text that someone typed, analyzed the same way the field was.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Text(
		/**
		 * What was typed.
		 */
		String text,

		/**
		 * How the words are combined, left out for {@code all}.
		 */
		Match match,

		/**
		 * How the word still being typed is treated, left out for
		 * {@code last_token}.
		 */
		Prefix prefix,

		/**
		 * Whether words may contain typing mistakes, left out for
		 * {@code auto}.
		 */
		Typos typos,

		/**
		 * How many other words may sit between the words of a phrase, left
		 * out for none. Only means something for a {@code phrase} or the
		 * quoted parts of a {@code user} text.
		 */
		Integer slop,

		/**
		 * What may be let go of rather than find nothing, left out for
		 * {@code unmatched}.
		 */
		Relax relax
	) implements Matcher {
		/**
		 * How the words of the text are combined.
		 */
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
		public enum Prefix {
			@JsonProperty("last_token")
			LAST_TOKEN,

			@JsonProperty("off")
			OFF
		}

		/**
		 * Whether the words of the text may contain typing mistakes.
		 */
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
