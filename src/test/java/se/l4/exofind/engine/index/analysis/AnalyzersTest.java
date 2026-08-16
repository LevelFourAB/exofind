package se.l4.exofind.engine.index.analysis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.decompound.Decompounder;
import se.l4.exofind.engine.index.locales.LocaleSupport;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.locales.StandardLocaleSupport;
import se.l4.exofind.engine.index.schema.AnalyzerDef;
import se.l4.exofind.engine.index.schema.CharFilterDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.TokenFilterDef;
import se.l4.exofind.engine.index.schema.TokenizerDef;

public class AnalyzersTest {
	private List<String> terms(Analyzer analyzer, String value) throws IOException {
		var terms = new ArrayList<String>();

		try(var stream = analyzer.tokenStream("field", value)) {
			var term = stream.addAttribute(CharTermAttribute.class);
			stream.reset();
			while(stream.incrementToken()) {
				terms.add(term.toString());
			}
			stream.end();
		}

		return terms;
	}

	private List<String> matchingTerms(
		StringFieldTypeDef.TextUsageConfig config,
		AnalyzerMode mode,
		String value
	) throws IOException {
		return terms(
			Analyzers.matching(
				config,
				ResourcesDef.getDefaultInstance(),
				Locales.getDefault(),
				mode
			),
			value
		);
	}

	private List<String> autocompleteTerms(AnalyzerMode mode, String value) throws IOException {
		return terms(
			Analyzers.autocomplete(
				StringFieldTypeDef.TextUsageConfig.getDefaultInstance(),
				ResourcesDef.getDefaultInstance(),
				Locales.getDefault(),
				mode
			),
			value
		);
	}

	private static StringFieldTypeDef.TextUsageConfig withChain(AnalyzerDef.Builder chain) {
		return StringFieldTypeDef.TextUsageConfig.newBuilder()
			.setAnalyzer(chain)
			.build();
	}

	/**
	 * Autocomplete indexes what a value starts with, so that a user sees
	 * results while they type.
	 */
	@Test
	public void testAutocompleteIndexesPrefixes() throws IOException {
		var terms = autocompleteTerms(AnalyzerMode.INDEXING, "search");

		assertThat(terms, hasItems("s", "se", "sea", "sear", "searc", "search"));
	}

	/**
	 * Prefixes and not every substring - matching the middle of a word is a
	 * different feature, and one nobody typing into a search box expects.
	 */
	@Test
	public void testAutocompleteDoesNotIndexTheMiddleOfWords() throws IOException {
		var terms = autocompleteTerms(AnalyzerMode.INDEXING, "search");

		assertThat(terms, not(hasItem("ear")));
		assertThat(terms, not(hasItem("rch")));
	}

	/**
	 * What the user typed is looked up as it is. Cutting it into prefixes on
	 * this side too would match everything those prefixes match.
	 */
	@Test
	public void testAutocompleteLooksUpWhatWasTypedAsOneTerm() throws IOException {
		assertThat(autocompleteTerms(AnalyzerMode.QUERYING, "sear"), contains("sear"));
	}

	/**
	 * Typing past the longest prefix that was indexed has to keep matching,
	 * which is what cutting the query to the same length is for.
	 */
	@Test
	public void testLongQueriesAreCutToTheLongestIndexedPrefix() throws IOException {
		var value = "extraordinarilylongsearchterm";

		var indexed = autocompleteTerms(AnalyzerMode.INDEXING, value);
		var queried = autocompleteTerms(AnalyzerMode.QUERYING, value);

		assertThat(queried.size(), is(1));
		assertThat(indexed, hasItem(queried.get(0)));
	}

	@Test
	public void testAutocompleteKeepsStopwords() throws IOException {
		assertThat(autocompleteTerms(AnalyzerMode.QUERYING, "the"), contains("the"));
	}

	/**
	 * Matching analyzes both sides the same way, so that a search for a word
	 * finds the documents that stem to it.
	 */
	@Test
	public void testMatchingStemsOnBothSides() throws IOException {
		var config = StringFieldTypeDef.TextUsageConfig.getDefaultInstance();

		var indexed = matchingTerms(config, AnalyzerMode.INDEXING, "running shoes");
		var queried = matchingTerms(config, AnalyzerMode.QUERYING, "running shoes");

		assertThat(indexed, is(queried));
	}

