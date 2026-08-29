package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Definition of a field containing text.
 *
 * The ways a string can be searched are opt-in, and each way is enabled by
 * including its configuration. An empty object enables it with the defaults of
 * the engine:
 *
 * <pre>
 * {
 *   "type": "string",
 *   "filter": {},
 *   "matching": { "highlight": {} }
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	Text data. The ways a string can be searched are opt-in, each enabled by \
	including its configuration; an empty object enables it with engine \
	defaults. See \
	[`string`](https://levelfourab.github.io/exofind/reference/field-types/#string).""")
public record StringFieldDefinition(
	/**
	 * What the field is for, expanded into the usages that serve it before the
	 * definition is stored. Accepts {@code id}, {@code title},
	 * {@code description}, {@code tag}, {@code path} and {@code code}.
	 */
	@Schema(description = FieldDefinition.ROLE_DESCRIPTION)
	Role role,

	@Schema(description = FieldDefinition.PRIMARY_KEY_DESCRIPTION, defaultValue = "false")
	Boolean primaryKey,

	@Schema(description = FieldDefinition.REQUIRED_DESCRIPTION, defaultValue = "false")
	Boolean required,

	@Schema(description = FieldDefinition.MULTIPLE_DESCRIPTION, defaultValue = "false")
	Boolean multiple,

	@Schema(description = FieldDefinition.STORED_DESCRIPTION, defaultValue = "false")
	Boolean stored,

	@Schema(description = FieldDefinition.LOCALES_DESCRIPTION)
	Locales locales,

	@Schema(description = FieldDefinition.FILTER_DESCRIPTION)
	Filter filter,

	@Schema(description = FieldDefinition.SORT_DESCRIPTION)
	Sort sort,

	@Schema(description = FieldDefinition.FACET_DESCRIPTION)
	Facet facet,

	/**
	 * How values are normalized before they are compared exactly, which is
	 * what filtering does.
	 */
	@Schema(description = """
		Configures exact-match normalization for filtering - how a value is \
		normalized before it is compared exactly.""")
	Keyword keyword,

	/**
	 * Enables searching the field using a query, where the text is analyzed
	 * into terms.
	 */
	@Schema(description = """
		Enables full-text search with analyzed terms.""")
	TextUsage matching,

	/**
	 * Enables using the field for autocompletion, where prefixes of the text
	 * match.
	 */
	@Schema(description = """
		Enables prefix matching for as-you-type search queries. A field \
		defined only for `autocomplete` does not support phrase matching.""")
	TextUsage autocomplete,

	/**
	 * Enables reading values as paths through a tree, such as
	 * `Men/Shoes/Running`, which is what a category navigation is built out
	 * of.
	 */
	@Schema(description = """
		Enables reading values as paths through a tree, such as \
		`Men/Shoes/Running` - what a category navigation is built out of. \
		Facets on such a field return nested counts per level, and the `under` \
		matcher filters to a level and everything below it.""")
	Hierarchy hierarchy
) implements FieldDefinition {
	/**
	 * How values are read as paths through a tree.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "How values are read as paths through a tree.")
	public record Hierarchy(
		/**
		 * What separates one level of a path from the next. Defaults to `/`.
		 * Part of how values were written, so changing it on an index holding
		 * documents needs those documents indexed again.
		 */
		@Schema(
			description = """
				What separates one level of a path from the next. Part of how \
				values were written, so changing it on an index holding \
				documents requires reindexing them.""",
			defaultValue = "/"
		)
		String separator
	) {
	}

	/**
	 * How values are normalized before they are compared exactly.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "How values are normalized before they are compared exactly.")
	public record Keyword(
		/**
		 * If case is folded away before values are compared. Defaults to
		 * true, so filtering on `Fiction` also finds `fiction`.
		 */
		@Schema(
			description = """
				Whether case is folded away before values are compared, so a \
				filter on `Fiction` also matches `fiction`.""",
			defaultValue = "true"
		)
		Boolean caseFolding
	) {
	}

	/**
	 * One shape for every text usage. Which slot it sits in - `matching` or
	 * `autocomplete` - decides what the engine builds when no analyzer is
	 * given.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Configuration shared by the `matching` and `autocomplete` usages. \
		Which slot it sits in decides what the engine builds when no analyzer \
		is given.""")
	public record TextUsage(
		/**
		 * How the text is analyzed. Absent means the engine builds analysis
		 * from the locale of the value and the usage.
		 */
		@Schema(description = """
			How the text is analyzed. Omitted, the engine generates an \
			analyzer from the field usage and locale. See \
			[Analysis](https://levelfourab.github.io/exofind/reference/analysis/).""")
		AnalyzerDefinition analyzer,

		/**
		 * How much a hit in this field counts relative to hits in other
		 * fields when text is searched across several. Defaults to 1.
		 */
		@Schema(
			description = """
				Relative score weight of hits in this field when querying \
				across several fields.""",
			defaultValue = "1"
		)
		Float weight,

		/**
		 * Enables highlighting where in the text the matches were, which
		 * requires the text to be stored.
		 */
		@Schema(description = """
			Enables highlighted snippet extraction in search responses. Text \
			is stored for highlighting regardless of `stored`. Highlighting \
			targets `matching` when it is defined; `highlight` on \
			`autocomplete` takes effect only when `matching` is omitted.""")
		Highlight highlight,

		/**
		 * Enables matching words despite typing mistakes, including in the
		 * word somebody is still typing.
		 */
		@Schema(description = """
			Enables matching words despite typing mistakes, including in the \
			word somebody is still typing.""")
		TypoTolerance typoTolerance,

		/**
		 * Whether the engine-built chain splits compound words into their
		 * parts. Absent means the engine decides by the locale of the value.
		 * Only usable when the engine builds the chain - a chain given
		 * through {@code analyzer} says itself whether it splits.
		 */
		@Schema(description = """
			Controls compound word splitting in the engine-generated chain. \
			Omitted, the engine decides by the locale of the value. Supported \
			only when the engine builds the chain - a chain given through \
			`analyzer` says itself whether it splits. See [Compound \
			words](https://levelfourab.github.io/exofind/reference/analysis/#compound-words).""")
		Decompound decompound,

		/**
		 * Enables ranking a value the search matched whole above one that
		 * merely holds the same words, so a document named what was typed
		 * comes first.
		 */
		@Schema(description = """
			Boosts documents where the query matches the full field value, so \
			a document named what was typed comes first. Adjusts ranking only, \
			without changing hit counts or facet distributions; analyzer \
			normalization is applied before the comparison.""")
		Exact exact,

		/**
		 * How much the length of a value counts against it. Absent means the
		 * engine decides, which is {@code moderate}.
		 */
		@Schema(
			description = """
				Controls the field length penalty in ranking. Takes effect at \
				search time without reindexing.""",
			defaultValue = "moderate"
		)
		LengthNormalization lengthNormalization
	) {
		/**
		 * How highlighting matches within the text behaves.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = """
			Enables highlighting matches within the text. Carries no \
			options.""")
		public record Highlight() {
		}

		/**
		 * How a value the search matched whole is ranked against one that
		 * merely holds the same words.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = """
			How a value the search matched whole is ranked against one that \
			merely holds the same words.""")
		public record Exact(
			/**
			 * How much a whole-value match adds, on the scale of what a hit in
			 * this field counts. Defaults to 2.
			 */
			@Schema(
				description = """
					How much a whole-value match adds, on the scale of what a \
					hit in this field counts.""",
				defaultValue = "2"
			)
			Float boost
		) {
		}

		/**
		 * How much the length of a value counts against it, which is what
		 * ranks the same words covering a short value above them sitting
		 * inside a long one.
		 */
		@Schema(description = """
			Field length penalty in ranking: `none` applies no penalty, \
			`moderate` normalizes the way prose reads, and `strong` applies \
			the full penalty, for a field holding names rather than prose.""")
		public enum LengthNormalization {
			/**
			 * Length is not counted at all, for a field where a longer value
			 * is not a worse answer, only a fuller one.
			 */
			@JsonProperty("none")
			NONE,

			/**
			 * Length counts the way it does for prose, which is what the
			 * engine does when nothing says otherwise.
			 */
			@JsonProperty("moderate")
			MODERATE,

			/**
			 * Length counts fully, for a field holding names rather than
			 * prose.
			 */
			@JsonProperty("strong")
			STRONG
		}

		/**
		 * Whether compound words are split into their parts.
		 */
		@Schema(description = """
			Compound word splitting in the engine-generated chain. `none` \
			disables splitting whatever the locale.""")
		public enum Decompound {
			/**
			 * Never split, whatever the locale.
			 */
			@JsonProperty("none")
			NONE
		}

		/**
		 * How matching words despite typing mistakes behaves.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = """
			Enables typo tolerance. Mixed alphanumeric words follow the \
			standard length thresholds.""")
		public record TypoTolerance(
			/**
			 * The shortest word that may contain one typo. Defaults to 5.
			 */
			@Schema(
				description = "The shortest word that may contain one typo.",
				defaultValue = "5"
			)
			Integer minLengthOneTypo,

			/**
			 * The shortest word that may contain two typos. Defaults to 9 for
			 * `matching`; under `autocomplete` a word carries two typos only
			 * where this is given, since the second one costs several times
			 * the first there and finds little.
			 */
			@Schema(
				description = """
					The shortest word that may contain two typos. Under \
					`autocomplete`, two typos are permitted only when this is \
					set explicitly, since the second costs several times the \
					first there and finds little.""",
				defaultValue = "9"
			)
			Integer minLengthTwoTypos,

			/**
			 * How many leading characters have to match exactly. Defaults
			 * to 1.
			 */
			@Schema(
				description = "How many leading characters must match exactly.",
				defaultValue = "1"
			)
			Integer prefixLength,

			/**
			 * Enables typos in words of digits alone, which are otherwise
			 * matched exactly however long they are - a number one digit off
			 * is a different number rather than a misspelling.
			 */
			@Schema(description = """
				Enables typos in digit-only words, which otherwise require \
				exact matches however long they are - a number one digit off \
				is a different number rather than a misspelling.""")
			Numbers numbers
		) {
			/**
			 * How words of digits alone are matched.
			 */
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = """
				Enables typo tolerance for digit-only words. Carries no \
				options.""")
			public record Numbers() {
			}
		}
	}
}
