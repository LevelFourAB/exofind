package se.l4.exofind.engine.index.analysis;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.analysis.charfilter.MappingCharFilter;
import org.apache.lucene.analysis.charfilter.NormalizeCharMap;
import org.apache.lucene.analysis.core.FlattenGraphFilter;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.core.LetterTokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.icu.ICUNormalizer2Filter;
import org.apache.lucene.analysis.icu.segmentation.ICUTokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.TruncateTokenFilter;
import org.apache.lucene.analysis.ngram.EdgeNGramTokenFilter;
import org.apache.lucene.analysis.ngram.NGramTokenFilter;
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilter;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.util.CharsRefBuilder;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.map.ImmutableMap;

import com.ibm.icu.text.Normalizer2;

import se.l4.exofind.engine.index.locales.LocaleSupport;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.schema.AnalyzerDef;
import se.l4.exofind.engine.index.schema.CharFilterDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.TokenFilterDef;

/**
 * Builds Lucene analyzers from stored analysis chains.
 *
 * A chain describes the indexing side and the querying side is derived from
 * it, which is where {@link AnalyzerMode} matters: an edge n-gram indexes
 * every prefix of a token, and the text a user typed must not be cut into
 * prefixes again, so at query time it becomes a cut to the longest prefix
 * that was indexed. Synonyms widen what a value is indexed as, and widening
 * the query as well would count the same synonym twice, so at query time the
 * component drops away - and decompounding, which widens a value with the
 * parts of its compounds, drops away on the query side for the same reason.
 *
 * A component that takes a locale and has none set uses the locale of the
 * value being analyzed, which is what lets one chain serve a field whose
 * values come in several locales.
 *
 * A component can also name something shared through the resources of the
 * index - a stopword list, a synonym set, or a whole chain through
 * {@code analyzer_ref}. The resources are part of the cache key, so an
 * analyzer never outlives what it was built from.
 *
 * Analyzers are built once per distinct chain, resources, locale and side and
 * cached, so they are shared and safe to use from several threads.
 */
public final class Analyzers {
	/**
	 * The n-gram sizes used when a chain does not give them. For the edge
	 * n-gram of autocomplete the max is the longest prefix that can match:
	 * every prefix of a value up to that length is indexed, and what a user
	 * types is cut to the same length so that a longer query still matches
	 * the prefixes that were written.
	 */
	public static final int DEFAULT_MIN_GRAM = 1;
	public static final int DEFAULT_MAX_GRAM = 20;

	/**
	 * The chain built for matching when a definition does not give one: both
	 * sides normalized, compounds split, stopwords dropped and words stemmed,
	 * all by the locale of the value. The variant without splitting serves a
	 * usage that turned it off.
	 */
	private static final AnalyzerDef DEFAULT_MATCHING = defaultMatching(true);
	private static final AnalyzerDef DEFAULT_MATCHING_UNSPLIT = defaultMatching(false);

	private static AnalyzerDef defaultMatching(boolean decompound) {
		var builder = AnalyzerDef.newBuilder()
			.addFilters(
				TokenFilterDef.newBuilder()
					.setNormalize(TokenFilterDef.Normalize.getDefaultInstance())
			)
			.addFilters(
				TokenFilterDef.newBuilder()
					.setStopwords(TokenFilterDef.Stopwords.getDefaultInstance())
			);

		/*
		 * Splitting sits after stopwords - a function word like the Swedish
		 * `deras` happens to contain smaller words, and dropping it first
		 * keeps those out of the index - and before stemming, so the parts
		 * are stemmed like any other word.
		 */
		if(decompound) {
			builder.addFilters(
				TokenFilterDef.newBuilder()
					.setDecompound(TokenFilterDef.Decompound.getDefaultInstance())
			);
		}

		return builder
			.addFilters(
				TokenFilterDef.newBuilder()
					.setStemming(TokenFilterDef.Stemming.getDefaultInstance())
			)
			.build();
	}