	@Test
	public void testMatchingDropsStopwordsByDefault() throws IOException {
		var terms = matchingTerms(
			StringFieldTypeDef.TextUsageConfig.getDefaultInstance(),
			AnalyzerMode.INDEXING,
			"the shoes"
		);

		assertThat(terms, not(hasItem("the")));
	}

	@Test
	public void testMatchingFoldsCaseByDefault() throws IOException {
		var terms = matchingTerms(
			StringFieldTypeDef.TextUsageConfig.getDefaultInstance(),
			AnalyzerMode.INDEXING,
			"Spring"
		);

		assertThat(terms, contains("spring"));
	}

	/**
	 * A chain on the usage replaces the engine-built one entirely - here
	 * nothing but normalization, so stopwords survive and words keep their
	 * form.
	 */
	@Test
	public void testChainReplacesTheDefault() throws IOException {
		var config = withChain(
			AnalyzerDef.newBuilder()
				.addFilters(
					TokenFilterDef.newBuilder()
						.setNormalize(TokenFilterDef.Normalize.getDefaultInstance())
				)
		);

		var terms = matchingTerms(config, AnalyzerMode.INDEXING, "The Running Shoes");

		assertThat(terms, contains("the", "running", "shoes"));
	}

	@Test
	public void testNormalizeCanKeepCase() throws IOException {
		var config = withChain(
			AnalyzerDef.newBuilder()
				.addFilters(
					TokenFilterDef.newBuilder()
						.setNormalize(
							TokenFilterDef.Normalize.newBuilder().setCaseFolding(false)
						)
				)
		);

		var terms = matchingTerms(config, AnalyzerMode.INDEXING, "Spring");

		assertThat(terms, contains("Spring"));
	}

	@Test
	public void testCustomStopwordsDropExactlyTheGivenWords() throws IOException {
		var config = withChain(
			AnalyzerDef.newBuilder()
				.addFilters(
					TokenFilterDef.newBuilder()
						.setNormalize(TokenFilterDef.Normalize.getDefaultInstance())
				)
				.addFilters(
					TokenFilterDef.newBuilder()
						.setStopwords(
							TokenFilterDef.Stopwords.newBuilder()
								.setCustom(
									TokenFilterDef.Stopwords.CustomWords.newBuilder()
										.addWords("spring")
								)
						)
				)
		);

		var terms = matchingTerms(config, AnalyzerMode.INDEXING, "the spring shoes");

		assertThat(terms, contains("the", "shoes"));
	}

	@Test
	public void testWhitespaceTokenizerKeepsPunctuation() throws IOException {
		var config = withChain(
			AnalyzerDef.newBuilder()
				.setTokenizer(
					TokenizerDef.newBuilder()
						.setWhitespace(TokenizerDef.WhitespaceTokenizer.getDefaultInstance())
				)
		);

		var terms = matchingTerms(config, AnalyzerMode.INDEXING, "TX-900/B spare");

		assertThat(terms, contains("TX-900/B", "spare"));
	}

	@Test
	public void testMappingCharFilterRunsBeforeTokenization() throws IOException {
		var config = withChain(
			AnalyzerDef.newBuilder()
				.addCharFilters(
					CharFilterDef.newBuilder()
						.setMapping(
							CharFilterDef.Mapping.newBuilder()
								.putMappings("-", "")
						)
				)
				.addFilters(
					TokenFilterDef.newBuilder()
						.setNormalize(TokenFilterDef.Normalize.getDefaultInstance())
				)
		);

		var terms = matchingTerms(config, AnalyzerMode.INDEXING, "TX-900");

		assertThat(terms, contains("tx900"));
	}

	@Test
	public void testAsciiFoldingMakesAccentsMatchTheirPlainForm() throws IOException {
		var config = withChain(
			AnalyzerDef.newBuilder()
				.addFilters(
					TokenFilterDef.newBuilder()
						.setNormalize(TokenFilterDef.Normalize.getDefaultInstance())
				)
				.addFilters(
					TokenFilterDef.newBuilder()
						.setAsciiFolding(TokenFilterDef.AsciiFolding.getDefaultInstance())
				)
		);

		var terms = matchingTerms(config, AnalyzerMode.INDEXING, "café");

		assertThat(terms, contains("cafe"));
	}

