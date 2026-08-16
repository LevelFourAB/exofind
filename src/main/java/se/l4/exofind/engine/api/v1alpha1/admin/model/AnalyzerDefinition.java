package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;
import java.util.Map;

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
public record AnalyzerDefinition(
	/**
	 * A named chain the engine expands: {@code preserve_terms} tokenizes and
	 * normalizes but keeps every word whole, for names, codes and SKUs;
	 * {@code full_text} also drops stopwords and stems, for prose.
	 */
	Preset preset,

	/**
	 * A chain given in full.
	 */
	Custom custom,

	/**
	 * The name of a chain defined in the resources of the index.
	 */
	String named
) {
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
	public record Custom(
		/**
		 * Run over the raw text before tokenization, in order.
		 */
		List<CharFilter> charFilters,

		/**
		 * How the text is split into tokens. Left out, the engine picks for
		 * the locale of the value - Unicode segmentation for most locales,
		 * the locale's own for Chinese, Japanese and Korean.
		 */
		Tokenizer tokenizer,

		/**
		 * Run over the tokens, in order.
		 */
		List<TokenFilter> filters
	) {
	}

	/**
	 * How text is split into tokens. Exactly one kind is given, selected by
	 * including its configuration: {@code { "whitespace": {} }}.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Tokenizer(
		/**
		 * Segment on the rules of Unicode. The engine default.
		 */
		Icu icu,

		/**
		 * Split on whitespace only.
		 */
		Whitespace whitespace,

		/**
		 * Keep the whole value as one token.
		 */
		Keyword keyword,

		/**
		 * Split on anything that is not a letter.
		 */
		Letter letter
	) {
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Icu() {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Whitespace() {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Keyword() {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Letter() {
		}
	}

	/**
	 * A transformation of the raw text before it is tokenized. Exactly one
	 * kind is given, selected by including its configuration.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CharFilter(
		/**
		 * Strip HTML and XML markup, keeping the text between tags.
		 */
		HtmlStrip htmlStrip,

		/**
		 * Replace occurrences of each key with its value.
		 */
		Mapping mapping,

		/**
		 * Replace everything a regular expression matches.
		 */
		PatternReplace patternReplace
	) {
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record HtmlStrip() {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Mapping(
			Map<String, String> mappings
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record PatternReplace(
			String pattern,
			String replacement
		) {
		}
	}

	/**
	 * A transformation of the token stream. Exactly one kind is given,
	 * selected by including its configuration.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TokenFilter(
		/**
		 * Unicode normalization, so the different ways of writing the same
		 * character compare as one. Folding case as part of it is what makes
		 * analysis case-insensitive.
		 */
		Normalize normalize,

		/**
		 * Drop words that appear too often to tell documents apart.
		 */
		Stopwords stopwords,

		/**
		 * Reduce words to a shared root, so a search for one form finds the
		 * others.
		 */
		Stemming stemming,

		/**
		 * Fold characters outside ASCII to their closest ASCII equivalent.
		 */
		AsciiFolding asciiFolding,

		/**
		 * Index every prefix of a token, for matching a partially typed word.
		 */
		EdgeNgram edgeNgram,

		/**
		 * Index every substring of a token between the given lengths.
		 */
		Ngram ngram,

		/**
		 * Widen tokens with the words that mean the same thing, from a
		 * synonym set defined in the resources of the index. Applied when a
		 * value is indexed, not when it is searched.
		 */
		Synonyms synonyms,

		/**
		 * Split compound words into their parts, keeping the whole word
		 * alongside them, so a search for a part finds the compounds built
		 * from it. Applied when a value is indexed, not when it is searched.
		 */
		Decompound decompound
	) {
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Normalize(
			/**
			 * If case is folded away. Defaults to true.
			 */
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
		public record Stopwords(
			/**
			 * The locale whose words to drop (BCP-47).
			 */
			String locale,

			/**
			 * Exactly these words and no others.
			 */
			List<String> words,

			/**
			 * The name of a stopword list defined in the resources of the
			 * index.
			 */
			String named
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Stemming(
			/**
			 * The locale whose rules to stem by (BCP-47). Absent means the
			 * locale of the value being analyzed.
			 */
			String locale
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record AsciiFolding(
			/**
			 * If the unfolded token is kept alongside the folded one.
			 * Defaults to false.
			 */
			Boolean preserveOriginal
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record EdgeNgram(
			/**
			 * The shortest prefix to index. Defaults to 1.
			 */
			Integer minGram,

			/**
			 * The longest prefix to index. Defaults to 20.
			 */
			Integer maxGram
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Ngram(
			Integer minGram,
			Integer maxGram
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Synonyms(
			/**
			 * The name of a synonym set defined in the resources of the
			 * index.
			 */
			String named
		) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Decompound(
			/**
			 * The locale whose rules and dictionary split the words
			 * (BCP-47). Absent means the locale of the value being analyzed;
			 * a value in a locale the engine has no decompounding data for
			 * passes through unsplit.
			 */
			String locale
		) {
		}
	}
}