	/**
	 * The chain built for autocomplete when a definition does not give one.
	 * Stopwords are kept - someone typing `the` should see what starts with
	 * it - and words are not stemmed, so that the prefixes are of the word as
	 * it was written.
	 */
	private static final AnalyzerDef DEFAULT_AUTOCOMPLETE = AnalyzerDef.newBuilder()
		.addFilters(
			TokenFilterDef.newBuilder()
				.setNormalize(TokenFilterDef.Normalize.getDefaultInstance())
		)
		.addFilters(
			TokenFilterDef.newBuilder()
				.setEdgeNgram(TokenFilterDef.EdgeNgram.getDefaultInstance())
		)
		.build();

	private record CacheKey(
		AnalyzerDef chain,
		ResourcesDef resources,
		String locale,
		AnalyzerMode mode
	) {
	}

	private static final ConcurrentHashMap<CacheKey, Analyzer> CACHE = new ConcurrentHashMap<>();

	private Analyzers() {
	}

	/**
	 * Get the analyzer for the matching usage of a field.
	 *
	 * @param config
	 *   the usage as defined on the field
	 * @param resources
	 *   what the index shares between fields
	 * @param locale
	 *   the locale of the value being analyzed
	 * @param mode
	 *   which side of the search the analyzer is for
	 * @return
	 */
	public static Analyzer matching(
		StringFieldTypeDef.TextUsageConfig config,
		ResourcesDef resources,
		LocaleSupport locale,
		AnalyzerMode mode
	) {
		/*
		 * The decompound setting only steers the engine-built chain - a chain
		 * the definition gives says itself whether it splits, and the mapper
		 * refuses the combination.
		 */
		var fallback = config.getDecompound()
			== StringFieldTypeDef.TextUsageConfig.DecompoundMode.DECOMPOUND_MODE_NONE
				? DEFAULT_MATCHING_UNSPLIT
				: DEFAULT_MATCHING;

		return get(chainOf(config, resources, fallback), resources, locale, mode);
	}

	/**
	 * Get the analyzer for the autocomplete usage of a field.
	 *
	 * @param config
	 *   the usage as defined on the field
	 * @param resources
	 *   what the index shares between fields
	 * @param locale
	 *   the locale of the value being analyzed
	 * @param mode
	 *   which side of the search the analyzer is for
	 * @return
	 */
	public static Analyzer autocomplete(
		StringFieldTypeDef.TextUsageConfig config,
		ResourcesDef resources,
		LocaleSupport locale,
		AnalyzerMode mode
	) {
		return get(chainOf(config, resources, DEFAULT_AUTOCOMPLETE), resources, locale, mode);
	}

	/**
	 * Get the chain a usage analyzes with - the chain it names in the
	 * resources, the chain it carries inline, or the engine-built one.
	 */
	private static AnalyzerDef chainOf(
		StringFieldTypeDef.TextUsageConfig config,
		ResourcesDef resources,
		AnalyzerDef fallback
	) {
		if(config.hasAnalyzerRef()) {
			var chain = resources.getAnalyzersMap().get(config.getAnalyzerRef());
			if(chain == null) {
				/*
				 * A reference to nowhere is refused when the definition is
				 * validated, so getting here means that check was skipped.
				 */
				throw new IllegalStateException(
					"Analysis chain `" + config.getAnalyzerRef()
						+ "` is not among the resources of the index"
				);
			}
			return chain;
		}

		return config.hasAnalyzer() ? config.getAnalyzer() : fallback;
	}

	private static Analyzer get(
		AnalyzerDef chain,
		ResourcesDef resources,
		LocaleSupport locale,
		AnalyzerMode mode
	) {
		return CACHE.computeIfAbsent(
			new CacheKey(chain, resources, locale.getLocale(), mode),
			key -> new ChainAnalyzer(chain, resources, locale, mode)
		);
	}

	private static final class ChainAnalyzer extends Analyzer {
		private final AnalyzerDef chain;
		private final ResourcesDef resources;
		private final LocaleSupport locale;
		private final AnalyzerMode mode;

		/**
		 * The synonym maps of the chain, built once here because building one
		 * walks every rule of its set and the result is immutable and shared
		 * across the token streams of this analyzer.
		 */
		private final ImmutableMap<String, SynonymMap> synonyms;