	/**
	 * Synonyms widen what a value is indexed as; the querying side leaves the
	 * component out, so what was typed is searched as it is and a synonym is
	 * not counted twice.
	 */
	@Test
	public void testSynonymsWidenTheIndexingSideOnly() throws IOException {
		var resources = ResourcesDef.newBuilder()
			.putSynonyms(
				"cars",
				ResourcesDef.SynonymsResource.newBuilder()
					.addRules(
						ResourcesDef.SynonymsResource.Rule.newBuilder()
							.setEquivalent(
								ResourcesDef.SynonymsResource.Rule.Equivalent.newBuilder()
									.addTerms("car")
									.addTerms("automobile")
							)
					)
					.build()
			)
			.build();

		var config = withChain(
			AnalyzerDef.newBuilder()
				.addFilters(
					TokenFilterDef.newBuilder()
						.setNormalize(TokenFilterDef.Normalize.getDefaultInstance())
				)
				.addFilters(
					TokenFilterDef.newBuilder()
						.setSynonyms(TokenFilterDef.Synonyms.newBuilder().setName("cars"))
				)
		);

		var indexed = terms(
			Analyzers.matching(config, resources, Locales.getDefault(), AnalyzerMode.INDEXING),
			"car"
		);
		assertThat(indexed, containsInAnyOrder("car", "automobile"));

		var queried = terms(
			Analyzers.matching(config, resources, Locales.getDefault(), AnalyzerMode.QUERYING),
			"car"
		);
		assertThat(queried, contains("car"));
	}

	/**
	 * A usage naming a chain analyzes with the chain the name stands for.
	 */
	@Test
	public void testNamedChainIsResolvedThroughTheResources() throws IOException {
		var resources = ResourcesDef.newBuilder()
			.putAnalyzers(
				"shouty",
				AnalyzerDef.newBuilder()
					.addFilters(
						TokenFilterDef.newBuilder()
							.setNormalize(
								TokenFilterDef.Normalize.newBuilder().setCaseFolding(false)
							)
					)
					.build()
			)
			.build();

		var config = StringFieldTypeDef.TextUsageConfig.newBuilder()
			.setAnalyzerRef("shouty")
			.build();

		var terms = terms(
			Analyzers.matching(config, resources, Locales.getDefault(), AnalyzerMode.INDEXING),
			"The Spring"
		);

		assertThat(terms, contains("The", "Spring"));
	}

	/**
	 * An edge n-gram in a custom chain behaves the way the autocomplete
	 * default does - prefixes when indexing, the term cut to the longest
	 * indexed prefix when querying.
	 */
	@Test
	public void testEdgeNgramDerivesTheQueryingSide() throws IOException {
		var chain = AnalyzerDef.newBuilder()
			.addFilters(
				TokenFilterDef.newBuilder()
					.setNormalize(TokenFilterDef.Normalize.getDefaultInstance())
			)
			.addFilters(
				TokenFilterDef.newBuilder()
					.setEdgeNgram(
						TokenFilterDef.EdgeNgram.newBuilder().setMaxGram(4)
					)
			);

		var indexed = matchingTerms(withChain(chain), AnalyzerMode.INDEXING, "search");
		assertThat(indexed, contains("s", "se", "sea", "sear"));

		var queried = matchingTerms(withChain(chain), AnalyzerMode.QUERYING, "search");
		assertThat(queried, contains("sear"));
	}

	/**
	 * A locale with decompounding data but nothing else, so what the splitter
	 * does is visible without stemming rewriting the terms. The data is the
	 * hand-made set under the test resources.
	 */
	private static final LocaleSupport COMPOUNDING = StandardLocaleSupport.of("xx")
		.withDecompounder(Decompounder.forData("test"))
		.build();

	private List<String> compoundingTerms(
		StringFieldTypeDef.TextUsageConfig config,
		AnalyzerMode mode,
		String value
	) throws IOException {
		return terms(
			Analyzers.matching(config, ResourcesDef.getDefaultInstance(), COMPOUNDING, mode),
			value
		);
	}

	/**
	 * The point of the feature: a document saying `regnjakke` has to be found
	 * by a search for `jakke`, so the parts are indexed alongside the whole
	 * word.
	 */
	@Test
	public void testMatchingIndexesThePartsOfCompounds() throws IOException {
		var terms = compoundingTerms(
			StringFieldTypeDef.TextUsageConfig.getDefaultInstance(),
			AnalyzerMode.INDEXING,
			"regnjakke"
		);

		assertThat(terms, containsInAnyOrder("regnjakke", "regn", "jakke"));
	}

