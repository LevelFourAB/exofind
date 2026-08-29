package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Definition of an index, describing what it contains and how it can be
 * searched.
 *
 * A definition is the state a caller wants the index to be in - it is sent in
 * full when updating an index and anything left out is removed. Observed
 * state, such as whether the index is currently usable, is reported separately
 * in {@link IndexStatus}.
 *
 * @param source
 *   how much of a document the index keeps. Left out to let the engine decide,
 *   which is to keep the whole document
 * @param metadata
 *   metadata for the index, not interpreted by the engine
 * @param fields
 *   the fields of the index, keyed by field name. Names may contain {@code *}
 *   to define several fields at once, such as {@code metadata.*}
 * @param ranking
 *   how the index breaks ties in the order of results. Left out for no
 *   opinion beyond how well documents match
 * @param resources
 *   things shared between fields rather than owned by one - named analysis
 *   chains, stopword lists and synonym sets - referred to by name from the
 *   fields
 * @param localeFallback
 *   how the locales a document holds no value in are filled from the ones it
 *   does. Left out to leave them empty, so a search in a locale only finds the
 *   documents translated into it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	What an index contains and how it can be searched. A definition is the \
	state a caller wants: it is sent in full and anything left out is removed. \
	Observed state, such as whether the index is usable, is reported separately \
	under `status`.""")
public record IndexDefinition(
	@Schema(
		description = """
			How much of a document the index keeps. `full` keeps the document \
			as it was given, so it comes back whole and can be reindexed from \
			the index itself; `none` keeps nothing beyond the fields that ask \
			to be `stored`. Changing it applies to documents indexed from \
			there on.""",
		defaultValue = "full"
	)
	Source source,

	@Schema(description = """
		Free-form metadata for the index, not interpreted by the engine.""")
	Map<String, String> metadata,

	@Schema(description = """
		The fields of the index, keyed by field name. A name may contain `*` \
		to define several fields at once, such as `metadata.*` - the wildcard \
		matches exactly one path segment, explicit definitions take \
		precedence, and among wildcards the longest literal prefix wins.""")
	Map<String, FieldDefinition> fields,

	@Schema(description = """
		Tie-breaking rules and ranking signals. Omitted for no opinion beyond \
		how well documents match. Replaced entirely while an index has \
		[search \
		settings](https://exofind.dev/reference/admin-api/#search-settings).""")
	Ranking ranking,

	@Schema(description = """
		Things shared between fields rather than owned by one - named analysis \
		chains, stopword lists and synonym sets - referred to by name from the \
		fields.""")
	Resources resources,

	@Schema(description = """
		How the locales a document holds no value in are filled from the ones \
		it does. Omitted to leave them empty, so a search in a locale only \
		finds the documents translated into it.""")
	LocaleFallback localeFallback
) {
	/**
	 * Fills the locales a document holds no value in from the ones it does,
	 * when it is indexed.
	 *
	 * <pre>
	 * "localeFallback": { "chain": [ "da", "en" ] }
	 * </pre>
	 *
	 * Without it a search naming a locale only finds the documents translated
	 * into it; the rest are missing rather than ranked lower. A filled value
	 * is matched, ordered, filtered and counted like any other, analyzed and
	 * collated as the locale it fills.
	 *
	 * Results are unaffected: a document comes back as it was given, so a
	 * locale specific field still reads only in the locales it was given in.
	 *
	 * Applies to every locale specific field except those setting
	 * {@code "locales": { "fallback": "disabled" }}. Adding, changing or
	 * removing a chain only decides what is written from there on - documents
	 * indexed before keep the variants they were given until they are indexed
	 * again.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Fills the locales a document holds no value in from the ones it does, \
		as it is indexed. Fallback values are analyzed using the target \
		fallback locale, and results are unaffected - a document comes back as \
		it was given. Applies to every locale-specific field except those \
		setting `"locales": { "fallback": "disabled" }`. Changing it decides \
		only what is written from there on.""")
	public record LocaleFallback(
		/**
		 * The locales to take a value from, in the order they are tried, for a
		 * locale a document holds no value in.
		 *
		 * A field skips the entries it holds no values in, so one chain serves
		 * fields declaring different locales; a locale no field of the index
		 * holds is refused. Left out to send every field to its own
		 * {@code defaultLocale}.
		 */
		@Schema(description = """
			Ordered list of locales to take a value from. A field skips the \
			entries it holds no values in, so one chain serves fields \
			declaring different locales; a locale no field of the index holds \
			is refused. Omitted, every field falls back to its own \
			`defaultLocale`.""")
		List<String> chain
	) {
	}

	/**
	 * How much of a document an index keeps.
	 */
	@Schema(description = """
		How much of a document an index keeps: `full` keeps it whole, `none` \
		keeps nothing beyond the fields marked `stored`.""")
	public enum Source {
		/**
		 * Keep the document as it was given, so that it comes back whole
		 * whatever its fields were stored for, and can be indexed again from
		 * the index itself after the definition changes.
		 */
		@JsonProperty("full")
		FULL,

		/**
		 * Keep nothing beyond the fields that ask to be stored. Cheaper on
		 * disk, and on everything the index is pushed and pulled across, at the
		 * cost of a document only coming back as far as it was stored.
		 */
		@JsonProperty("none")
		NONE
	}

	/**
	 * Things shared between the fields of an index, each named and referred
	 * to by that name:
	 *
	 * <pre>
	 * "resources": {
	 *   "analyzers": { "prose": { "preset": "full_text" } },
	 *   "stopwords": { "brands": [ "acme" ] },
	 *   "synonyms": {
	 *     "cars": {
	 *       "rules": [
	 *         { "equivalent": [ "car", "automobile" ] },
	 *         { "mapping": { "from": [ "ny" ], "to": [ "new york" ] } }
	 *       ]
	 *     }
	 *   }
	 * }
	 * </pre>
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Things shared between the fields of an index, each named and referred \
		to by that name.""")
	public record Resources(
		/**
		 * Analysis chains by name, used from a usage with
		 * {@code "analyzer": { "named": "..." } }. A preset is expanded the
		 * same way it is on a field.
		 */
		@Schema(description = """
			Analysis chains by name, referenced from a usage with \
			`"analyzer": { "named": "..." }`. A preset is expanded the same \
			way it is on a field.""")
		Map<String, AnalyzerDefinition> analyzers,

		/**
		 * Stopword lists by name, used from the stopwords component of a
		 * chain with {@code "stopwords": { "named": "..." } }.
		 */
		@Schema(description = """
			Stopword lists by name, referenced from the stopwords component of \
			a chain with `"stopwords": { "named": "..." }`.""")
		Map<String, List<String>> stopwords,

		/**
		 * Synonym sets by name, used from the synonyms component of a chain
		 * with {@code "synonyms": { "named": "..." } }.
		 */
		@Schema(description = """
			Synonym sets by name, referenced from the synonyms component of a \
			chain with `"synonyms": { "named": "..." }`.""")
		Map<String, Synonyms> synonyms
	) {
		/**
		 * Words that mean the same thing. Synonyms are applied when a value
		 * is indexed, so changing a set only affects documents indexed from
		 * there on.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = """
			Words that mean the same thing. Synonyms are applied when a value \
			is indexed, so changing a set only affects documents indexed from \
			there on.""")
		public record Synonyms(
			@Schema(description = "The rules of the set.", required = true)
			List<Rule> rules
		) {
			/**
			 * One rule of a synonym set, exactly one kind: words that are all
			 * equivalent, or a one way mapping.
			 */
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = """
				One rule of a synonym set, carrying exactly one kind: words \
				that are all equivalent, or a one-way mapping.""")
			public record Rule(
				/**
				 * Terms that all mean the same thing - each matches every
				 * other. A term of several words matches them in sequence.
				 */
				@Schema(description = """
					Terms that all mean the same thing, each matching every \
					other. A term of several words matches them in \
					sequence.""")
				List<String> equivalent,

				/**
				 * One way: a value containing one of {@code from} also
				 * answers searches for any of {@code to}, but not the other
				 * way around.
				 */
				@Schema(description = """
					A one-way rule: a value containing one of `from` also \
					answers searches for any of `to`, but not the other way \
					around.""")
				Mapping mapping
			) {
				@JsonInclude(JsonInclude.Include.NON_NULL)
				@Schema(description = "The two sides of a one-way synonym rule.")
				public record Mapping(
					@Schema(description = "Terms the rule reads from.", required = true)
					List<String> from,

					@Schema(
						description = "Terms the rule also answers for.",
						required = true
					)
					List<String> to
				) {
				}
			}
		}
	}

	/**
	 * The standing opinion an index has about the order of its results.
	 *
	 * The tie breakers are appended after whatever primary ordering a search
	 * asks for - its own sort, or relevance when it gives none - so they
	 * decide the order within ties without ever disturbing the order the
	 * search asked for.
	 *
	 * The signals are the graded part of the same opinion: a value the
	 * documents carry, multiplied into their relevance so that a popular or a
	 * recent document ranks above an equally good match that is neither. They
	 * only mean something where relevance is the ordering, so a search sorting
	 * by a field of its own is left alone.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Tie-breaking rules and signal score multipliers. See \
		[Relevance](https://exofind.dev/explanation/relevance/).""")
	public record Ranking(
		/**
		 * The tie breakers, applied in order until one of them tells two
		 * documents apart.
		 */
		@Schema(description = """
			Secondary sort criteria applied in sequence after the search's own \
			sort or relevance scoring, until one of them tells two documents \
			apart. Target fields must have `sort` enabled.""")
		List<TieBreaker> tieBreakers,

		/**
		 * The values of the documents themselves to take into their relevance,
		 * each multiplied into the score in turn.
		 */
		@Schema(description = """
			Document values multiplied into relevance, each applied in turn. \
			Evaluated at search time without reindexing, and only where \
			relevance is the ordering. A search that carries its own `signals` \
			replaces these.""")
		List<Signal> signals
	) {
		/**
		 * One value of the documents themselves, taken into their relevance.
		 *
		 * The value is read from a field defined for sorting, shaped into a
		 * number between zero and one, and multiplied into the score as
		 * {@code 1 + weight * shape}. A document holding no value contributes
		 * nothing rather than being multiplied away, and every shape is
		 * bounded, so a signal can lift a document by at most its weight and
		 * never drown out how well it matched.
		 *
		 * Exactly one shape has to be given, and which one a field can answer
		 * for follows from its type: how far a value is above a pivot means
		 * something for a number, how long ago it was for a timestamp.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = """
			One document value taken into relevance. The value is read from a \
			field defined for sorting, shaped into a number between `0` and \
			`1`, and multiplied into the score as `1 + weight * shape`. A \
			document holding no value contributes `0` rather than being \
			multiplied away, so a signal can lift a document by at most its \
			weight. Exactly one shape must be given, and which one a field can \
			answer for follows from its type.""")
		public record Signal(
			/**
			 * The field to read the value from. Has to be a number or a
			 * timestamp defined for sorting.
			 */
			@Schema(
				description = """
					The field to read the value from. Must be a number or \
					timestamp field with sorting enabled.""",
				required = true,
				examples = "purchases"
			)
			String field,

			/**
			 * Rank by how far the value is above a pivot. For numbers.
			 */
			@Schema(description = """
				Ranks by how far the value rises above a pivot. For `int32`, \
				`int64`, `float` and `double` fields.""")
			Saturation saturation,

			/**
			 * Rank by how long ago the value was. For timestamps.
			 */
			@Schema(description = """
				Ranks by how long ago the value was. For `timestamp` \
				fields.""")
			Decay decay,

			/**
			 * How much the signal can lift a document at most, as a share of
			 * its score. Left out for 1, which lets a document at the top of
			 * the signal reach twice the score of one holding no value at all.
			 */
			@Schema(
				description = """
					How much the signal can lift a document at most, as a \
					share of its score. At `1`, a document at the top of the \
					signal reaches twice the score of one holding no value at \
					all.""",
				defaultValue = "1"
			)
			Float weight
		) {
			/**
			 * Rank by how far a value is above a pivot, as
			 * {@code value / (value + pivot)} - half at the pivot, approaching
			 * but never reaching one above it. The shape for a count that has
			 * no ceiling, such as how often something was bought.
			 */
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(
				name = "RankingSaturation",
				description = """
					Computes `value / (value + pivot)`, reaching `0.5` at the \
					pivot and approaching but never reaching `1` above it. \
					Values below `0` evaluate to `0`. The shape for a count \
					with no ceiling, such as how often something was bought."""
			)
			public record Saturation(
				/**
				 * The value that counts for half of what the signal can give.
				 * Has to be above zero.
				 */
				@Schema(
					description = """
						The value that counts for half of what the signal can \
						give. Required, and must be greater than `0`.""",
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
			@Schema(
				name = "RankingDecay",
				description = """
					Halves the multiplier every `halfLife` seconds of age. \
					Values dated at or after the current time evaluate to \
					`1`."""
			)
			public record Decay(
				/**
				 * How many seconds it takes for the signal to be worth half as
				 * much. Has to be above zero.
				 */
				@Schema(
					description = """
						How many seconds it takes for the signal to be worth \
						half as much. Required, and must be greater than \
						`0`.""",
					required = true,
					exclusiveMinimum = true,
					minimum = "0",
					examples = "604800"
				)
				Long halfLife
			) {
			}
		}

		/**
		 * One way of breaking a tie, by a field defined for sorting.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "One way of breaking a tie, by a field defined for sorting.")
		public record TieBreaker(
			/**
			 * The field to break ties by. Has to be defined for sorting.
			 */
			@Schema(
				description = """
					The field to break ties by. Must have `sort` enabled.""",
				required = true,
				examples = "sales"
			)
			String field,

			/**
			 * Which end of the field wins the tie. Defaults to
			 * {@code descending}, the way recency and popularity read.
			 */
			@Schema(
				description = """
					Which end of the field wins the tie. `descending` is the \
					way recency and popularity read.""",
				defaultValue = "descending"
			)
			Direction direction
		) {
			@Schema(description = """
				Which end of a tie-breaker field wins: `ascending` or \
				`descending`.""")
			public enum Direction {
				@JsonProperty("ascending")
				ASCENDING,

				@JsonProperty("descending")
				DESCENDING
			}
		}
	}
}