		ChainAnalyzer(
			AnalyzerDef chain,
			ResourcesDef resources,
			LocaleSupport locale,
			AnalyzerMode mode
		) {
			this.chain = chain;
			this.resources = resources;
			this.locale = locale;
			this.mode = mode;
			this.synonyms = buildSynonymMaps(chain, resources);
		}

		@Override
		protected Reader initReader(String fieldName, Reader reader) {
			var result = reader;
			for(var filter : chain.getCharFiltersList()) {
				result = switch(filter.getFilterCase()) {
					case HTML_STRIP -> new HTMLStripCharFilter(result);
					case MAPPING -> new MappingCharFilter(
						mappings(filter.getMapping()),
						result
					);
					case PATTERN_REPLACE -> new PatternReplaceCharFilter(
						Pattern.compile(filter.getPatternReplace().getPattern()),
						filter.getPatternReplace().getReplacement(),
						result
					);
					case FILTER_NOT_SET -> throw unknownComponent();
				};
			}
			return result;
		}

		/**
		 * The char filters run when a value is taken whole as well. A mapping
		 * or a pattern replacement rewrites text before anything is cut into
		 * words, so what it says about the words of a value it says about the
		 * value itself.
		 */
		@Override
		protected Reader initReaderForNormalization(String fieldName, Reader reader) {
			return initReader(fieldName, reader);
		}

		/**
		 * The part of the chain that rewrites a token without splitting,
		 * joining or dropping it, run over a whole value as if it were one
		 * token. This is what a value is written and looked up as when a usage
		 * asks to rank a whole-value match above a mention, and it is also
		 * what Lucene runs over the text of a query that reaches the terms of
		 * the field without being analyzed, such as a prefix.
		 *
		 * Stemming, stopwords, synonyms, decompounding and the n-grams are
		 * left out because none of them answer for a value taken whole - they
		 * turn one token into none, into several, or into one that no longer
		 * spells what was written. What is left is the normalization: the case
		 * folding and Unicode forms of the chain, and the folding of accents
		 * where it asked for that. Preserving the original is dropped along
		 * with the rest, as two tokens is not a normalization.
		 */
		@Override
		protected TokenStream normalize(String fieldName, TokenStream in) {
			var stream = in;

			for(var filter : chain.getFiltersList()) {
				stream = switch(filter.getFilterCase()) {
					case NORMALIZE -> normalize(filter.getNormalize(), stream);
					case ASCII_FOLDING -> new ASCIIFoldingFilter(stream, false);
					default -> stream;
				};
			}

			return stream;
		}

		@Override
		protected TokenStreamComponents createComponents(String fieldName) {
			var tokenizer = createTokenizer();

			TokenStream stream = tokenizer;
			for(var filter : chain.getFiltersList()) {
				stream = append(filter, stream);
			}

			return new TokenStreamComponents(tokenizer, stream);
		}

		private Tokenizer createTokenizer() {
			if(!chain.hasTokenizer()) {
				/*
				 * The locale of the value decides how its text splits into
				 * words - Unicode segmentation for most locales, the locale's
				 * own for those whose words Unicode alone cannot find.
				 */
				return locale.createTokenizer();
			}

			return switch(chain.getTokenizer().getTokenizerCase()) {
				case ICU -> new ICUTokenizer();
				case WHITESPACE -> new WhitespaceTokenizer();
				case KEYWORD -> new KeywordTokenizer();
				case LETTER -> new LetterTokenizer();
				case TOKENIZER_NOT_SET -> throw unknownComponent();
			};
		}

