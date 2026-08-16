package se.l4.exofind.engine.api.v1alpha1.admin.model;

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
public record StringFieldDefinition(
	Boolean primaryKey,
	Boolean required,
	Boolean multiple,
	Boolean stored,
	Locales locales,
	Filter filter,
	Sort sort,
	Facet facet,

	/**
	 * How values are normalized before they are compared exactly, which is
	 * what filtering does.
	 */
	Keyword keyword,

	/**
	 * Enables searching the field using a query, where the text is analyzed
	 * into terms.
	 */
	TextUsage matching,

	/**
	 * Enables using the field for autocompletion, where prefixes of the text
	 * match.
	 */
	TextUsage autocomplete,

	/**
	 * Enables reading values as paths through a tree, such as
	 * `Men/Shoes/Running`, which is what a category navigation is built out
	 * of.
	 */
	Hierarchy hierarchy
) implements FieldDefinition {
	/**
	 * How values are read as paths through a tree.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Hierarchy(
		/**
		 * What separates one level of a path from the next. Defaults to `/`.
		 * Part of how values were written, so changing it on an index holding
		 * documents needs those documents indexed again.
		 */
		String separator
	) {
	}

	/**
	 * How values are normalized before they are compared exactly.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Keyword(
		/**
		 * If case is folded away before values are compared. Defaults to
		 * true, so filtering on `Fiction` also finds `fiction`.
		 */
		Boolean caseFolding
	) {
	}

	/**
	 * One shape for every text usage. Which slot it sits in - `matching` or
	 * `autocomplete` - decides what the engine builds when no analyzer is
	 * given.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TextUsage(
		/**
		 * How the text is analyzed. Absent means the engine builds analysis
		 * from the locale of the value and the usage.
		 */
		AnalyzerDefinition analyzer,

		/**
		 * How much a hit in this field counts relative to hits in other
		 * fields when text is searched across several. Defaults to 1.
		 */
		Float weight,

		/**
		 * Enables highlighting where in the text the matches were, which
		 * requires the text to be stored.
		 */
		Highlight highlight,

		/**
		 * Enables matching words despite typing mistakes, including in the
		 * word somebody is still typing.
		 */
		TypoTolerance typoTolerance,

		/**
		 * Whether the engine-built chain splits compound words into their
		 * parts. Absent means the engine decides by the locale of the value.
		 * Only usable when the engine builds the chain - a chain given
		 * through {@code analyzer} says itself whether it splits.
		 */
		Decompound decompound,

		/**
		 * Enables ranking a value the search matched whole above one that
		 * merely holds the same words, so a document named what was typed
		 * comes first.
		 */
		Exact exact,

		/**
		 * How much the length of a value counts against it. Absent means the
		 * engine decides, which is {@code moderate}.
		 */
		LengthNormalization lengthNormalization
	) {
		/**
		 * How highlighting matches within the text behaves.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Highlight() {
		}

		/**
		 * How a value the search matched whole is ranked against one that
		 * merely holds the same words.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Exact(
			/**
			 * How much a whole-value match adds, on the scale of what a hit in
			 * this field counts. Defaults to 2.
			 */
			Float boost
		) {
		}

		/**
		 * How much the length of a value counts against it, which is what
		 * ranks the same words covering a short value above them sitting
		 * inside a long one.
		 */
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
		public record TypoTolerance(
			/**
			 * The shortest word that may contain one typo. Defaults to 5.
			 */
			Integer minLengthOneTypo,

			/**
			 * The shortest word that may contain two typos. Defaults to 9 for
			 * `matching`; under `autocomplete` a word carries two typos only
			 * where this is given, since the second one costs several times
			 * the first there and finds little.
			 */
			Integer minLengthTwoTypos,

			/**
			 * How many leading characters have to match exactly. Defaults
			 * to 1.
			 */
			Integer prefixLength
		) {
		}
	}
}
