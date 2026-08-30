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
 * <p>A definition represents the desired state of the index: it is sent in full
 * when updating an index, and any omitted setting is removed. Observed runtime
 * state is reported separately in {@link IndexStatus}.
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
 *   how the index breaks ties in the order of results. Left out for no opinion
 *   beyond how well documents match
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
			How much of a document the index retains. `full` retains the \
			entire document, enabling full document retrieval and reindexing \
			from the index itself. `none` retains only fields configured as \
			`stored`. Changing this setting applies to documents indexed after \
			the update.""",
		defaultValue = "full"
	)
	Source source,

	@Schema(description = """
		Free-form metadata for the index, not interpreted by the engine.""")
	Map<String, String> metadata,

	@Schema(description = """
		The fields of the index, keyed by field name. A name can contain `*` \
		to define multiple fields at once, such as `metadata.*`. The wildcard \
		matches exactly one path segment. Explicit definitions take \
		precedence, and the longest literal prefix wins among multiple \
		wildcard patterns.""")
	Map<String, FieldDefinition> fields,

	@Schema(description = """
		Tie-breaking rules and ranking signals. Omitted to order results by \
		match score alone. Replaced entirely while an index has [search \
		settings](https://exofind.dev/reference/admin-api/#search-settings).""")
	Ranking ranking,

	@Schema(description = """
		Shared resources referenced by name from fields, including named \
		analysis chains, stopword lists, and synonym sets.""")
	Resources resources,

	@Schema(description = """
		Configures how missing locale values in a document are populated from \
		available locales during indexing. When omitted, missing locales \
		remain empty, and searches in a locale find only documents translated \
		into it.""")
	LocaleFallback localeFallback
) {
	/**
	 * Fills missing locale values in a document from available translations
	 * during indexing.
	 *
	 * <pre>
	 * "localeFallback": { "chain": [ "da", "en" ] }
	 * </pre>
	 *
	 * <p>Without fallback configured, searches in a locale match only documents
	 * translated into that locale. Populated fallback values are matched,
	 * ordered, filtered, and faceted like direct values, analyzed and collated
	 * under the target fallback locale.
	 *
	 * <p>Document retrieval remains unchanged: a document returns only the
	 * locale variants originally provided.
	 *
	 * <p>Applies to every locale-specific field except those setting {@code
	 * "locales": { "fallback": "disabled" }}. Modifying fallback chains applies
	 * only to documents indexed after the update; previously indexed documents
	 * retain their variants until reindexed.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Fills missing locale values in a document from available translations \
		during indexing. Fallback values are analyzed using the target \
		fallback locale. Document retrieval is unaffected: documents return as \
		originally provided. Applies to every locale-specific field except \
		those setting `"locales": { "fallback": "disabled" }`. Modifying \
		fallback rules applies only to documents indexed after the change.""")
	public record LocaleFallback(
		/**
		 * The ordered list of locales to evaluate when populating a missing
		 * locale value.
		 *
		 * <p>A field skips locales for which it has no value, allowing a single
		 * chain to serve fields declaring different locales. Specifying a
		 * locale that no field in the index defines is rejected. When omitted,
		 * each field falls back to its own {@code defaultLocale}.
		 */
		@Schema(description = """
			Ordered list of locales to evaluate when populating a missing \
			locale value. A field skips locales for which it has no value, \
			allowing a single chain to serve fields with different configured \
			locales. Specifying a locale not defined on any field in the index \
			is rejected. When omitted, each field falls back to its \
			`defaultLocale`.""")
		List<String> chain
	) {
	}

	/**
	 * How much of a document an index retains.
	 */
	@Schema(description = """
		How much of a document an index retains: `full` retains the document \
		in full, and `none` retains only the fields marked `stored`.""")
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
	 * Shared resources defined for an index, referenced by name across fields:
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
		Shared resources defined for an index, referenced by name from \
		individual fields.""")
	public record Resources(
		/**
		 * Named analysis chains, referenced from a field usage with {@code
		 * "analyzer": { "named": "..." } }. Presets are expanded the same way
		 * as on a field.
		 */
		@Schema(description = """
			Named analysis chains, referenced from a field usage with \
			`"analyzer": { "named": "..." }`. Presets are expanded the same \
			way as on a field.""")
		Map<String, AnalyzerDefinition> analyzers,

		/**
		 * Named stopword lists, referenced from the stopwords component of an
		 * analyzer chain with {@code "stopwords": { "named": "..." } }.
		 */
		@Schema(description = """
			Named stopword lists, referenced from the stopwords component of \
			an analyzer chain with `"stopwords": { "named": "..." }`.""")
		Map<String, List<String>> stopwords,

		/**
		 * Named synonym sets, referenced from the synonyms component of an
		 * analyzer chain with {@code "synonyms": { "named": "..." } }.
		 */
		@Schema(description = """
			Named synonym sets, referenced from the synonyms component of an \
			analyzer chain with `"synonyms": { "named": "..." }`.""")
		Map<String, Synonyms> synonyms
	) {
		/**
		 * Synonym rules applied during indexing. Modifying a synonym set
		 * applies only to documents indexed after the change.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = """
			Synonym rules applied during indexing. Modifying a synonym set \
			applies only to documents indexed after the change.""")
		public record Synonyms(
			@Schema(description = "The list of rules for the synonym set.", required = true)
			List<Rule> rules
		) {
			/**
			 * A single synonym rule, configured as either equivalent terms or a
			 * one-way mapping.
			 */
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = """
				A single synonym rule, configured as either equivalent terms \
				or a one-way mapping.""")
			public record Rule(
				/**
				 * Interchangeable terms where each term matches every other
				 * term. Multi-word terms match words in sequence.
				 */
				@Schema(description = """
					Interchangeable terms where each term matches every other \
					term. Multi-word terms match words in sequence.""")
				List<String> equivalent,

				/**
				 * A one-way mapping rule: values containing a term in
				 * {@code from} also match searches for any term in {@code to},
				 * but not the reverse.
				 */
				@Schema(description = """
					A one-way mapping rule: values containing a term in `from` \
					also match searches for any term in `to`, but not the \
					reverse.""")
				Mapping mapping
			) {
				@JsonInclude(JsonInclude.Include.NON_NULL)
				@Schema(description = """
					The input and target terms of a one-way synonym mapping \
					rule.""")
				public record Mapping(
					@Schema(description = "Source terms matched by the mapping rule.", required = true)
					List<String> from,

					@Schema(
						description = "Target terms that the source terms map to.",
						required = true
					)
					List<String> to
				) {
				}
			}
		}
	}

	/**
	 * Configures result ordering through tie-breaking rules and signal
	 * multipliers.
	 *
	 * <p>Tie breakers are applied in sequence after primary sort criteria or
	 * relevance scoring, resolving ordering among tied documents without
	 * modifying the primary sort.
	 *
	 * <p>Signals multiply document attribute values into relevance scores,
	 * ranking documents with higher values above otherwise equal matches.
	 * Signals apply only when results are ordered by relevance; queries
	 * specifying an explicit sort order ignore them.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Tie-breaking rules and signal score multipliers. See \
		[Relevance](https://exofind.dev/explanation/relevance/).""")
	public record Ranking(
		/**
		 * Tie breakers applied in sequence until ties between documents are
		 * resolved.
		 */
		@Schema(description = """
			Secondary sort criteria applied in sequence after primary sort or \
			relevance scoring until ties are resolved. Target fields must have \
			`sort` enabled.""")
		List<TieBreaker> tieBreakers,

		/**
		 * Document values multiplied into relevance scores in sequence.
		 */
		@Schema(description = """
			Document values multiplied into relevance scores in sequence. \
			Evaluated at search time without reindexing, and applied only when \
			results are ordered by relevance. A search request that specifies \
			`signals` adds to these rules or replaces them based on \
			`signalsMode`.""")
		List<Signal> signals
	) {
		/**
		 * A single document attribute value multiplied into relevance.
		 *
		 * <p>The value is read from a sortable field, normalized to a number
		 * between zero and one, and applied to the score as
		 * {@code 1 + weight * shape}. A document with no value contributes
		 * nothing to the calculation, ensuring the signal increases the score
		 * by at most its weight without overriding match relevance.
		 *
		 * <p>Each signal requires exactly one shape matching the field type:
		 * saturation relative to a pivot for numeric fields, or decay over time
		 * for timestamp fields.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = """
			A single document attribute value multiplied into relevance. The \
			value is read from a sortable field, normalized to a value between \
			`0` and `1`, and applied to the score as `1 + weight * shape`. A \
			document with no value contributes `0`, ensuring a signal boosts a \
			score by at most its configured weight. Each signal must specify \
			exactly one shape matching the field type.""")
		public record Signal(
			/**
			 * The field to read the value from. Must be a number or timestamp
			 * field with sorting enabled.
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
			 * Ranks by how far the value rises above a pivot. For numeric
			 * fields.
			 */
			@Schema(description = """
				Ranks by how far the value rises above a pivot. For `int32`, \
				`int64`, `float` and `double` fields.""")
			Saturation saturation,

			/**
			 * Ranks by how long ago the value was. For timestamp fields.
			 */
			@Schema(description = """
				Ranks by how long ago the value was. For `timestamp` \
				fields.""")
			Decay decay,

			/**
			 * How much the signal can lift a document at most, as a share of
			 * its score. Defaults to 1, where a document at the top of the
			 * signal reaches twice the score of a document holding no value.
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
			 * Ranks by how far a value rises above a pivot, computed as
			 * {@code value / (value + pivot)}. Reaches 0.5 at the pivot and
			 * approaches 1 above it. Values below 0 evaluate to 0. Used for
			 * counts with no ceiling, such as how often something was bought.
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
				 * Required, and must be greater than zero.
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
			 * Ranks by how long ago a value was, halving the multiplier every
			 * half-life. Values dated at or after the current time evaluate to
			 * 1.
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
				 * much. Required, and must be greater than zero.
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
		 * A tie-breaking rule using a field defined for sorting.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "A tie-breaking rule using a field defined for sorting.")
		public record TieBreaker(
			/**
			 * The field to break ties by. Must have sorting enabled.
			 */
			@Schema(
				description = """
					The field to break ties by. Must have `sort` enabled.""",
				required = true,
				examples = "sales"
			)
			String field,

			/**
			 * The sort direction for breaking ties. Defaults to
			 * {@code descending}.
			 */
			@Schema(
				description = """
					The sort direction for breaking ties. Defaults to \
					`descending`.""",
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