		private TokenStream append(TokenFilterDef filter, TokenStream stream) {
			return switch(filter.getFilterCase()) {
				case NORMALIZE -> normalize(filter.getNormalize(), stream);
				case STOPWORDS -> new StopFilter(stream, stopwords(filter.getStopwords()));
				case STEMMING -> resolve(
					filter.getStemming().hasLocale() ? filter.getStemming().getLocale() : null
				).stem(stream);
				case ASCII_FOLDING -> new ASCIIFoldingFilter(
					stream,
					filter.getAsciiFolding().getPreserveOriginal()
				);
				case EDGE_NGRAM -> edgeNgram(filter.getEdgeNgram(), stream);
				case NGRAM -> new NGramTokenFilter(
					stream,
					filter.getNgram().hasMinGram()
						? filter.getNgram().getMinGram()
						: DEFAULT_MIN_GRAM,
					filter.getNgram().hasMaxGram()
						? filter.getNgram().getMaxGram()
						: DEFAULT_MAX_GRAM,
					false
				);
				case SYNONYMS -> synonyms(filter.getSynonyms(), stream);
				case DECOMPOUND -> decompound(filter.getDecompound(), stream);
				case FILTER_NOT_SET -> throw unknownComponent();
			};
		}

		/**
		 * Split compound words into their parts when indexing. The whole
		 * token is kept alongside the parts, so a compound query matches
		 * through it and the query side leaves the component out - splitting
		 * the query too would match documents holding only a part, trading
		 * away the precision the whole token carries.
		 */
		private TokenStream decompound(TokenFilterDef.Decompound config, TokenStream stream) {
			if(mode == AnalyzerMode.QUERYING) {
				return stream;
			}

			return resolve(config.hasLocale() ? config.getLocale() : null)
				.decompound(stream);
		}

		/**
		 * Unicode normalization, with what the locale needs on top of it when
		 * case is folded. The locale runs first: its own case folding has to
		 * see the text before the Unicode one flattens the distinctions it
		 * cares about, the way Turkish I only finds ı while it is still an I.
		 * Keeping case keeps the locale's forms too, so the hook follows the
		 * case folding setting.
		 */
		private TokenStream normalize(TokenFilterDef.Normalize config, TokenStream stream) {
			if(config.hasCaseFolding() && !config.getCaseFolding()) {
				return new ICUNormalizer2Filter(stream, Normalizer2.getNFKCInstance());
			}

			return new ICUNormalizer2Filter(
				locale.normalize(stream),
				Normalizer2.getNFKCCasefoldInstance()
			);
		}

		/**
		 * The one component that runs differently per side. Indexing writes
		 * every prefix of a token; querying keeps the token whole, cut to the
		 * longest prefix that was written so that typing past that length
		 * keeps matching instead of suddenly finding nothing.
		 */
		private TokenStream edgeNgram(TokenFilterDef.EdgeNgram config, TokenStream stream) {
			var maxGram = config.hasMaxGram() ? config.getMaxGram() : DEFAULT_MAX_GRAM;

			if(mode == AnalyzerMode.QUERYING) {
				return new TruncateTokenFilter(stream, maxGram);
			}

			return new EdgeNGramTokenFilter(
				stream,
				config.hasMinGram() ? config.getMinGram() : DEFAULT_MIN_GRAM,
				maxGram,
				false
			);
		}

		/**
		 * Widen tokens with their synonyms when indexing. A value containing
		 * one word of a rule is indexed under the others too, so a search for
		 * any of them finds the document; the query side leaves the component
		 * out and searches what was typed.
		 */
		private TokenStream synonyms(TokenFilterDef.Synonyms config, TokenStream stream) {
			if(mode == AnalyzerMode.QUERYING) {
				return stream;
			}

			var map = synonyms.get(config.getName());
			if(map == null) {
				// The set exists but holds no rules, so there is nothing to add
				return stream;
			}

			/*
			 * A synonym of several words comes out of the filter as a graph,
			 * which an index can not hold; flattening approximates it with
			 * positions, which is as close as Lucene gets at indexing time.
			 */
			return new FlattenGraphFilter(new SynonymGraphFilter(stream, map, true));
		}

		private CharArraySet stopwords(TokenFilterDef.Stopwords config) {
			return switch(config.getSourceCase()) {
				case LOCALE -> resolve(
					config.getLocale().hasLocale() ? config.getLocale().getLocale() : null
				).getStopWords();
				case CUSTOM -> new CharArraySet(config.getCustom().getWordsList(), true);
				case NAMED -> namedStopwords(config.getNamed().getName());
				case SOURCE_NOT_SET -> locale.getStopWords();
			};
		}

