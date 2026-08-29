package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How the text of a usage is analyzed. Exactly one of {@code preset},
 * {@code custom} and {@code named} is given:
 *
 * <pre>
 * "analyzer": { "preset": "full_text" }
 * "analyzer": { "custom": { "filters": [ { "normalize": {} } ] } }
 * "analyzer": { "named": "prose" }
 * </pre>
 *
 * A preset is expanded to the chain it names before it is stored, so reading
 * the definition back shows the chain rather than the preset - what a preset
 * means can then never shift under an index that already exists. A named
 * chain refers to one defined once in the resources of the index, for chains
 * shared between fields.
 *
 * Components that pick words by locale - stopwords and stemming - follow the
 * locale of the value being analyzed unless they name one, which is what lets
 * one chain serve a field whose values come in several locales.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	How the text of a usage is analyzed, carrying exactly one of `preset`, \
	`custom` and `named`. A chain describes the indexing side; the engine \
	derives the querying side from it. Components that select words by locale, \
	such as stopwords and stemming, follow the locale of the value being \
	analyzed unless they name one. See \
	[Analysis](https://exofind.dev/reference/analysis/).""")
public record AnalyzerDefinition(
	/**
	 * A named chain the engine expands: {@code preserve_terms} tokenizes and
	 * normalizes but keeps every word whole, for names, codes and SKUs;
	 * {@code full_text} also drops stopwords and stems, for prose.
	 */
	@Schema(description = """
		A predefined chain, expanded before the definition is stored - so \
		reading the definition back shows the chain rather than the preset, \
		and what a preset means can never shift under an index that already \
		exists.""")
	Preset preset,

	/**
	 * A chain given in full.
	 */
	@Schema(description = "A chain given in full.")
	Custom custom,

	/**
	 * The name of a chain defined in the resources of the index.
	 */
	@Schema(
		description = """
			Name of a chain defined under the index's `resources`, for chains \
			shared between fields. Validation fails if no such name is \
			defined.""",
		examples = "prose"
	)
	String named
) {
	@Schema(description = """
		A predefined analyzer chain. `preserve_terms` tokenizes and normalizes \
		but keeps every word whole, for names, codes and SKUs. `full_text` \
		also removes stopwords, splits compound words and stems, for prose.""")
	public enum Preset {
		@JsonProperty("preserve_terms")
		PRESERVE_TERMS,

		@JsonProperty("full_text")
		FULL_TEXT
	}

	/**
	 * An analysis chain given in full. The chain describes the indexing side;
	 * the engine derives the querying side from it.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		An analysis chain given in full: character filters, a tokenizer and \
		token filters. Each component is an object with one key naming its \
		type, such as `{ "whitespace": {} }`.""")
	public record Custom(
		/**
		 * Run over the raw text before tokenization, in order.
		 */
		@Schema(description = """
			Character filters applied to the raw text before tokenization, in \
			order.""")
		List<CharFilter> charFilters,

		/**
		 * How the text is split into tokens. Left out, the engine picks for
		 * the locale of the value - Unicode segmentation for most locales,
		 * the locale's own for Chinese, Japanese and Korean.
		 */
		@Schema(description = """
			How the text is split into tokens. Omitted, the engine chooses by \
			the locale of the value - Unicode segmentation for most locales, \
			language-specific segmentation for Chinese, Japanese and \
			Korean.""")
		Tokenizer tokenizer,

		/**
		 * Run over the tokens, in order.
		 */
		@Schema(description = "Token filters applied to the tokens, in order.")
		List<TokenFilter> filters
	) {
	}

	/**
	 * How text is split into tokens. Exactly one kind is given, selected by
	 * including its configuration: {@code { "whitespace": {} }}.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		How text is split into tokens. Exactly one kind is given, selected by \
		including its configuration, such as `{ "whitespace": {} }`.""")
	public record Tokenizer(
		/**
		 * Segment on the rules of Unicode. The engine default.
		 */
		@Schema(description = """
			Segments text on the rules of Unicode. The engine default.""")
		Icu icu,

		/**
		 * Split on whitespace only.
		 */
		@Schema(description = "Splits text on whitespace characters.")
		Whitespace whitespace,

		/**
		 * Keep the whole value as one token.
		 */
		@Schema(description = "Retains the entire input value as a single token.")
		Keyword keyword,

		/**
		 * Split on anything that is not a letter.
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
			description = "Keeps the whole value as one token. Carries no options."
		)
		public record Keyword() {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "LetterTokenizer",
			description = "Splits on non-letter characters. Carries no options."
		)
		public record Letter() {
		}
	}

	/**
	 * A transformation of the raw text before it is tokenized. Exactly one
	 * kind is given, selected by including its configuration.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		A transformation of the raw text before it is tokenized. Exactly one \
		kind is given, selected by including its configuration.""")
	public record CharFilter(
		/**
		 * Strip HTML and XML markup, keeping the text between tags.
		 */
		@Schema(description = """
			Strips HTML and XML markup, keeping the text between tags.""")
		HtmlStrip htmlStrip,

		/**
		 * Replace occurrences of each key with its value.
		 */
		@Schema(description = "Replaces occurrences of each key with its value.")
		Mapping mapping,

		/**
		 * Replace everything a regular expression matches.
		 */
		@Schema(description = """
			Replaces substrings that match a regular expression.""")
		PatternReplace patternReplace
	) {
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = """
			Strips HTML and XML markup. Carries no options.""")
		public record HtmlStrip() {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(
			name = "MappingCharFilter",
			description = "Literal text replacements applied before tokenization."
		)
		public record Mapping(
			@Schema(
				description = """
					Replacements to apply, each key replaced by its value.""",
				required = true
			)
			Map<String, String> mappings
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "A regular-expression replacement applied before tokenization.")
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
		 * Unicode normalization, so the different ways of writing the same
		 * character compare as one. Folding case as part of it is what makes
		 * analysis case-insensitive.
		 */
		@Schema(description = """
			Applies Unicode normalization, so the different ways of writing the \
			same character compare as one. Folding case as part of it is what \
			makes analysis case-insensitive.""")
		Normalize normalize,

		/**
		 * Drop words that appear too often to tell documents apart.
		 */
		@Schema(description = """
			Removes words that appear too often to tell documents apart.""")
		Stopwords stopwords,

		/**
		 * Reduce words to a shared root, so a search for one form finds the
		 * others.
		 */
		@Schema(description = """
			Reduces words to a shared root, so a search for one form finds the \
			others.""")
		Stemming stemming,

		/**
		 * Fold characters outside ASCII to their closest ASCII equivalent.
		 */
		@Schema(description = """
			Converts non-ASCII characters to their closest ASCII \
			equivalent.""")
		AsciiFolding asciiFolding,

		/**
		 * Index every prefix of a token, for matching a partially typed word.
		 */
		@Schema(description = """
			Generates prefix n-grams, for matching a partially typed word.""")
		EdgeNgram edgeNgram,

		/**
		 * Index every substring of a token between the given lengths.
		 */
		@Schema(description = """
			Generates substring n-grams within the given character lengths.""")
		Ngram ngram,

		/**
		 * Widen tokens with the words that mean the same thing, from a
		 * synonym set defined in the resources of the index. Applied when a
		 * value is indexed, not when it is searched.
		 */
		@Schema(description = """
			Expands tokens with synonyms from a set defined under the index's \
			`resources`. Applied when a value is indexed, so changing a set \
			only affects documents indexed from there on.""")
		Synonyms synonyms,

		/**
		 * Split compound words into their parts, keeping the whole word
		 * alongside them, so a search for a part finds the compounds built
		 * from it. Applied when a value is indexed, not when it is searched.
		 */
		@Schema(description = """
			Splits compound words into their parts, keeping the whole word \
			alongside them, so a search for a part finds the compounds built \
			from it. Applied when a value is indexed. See [Compound \
			words](https://exofind.dev/reference/analysis/#compound-words).""")
		Decompound decompound
	) {
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "Unicode normalization and case folding.")
		public record Normalize(
			/**
			 * If case is folded away. Defaults to true.
			 */
			@Schema(
				description = "Whether case is folded away.",
				defaultValue = "true"
			)
			Boolean caseFolding
		) {
		}

		/**
		 * Where the words come from - the list of a locale, exactly the given
		 * words, or a list shared through the resources of the index. At most
		 * one of the three is given; an empty object means the words of the
		 * locale of the value being analyzed.
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
			 * The locale whose words to drop (BCP-47).
			 */
			@Schema(
				description = "BCP-47 locale whose stopwords to remove.",
				examples = "sv"
			)
			String locale,

			/**
			 * Exactly these words and no others.
			 */
			@Schema(description = "Exactly these words and no others.")
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
			 * The locale whose rules to stem by (BCP-47). Absent means the
			 * locale of the value being analyzed.
			 */
			@Schema(
				description = """
					BCP-47 locale whose rules to stem by. Omitted, the locale \
					of the value being analyzed is used.""",
				examples = "sv"
			)
			String locale
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "Converts non-ASCII characters to ASCII equivalents.")
		public record AsciiFolding(
			/**
			 * If the unfolded token is kept alongside the folded one.
			 * Defaults to false.
			 */
			@Schema(
				description = """
					Whether the original non-ASCII token is kept alongside the \
					folded one.""",
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
		@Schema(description = "Generates substring n-grams for tokens.")
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
			description = "Expands tokens with synonyms from a named set."
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
			description = "Splits compound words into their parts."
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