	/**
	 * The query side searches what was typed. Splitting the query too would
	 * match documents holding only a part - a search for the compound is more
	 * precise than that.
	 */
	@Test
	public void testMatchingKeepsCompoundQueriesWhole() throws IOException {
		var terms = compoundingTerms(
			StringFieldTypeDef.TextUsageConfig.getDefaultInstance(),
			AnalyzerMode.QUERYING,
			"regnjakke"
		);

		assertThat(terms, contains("regnjakke"));
	}

	/**
	 * A split point the grammar allows is not enough on its own - the part
	 * also has to be a word the list knows, which is what keeps arbitrary
	 * substrings out of the index.
	 */
	@Test
	public void testOnlyKnownPartsAreIndexed() throws IOException {
		var terms = compoundingTerms(
			StringFieldTypeDef.TextUsageConfig.getDefaultInstance(),
			AnalyzerMode.INDEXING,
			"grønjakke"
		);

		assertThat(terms, containsInAnyOrder("grønjakke", "jakke"));
	}

	/**
	 * The word list is folded when it is loaded, the way tokens are folded
	 * before the splitter sees them - the test list spells `Vinter` with a
	 * capital on purpose.
	 */
	@Test
	public void testTheWordListMeetsFoldedTokens() throws IOException {
		var terms = compoundingTerms(
			StringFieldTypeDef.TextUsageConfig.getDefaultInstance(),
			AnalyzerMode.INDEXING,
			"Vinterjakke"
		);

		assertThat(terms, containsInAnyOrder("vinterjakke", "vinter", "jakke"));
	}

	@Test
	public void testShortPartsDownToThreeLettersAreKept() throws IOException {
		var terms = compoundingTerms(
			StringFieldTypeDef.TextUsageConfig.getDefaultInstance(),
			AnalyzerMode.INDEXING,
			"husbil"
		);

		assertThat(terms, containsInAnyOrder("husbil", "hus", "bil"));
	}

	/**
	 * The setting on the usage takes the component out of the engine-built
	 * chain, for the fields where the parts of a name would only mislead.
	 */
	@Test
	public void testDecompoundingCanBeTurnedOffPerUsage() throws IOException {
		var config = StringFieldTypeDef.TextUsageConfig.newBuilder()
			.setDecompound(
				StringFieldTypeDef.TextUsageConfig.DecompoundMode.DECOMPOUND_MODE_NONE
			)
			.build();

		var terms = compoundingTerms(config, AnalyzerMode.INDEXING, "regnjakke");

		assertThat(terms, contains("regnjakke"));
	}

	/**
	 * A locale without decompounding data passes through unsplit - the
	 * component in the engine-built chain does nothing rather than guessing.
	 */
	@Test
	public void testLocalesWithoutDataAreNotSplit() throws IOException {
		var terms = matchingTerms(
			StringFieldTypeDef.TextUsageConfig.getDefaultInstance(),
			AnalyzerMode.INDEXING,
			"regnjakke"
		);

		assertThat(terms.size(), is(1));
	}

	/**
	 * Stopwords go before splitting in the engine-built chain: a function
	 * word can contain smaller words - the Swedish `deras` holds `ras` - and
	 * a word too common to tell documents apart must not sneak parts of
	 * itself into the index either.
	 */
	@Test
	public void testStopwordsAreNotSplitIntoParts() throws IOException {
		var terms = terms(
			Analyzers.matching(
				StringFieldTypeDef.TextUsageConfig.getDefaultInstance(),
				ResourcesDef.getDefaultInstance(),
				Locales.get("sv").orElseThrow(),
				AnalyzerMode.INDEXING
			),
			"deras"
		);

		assertThat(terms, is(List.of()));
	}

	/**
	 * A custom chain asks for splitting the way it asks for anything else,
	 * with a component.
	 */
	@Test
	public void testChainCanAskForDecompounding() throws IOException {
		var config = withChain(
			AnalyzerDef.newBuilder()
				.addFilters(
					TokenFilterDef.newBuilder()
						.setNormalize(TokenFilterDef.Normalize.getDefaultInstance())
				)
				.addFilters(
					TokenFilterDef.newBuilder()
						.setDecompound(TokenFilterDef.Decompound.getDefaultInstance())
				)
		);

		var terms = terms(
			Analyzers.matching(
				config,
				ResourcesDef.getDefaultInstance(),
				COMPOUNDING,
				AnalyzerMode.INDEXING
			),
			"regnjakke"
		);

		assertThat(terms, containsInAnyOrder("regnjakke", "regn", "jakke"));
	}
}
