package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Specifies how the text of a usage is analyzed. Specify exactly one of
 * {@code preset}, {@code custom}, or {@code named}:
 *
 * <pre>
 * "analyzer": { "preset": "full_text" }
 * "analyzer": { "custom": { "filters": [ { "normalize": {} } ] } }
 * "analyzer": { "named": "prose" }
 * </pre>
 *
 * <p>A preset specifies a predefined analyzer chain. The engine expands the
 * preset before storing the index definition. A named chain references an
 * analyzer defined under resources in the index definition to share analyzer
 * configurations across fields.
 *
 * <p>Components that select words by locale, such as stopwords and stemming,
 * use the locale of the value being analyzed unless specified.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	Specifies how the text of a usage is analyzed, with exactly one of \
	`preset`, `custom`, or `named`. An analyzer chain describes the indexing \
	process. The engine derives the query analyzer from the indexing chain. \
	Components that select words by locale, such as stopwords and stemming, \
	use the locale of the value being analyzed unless you specify a locale. \
	See [Analysis](https://exofind.dev/reference/analysis/).""")
public record AnalyzerDefinition(
	/**
	 * A predefined analyzer chain expanded before storing the index definition.
	 * {@code preserve_terms} tokenizes and normalizes text, but keeps each word
	 * whole, for names, codes, and SKUs. {@code full_text} tokenizes and
	 * normalizes text, removes stopwords, splits compound words, and stems
	 * words, for prose.
	 */
	@Schema(description = """
		A preset specifies a predefined analyzer chain. The engine expands the \
		preset before storing the index definition.""")
	Preset preset,

	/**
	 * A custom analyzer chain that defines character filters, a tokenizer, and
	 * token filters.
	 */
	@Schema(description = """
		A custom analyzer chain that defines character filters, a tokenizer, \
		and token filters.""")
	Custom custom,

	/**
	 * The name of an analyzer defined under resources in the index definition,
	 * used to share analyzer configurations across fields.
	 */
	@Schema(
		description = """
			A named chain references an analyzer defined under `resources` in \
			the index definition. Used to share analyzer configurations across \
			fields. Validation fails if the specified name does not exist \
			under `resources`.""",
		examples = "prose"
	)
	String named
) {
	@Schema(description = """
		A predefined analyzer chain. `preserve_terms` tokenizes and normalizes \
		text, but keeps each word whole, for names, codes, and SKUs. \
		`full_text` tokenizes and normalizes text, removes stopwords, splits \
		compound words, and stems words, for prose.""")
	public enum Preset {
		@JsonProperty("preserve_terms")
		PRESERVE_TERMS,

		@JsonProperty("full_text")
		FULL_TEXT
	}

	/**
	 * A custom analyzer chain that defines character filters, a tokenizer, and
	 * token filters. The chain describes the indexing process; the engine
	 * derives the query analyzer from it.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		A custom analyzer chain that defines character filters, a tokenizer, \
		and token filters. Each component is an object with one key that \
		specifies the component type, for example `{ "whitespace": {} }`.""")
	public record Custom(
		/**
		 * Character filters applied to the raw text before tokenization, in
		 * order.
		 */
		@Schema(description = """
			An array of character filters applied to the raw text before \
			tokenization, in order.""")
		List<CharFilter> charFilters,

		/**
		 * The tokenizer that splits text into tokens. If omitted, the engine
		 * chooses a tokenizer based on the locale of the value (Unicode
		 * segmentation for most locales; language-specific segmentation for
		 * Chinese, Japanese, and Korean).
		 */
		@Schema(description = """
			The tokenizer that splits text into tokens. If omitted, the engine \
			chooses a tokenizer based on the locale of the value (Unicode \
			segmentation for most locales; language-specific segmentation for \
			Chinese, Japanese, and Korean).""")
		Tokenizer tokenizer,

		/**
		 * Token filters applied to tokens, in order.
		 */
		@Schema(description = "An array of token filters applied to tokens, in order.")
		List<TokenFilter> filters
	) {
	}

	/**
	 * Specifies how text is split into tokens. Specify exactly one tokenizer by
	 * including its configuration: {@code { "whitespace": {} }}.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Specifies how text is split into tokens. Specify exactly one tokenizer \
		by including its configuration, for example `{ "whitespace": {} }`.""")
	public record Tokenizer(
		/**
		 * Segments text based on Unicode rules. This is the default tokenizer.
		 */
		@Schema(description = """
			Segments text based on Unicode rules. This is the default \
			tokenizer.""")
		Icu icu,

		/**
		 * Splits text on whitespace characters.
		 */
		@Schema(description = "Splits text on whitespace characters.")
		Whitespace whitespace,

		/**
		 * Retains the entire input value as a single token.
		 */
		@Schema(description = "Retains the entire input value as a single token.")
		Keyword keyword,

		/**
		 * Splits text on non-letter characters.
		 */
		@Schema(description = "Splits text on non-letter characters.")
		Letter letter
	) {
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "IcuTokenizer",
			description = "Unicode segmentation. Carries no options."
		)
		public record Icu() {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "WhitespaceTokenizer",
			description = "Whitespace segmentation. Carries no options."
		)
		public record Whitespace() {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "KeywordTokenizer",
			description = "Retains the entire input value as a single token. Carries no options."
		)
		public record Keyword() {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "LetterTokenizer",
			description = "Splits text on non-letter characters. Carries no options."
		)
		public record Letter() {
		}
	}

	/**
	 * A transformation of the raw text before tokenization. Specify exactly one
	 * character filter by including its configuration.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		A transformation of the raw text before tokenization. Specify exactly \
		one character filter by including its configuration.""")
	public record CharFilter(
		/**
		 * Strips HTML and XML markup and keeps text between tags.
		 */
		@Schema(description = """
			Strips HTML and XML markup and keeps text between tags.""")
		HtmlStrip htmlStrip,

		/**
		 * Replaces occurrences of each key with its value.
		 */
		@Schema(description = "Replaces occurrences of each key with its value.")
		Mapping mapping,

		/**
		 * Replaces substrings that match a regular expression.
		 */
		@Schema(description = """
			Replaces substrings that match a regular expression.""")
		PatternReplace patternReplace
	) {
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = """
			Strips HTML and XML markup and keeps text between tags. Carries no \
			options.""")
		public record HtmlStrip() {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "MappingCharFilter",
			description = "Literal text replacements applied before tokenization."
		)
		public record Mapping(
			@Schema(
				description = "Replaces occurrences of each key with its value.",
				required = true
			)
			Map<String, String> mappings
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "Replaces substrings that match a regular expression.")
		public record PatternReplace(
			@Schema(
				description = "The regular expression to match.",
				required = true
			)
			String pattern,

			@Schema(
				description = "What each match is replaced with.",
				required = true
			)
			String replacement
		) {
		}
	}

	/**
	 * A transformation of the token stream. Exactly one kind is given,
	 * selected by including its configuration.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		A transformation of the token stream. Exactly one kind is given, \
		selected by including its configuration.""")
	public record TokenFilter(
		/**
		 * Applies Unicode normalization and case folding to make analysis
		 * case-insensitive.
		 */
		@Schema(description = """
			Applies Unicode normalization and case folding to make analysis \
			case-insensitive.""")
		Normalize normalize,

		/**
		 * Removes frequent words.
		 */
		@Schema(description = "Removes frequent words.")
		Stopwords stopwords,

		/**
		 * Reduces words to a shared root.
		 */
		@Schema(description = "Reduces words to a shared root.")
		Stemming stemming,

		/**
		 * Converts non-ASCII characters to ASCII equivalents.
		 */
		@Schema(description = """
			Converts non-ASCII characters to ASCII equivalents.""")
		AsciiFolding asciiFolding,

		/**
		 * Generates prefix n-grams for tokens within the specified character
		 * lengths.
		 */
		@Schema(description = """
			Generates prefix n-grams for tokens within the specified character \
			lengths.""")
		EdgeNgram edgeNgram,

		/**
		 * Generates substring n-grams for tokens within the specified character
		 * lengths.
		 */
		@Schema(description = """
			Generates substring n-grams for tokens within the specified \
			character lengths.""")
		Ngram ngram,

		/**
		 * Expands tokens with synonyms from a synonym set defined in the
		 * resources of the index. Applied when a value is indexed, not when the
		 * text of a search is analyzed.
		 */
		@Schema(description = """
			Expands tokens with synonyms from a synonym set defined in \
			`resources`. Applied when a value is indexed, not when the text of \
			a search is analyzed.""")
		Synonyms synonyms,

		/**
		 * Splits compound words into parts and retains the original compound
		 * word. Applied at index time.
		 */
		@Schema(description = """
			Splits compound words into parts and retains the original compound \
			word. See [Compound \
			words](https://exofind.dev/reference/analysis/#compound-words). \
			Applied at index time.""")
		Decompound decompound
	) {
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "Unicode normalization and case folding.")
		public record Normalize(
			/**
			 * Whether case folding is applied. Defaults to true.
			 */
			@Schema(
				description = "Whether case folding is applied.",
				defaultValue = "true"
			)
			Boolean caseFolding
		) {
		}

		/**
		 * Removes frequent words. Specifies stopwords by locale, an explicit
		 * list of words, or a stopword list defined in the resources of the
		 * index. At most one of the three is given; an empty object uses the
		 * stopwords of the locale of the value being analyzed.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "StopwordsFilter",
			description = """
				Removes frequent words. At most one of `locale`, `words` and \
				`named` is given; an empty object uses the stopwords of the \
				locale of the value being analyzed."""
		)
		public record Stopwords(
			/**
			 * The BCP-47 locale whose stopwords to remove.
			 */
			@Schema(
				description = "BCP-47 locale whose stopwords to remove.",
				examples = "sv"
			)
			String locale,

			/**
			 * A list of words to remove.
			 */
			@Schema(description = "A list of words to remove.")
			List<String> words,

			/**
			 * The name of a stopword list defined in the resources of the
			 * index.
			 */
			@Schema(
				description = """
					Name of a stopword list defined under the index's \
					`resources`.""",
				examples = "brands"
			)
			String named
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "Reduces words to a shared root.")
		public record Stemming(
			/**
			 * The BCP-47 locale whose rules to stem by. If omitted, uses the
			 * stemmer for the locale of the value being analyzed.
			 */
			@Schema(
				description = """
					BCP-47 locale whose rules to stem by. If omitted, uses the \
					stemmer for the locale of the value being analyzed.""",
				examples = "sv"
			)
			String locale
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "Converts non-ASCII characters to ASCII equivalents.")
		public record AsciiFolding(
			/**
			 * Whether to preserve the original non-ASCII token alongside the
			 * folded one. Defaults to false.
			 */
			@Schema(
				description = """
					Whether to preserve the original non-ASCII token alongside \
					the folded one.""",
				defaultValue = "false"
			)
			Boolean preserveOriginal
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "Generates prefix n-grams for tokens.")
		public record EdgeNgram(
			/**
			 * The shortest prefix to index. Defaults to 1.
			 */
			@Schema(description = "The shortest prefix to index.", defaultValue = "1")
			Integer minGram,

			/**
			 * The longest prefix to index. Defaults to 20.
			 */
			@Schema(description = "The longest prefix to index.", defaultValue = "20")
			Integer maxGram
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = """
			Generates substring n-grams for tokens within the specified \
			character lengths.""")
		public record Ngram(
			@Schema(description = "The shortest substring to index.")
			Integer minGram,

			@Schema(description = "The longest substring to index.")
			Integer maxGram
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "SynonymsFilter",
			description = """
				Expands tokens with synonyms from a synonym set defined in \
				`resources`. Applied when a value is indexed, not when the \
				text of a search is analyzed. See [Applying a synonym set to a \
				field](https://exofind.dev/reference/analysis/#applying-a-synonym-set-to-a-field)."""
		)
		public record Synonyms(
			/**
			 * The name of a synonym set defined in the resources of the
			 * index.
			 */
			@Schema(
				description = """
					Name of a synonym set defined under the index's \
					`resources`.""",
				required = true,
				examples = "cars"
			)
			String named
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "DecompoundFilter",
			description = """
				Splits compound words into parts and retains the original \
				compound word. See [Compound \
				words](https://exofind.dev/reference/analysis/#compound-words). \
				If omitted, uses the dictionary for the locale of the value. \
				Applied at index time."""
		)
		public record Decompound(
			/**
			 * The locale whose rules and dictionary split the words
			 * (BCP-47). Absent means the locale of the value being analyzed;
			 * a value in a locale the engine has no decompounding data for
			 * passes through unsplit.
			 */
			@Schema(
				description = """
					BCP-47 locale whose rules and dictionary split the words. \
					Omitted, the locale of the value being analyzed is used; a \
					value in a locale the engine has no decompounding data for \
					passes through unsplit.""",
				examples = "sv"
			)
			String locale
		) {
		}
	}
}