		private CharArraySet namedStopwords(String name) {
			var resource = resources.getStopwordsMap().get(name);
			if(resource == null) {
				/*
				 * A reference to nowhere is refused when the definition is
				 * validated, so getting here means that check was skipped.
				 */
				throw new IllegalStateException(
					"Stopword list `" + name + "` is not among the resources of the index"
				);
			}

			return new CharArraySet(resource.getWordsList(), true);
		}

		/**
		 * Get the support a component asks for - the named locale, or the
		 * locale of the value when it names none. A named locale is checked
		 * when the definition is validated, so missing support here is a bug
		 * rather than bad input.
		 */
		private LocaleSupport resolve(String tag) {
			if(tag == null) {
				return locale;
			}

			return Locales.get(tag)
				.orElseThrow(() -> new IllegalStateException(
					"Analysis chain names locale `" + tag + "` which this build does not support"
				));
		}

		private static ImmutableMap<String, SynonymMap> buildSynonymMaps(
			AnalyzerDef chain,
			ResourcesDef resources
		) {
			var maps = Maps.mutable.<String, SynonymMap>empty();

			for(var filter : chain.getFiltersList()) {
				if(filter.getFilterCase() != TokenFilterDef.FilterCase.SYNONYMS) {
					continue;
				}

				var name = filter.getSynonyms().getName();
				var resource = resources.getSynonymsMap().get(name);
				if(resource == null) {
					throw new IllegalStateException(
						"Synonym set `" + name + "` is not among the resources of the index"
					);
				}

				var map = buildSynonymMap(resource);
				if(map != null) {
					maps.put(name, map);
				}
			}

			return maps.toImmutable();
		}

		/**
		 * Turn the rules of a synonym set into the automaton the filter runs.
		 * Words are matched as they are where the component sits in the
		 * chain, so a set used after normalization is written in lowercase.
		 *
		 * @return
		 *   the map, or {@code null} when the set has no rules to apply
		 */
		private static SynonymMap buildSynonymMap(ResourcesDef.SynonymsResource resource) {
			if(resource.getRulesCount() == 0) {
				return null;
			}

			var builder = new SynonymMap.Builder(true);

			for(var rule : resource.getRulesList()) {
				switch(rule.getRuleCase()) {
					case EQUIVALENT -> {
						var terms = rule.getEquivalent().getTermsList();
						for(var from : terms) {
							for(var to : terms) {
								if(!from.equals(to)) {
									add(builder, from, to);
								}
							}
						}
					}
					case MAPPING -> {
						for(var from : rule.getMapping().getFromList()) {
							for(var to : rule.getMapping().getToList()) {
								add(builder, from, to);
							}
						}
					}
					case RULE_NOT_SET -> throw unknownComponent();
				}
			}

			try {
				return builder.build();
			} catch(IOException e) {
				throw new UncheckedIOException("Unable to build synonym map", e);
			}
		}

		/**
		 * Add one direction of a rule. A phrase of several words is joined
		 * the way the filter expects, so it matches the words in sequence.
		 */
		private static void add(SynonymMap.Builder builder, String from, String to) {
			var in = new CharsRefBuilder();
			SynonymMap.Builder.join(from.trim().split("\\s+"), in);

			var out = new CharsRefBuilder();
			SynonymMap.Builder.join(to.trim().split("\\s+"), out);

			builder.add(in.get(), out.get(), true);
		}

		private static NormalizeCharMap mappings(CharFilterDef.Mapping config) {
			var builder = new NormalizeCharMap.Builder();
			for(var entry : config.getMappingsMap().entrySet()) {
				builder.add(entry.getKey(), entry.getValue());
			}
			return builder.build();
		}

		private static IllegalStateException unknownComponent() {
			/*
			 * A chain with a component this build has no code for is refused
			 * through the required features of the definition, so getting here
			 * means that check was skipped.
			 */
			return new IllegalStateException(
				"Analysis chain contains a component this build does not know"
			);
		}
	}
}
