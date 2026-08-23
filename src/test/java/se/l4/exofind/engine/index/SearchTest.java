package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.AnalyzerDef;
import se.l4.exofind.engine.index.schema.BooleanFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.RankingConfig;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.TokenFilterDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.TextQuery;
import se.l4.exofind.engine.query.matchers.Matchers;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for searching an index - what the clauses of a query find, in which
 * order results come back and what a result carries with it.
 */
public class SearchTest extends AbstractIndexTest {
	@Test
	public void testNoClausesMatchesEverything() throws IOException {
		var index = books();

		var result = index.search(SearchRequest.all());

		assertThat(result.total().count(), is(3L));
		assertThat(result.total().exact(), is(true));
		assertThat(ids(result), containsInAnyOrder("1", "2", "3"));
	}

	@Test
	public void testFilterOnValue() throws IOException {
		var index = books();

		var result = search(index, Query.field("category", Matchers.equalTo("fiction")));

		assertThat(ids(result), contains("2"));
	}

	@Test
	public void testFilterIgnoresCase() throws IOException {
		var index = books();

		var result = search(index, Query.field("category", Matchers.equalTo("Fiction")));

		assertThat(ids(result), contains("2"));
	}

	@Test
	public void testFilterOnAnyOfSeveralValues() throws IOException {
		var index = books();

		var result = search(index, Query.field("category", Matchers.in("fiction", "poetry")));

		assertThat(ids(result), containsInAnyOrder("2", "3"));
	}

	@Test
	public void testFilterOnPrefix() throws IOException {
		var index = books();

		var result = search(index, Query.field("code", Matchers.prefix("EX-")));

		assertThat(ids(result), containsInAnyOrder("1", "2"));
	}

	@Test
	public void testFilterOnRange() throws IOException {
		var index = books();

		var result = search(index, Query.field("code", Matchers.between("AB-000", "EX-150")));

		assertThat(ids(result), containsInAnyOrder("1", "3"));
	}

	@Test
	public void testFilterOnHavingAValue() throws IOException {
		var index = books();

		var result = search(index, Query.field("tags", Matchers.any()));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testFilterOnBoolean() throws IOException {
		var index = books();

		var result = search(index, Query.field("published", Matchers.equalTo(true)));

		assertThat(ids(result), containsInAnyOrder("1", "3"));
	}

	@Test
	public void testClausesNarrowEachOther() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.field("published", Matchers.equalTo(true)),
			Query.field("category", Matchers.equalTo("poetry"))
		);

		assertThat(ids(result), contains("3"));
	}

	@Test
	public void testAnyOfSeveralClauses() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.or(
				Query.field("category", Matchers.equalTo("fiction")),
				Query.field("code", Matchers.prefix("AB-"))
			)
		);

		assertThat(ids(result), containsInAnyOrder("2", "3"));
	}

	@Test
	public void testNoneOfSeveralClauses() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.not(
				Query.field("category", Matchers.equalTo("fiction")),
				Query.field("category", Matchers.equalTo("poetry"))
			)
		);

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testTextMatchesEveryFieldThatCanBeMatched() throws IOException {
		var index = books();

		var result = search(index, Query.text("waters"));

		assertThat(ids(result), contains("3"));
	}

	@Test
	public void testTextMatchesWordStillBeingTyped() throws IOException {
		var index = books();

		var result = search(index, Query.text("silent spr"));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testTextWithoutPrefixNeedsWholeWords() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.text(
				TextMatcher.of("silent spr")
					.withPrefix(TextMatcher.Prefix.OFF)
					/*
					 * Nothing holds `spr` as a whole word, which is exactly what
					 * relaxing would let go of - and this is about what a whole
					 * word matches, not about being rescued from it.
					 */
					.withRelax(TextMatcher.Relax.OFF)
			)
		);

		assertThat(ids(result), is(empty()));
		assertThat(result.total().count(), is(0L));
	}

	@Test
	public void testTextNeedsEveryWordUnlessToldOtherwise() throws IOException {
		var index = books();

		var all = search(index, Query.text("silent tidying"));
		assertThat(ids(all), is(empty()));

		var any = search(
			index,
			Query.text(TextMatcher.of("silent tidying").withMatch(TextMatcher.Match.ANY))
		);
		assertThat(ids(any), containsInAnyOrder("1", "2", "3"));
	}

	@Test
	public void testTextMatchesWordsSpreadOverFields() throws IOException {
		var index = books();

		// `quiet` is in the name and `waters` in the description of the same book
		var result = search(index, Query.text("quiet waters"));

		assertThat(ids(result), contains("3"));
	}

	@Test
	public void testTextSpreadOverFieldsKeepsPrefixOnLastWord() throws IOException {
		var index = books();

		var result = search(index, Query.text("quiet wat"));

		assertThat(ids(result), contains("3"));
	}

	@Test
	public void testTextByFieldNeedsEveryWordInOneField() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.text("quiet waters").withCombine(TextQuery.Combine.FIELD)
		);

		assertThat(ids(result), is(empty()));
		assertThat(result.total().count(), is(0L));
	}

	@Test
	public void testTextInNamedFieldOnly() throws IOException {
		var index = books();

		var result = search(index, Query.text("silent").withField("name"));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testTextRanksByWeightOfTheFieldItMatched() throws IOException {
		var index = ranking();

		var byName = search(
			index,
			Query.text("spring").withField("name", 5f).withField("description", 1f)
		);
		assertThat(ids(byName), contains("named", "described"));

		var byDescription = search(
			index,
			Query.text("spring").withField("name", 1f).withField("description", 5f)
		);
		assertThat(ids(byDescription), contains("described", "named"));
	}

	@Test
	public void testTextUsesTheWeightsOfTheDefinition() throws IOException {
		var index = weighted();

		/*
		 * The name field would win on its own - it is shorter - so the
		 * description winning is the weight of the definition doing it.
		 */
		var byDefinition = search(index, Query.text("spring"));
		assertThat(ids(byDefinition), contains("described", "named"));

		var overridden = search(
			index,
			Query.text("spring").withField("name", 5f).withField("description", 1f)
		);
		assertThat(ids(overridden), contains("named", "described"));
	}

	@Test
	public void testNamingFieldsKeepsTheWeightsOfTheDefinition() throws IOException {
		var index = weighted();

		var result = search(
			index,
			Query.text("spring").withField("name").withField("description")
		);

		assertThat(ids(result), contains("described", "named"));
	}

	@Test
	public void testTextNeedsAFieldThatCanBeMatched() throws IOException {
		var index = create(
			"filters-only",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"category",
					string().setFilter(FilterConfig.getDefaultInstance()).build()
				)
		);

		assertThrows(IndexException.class, () -> {
			search(index, Query.text("anything"));
		});
	}

	@Test
	public void testTextMatchesPrefixesWrittenForAutocomplete() throws IOException {
		var index = create(
			"autocomplete",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(
									StringFieldTypeDef.newBuilder()
										.setAutocomplete(
											StringFieldTypeDef.TextUsageConfig
												.getDefaultInstance()
										)
								)
						)
						.setStored(true)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Spring")
			)
		);
		index.commit();

		var result = search(index, Query.text("spr"));

		assertThat(ids(result), contains("1"));
	}

	/**
	 * A field defined for both matching and autocomplete answers the half
	 * typed last word of a text of several from the prefixes autocomplete
	 * indexed. The words here lean on analysis deliberately: matching stems
	 * {@code running} to {@code run}, so no matching term starts with
	 * {@code runni} - only the autocomplete terms, which hold every prefix of
	 * the word as it was written, can find it.
	 */
	@Test
	public void testTextCompletesLastWordThroughAutocompleteBesideMatching() throws IOException {
		var index = matchedAndCompleted();

		var result = search(
			index,
			Query.text(TextMatcher.of("shoes runni").withRelax(TextMatcher.Relax.OFF))
		);

		assertThat(ids(result), contains("1"));
	}

	/**
	 * A half typed word on its own is answered by the matching usage alone,
	 * even when the field also autocompletes: with no other word narrowing the
	 * search, the terms autocomplete indexed offer nothing over the matching
	 * ones and cost a longer walk. The same stemming as above is what shows
	 * which usage answered - {@code runni} starts no stemmed matching term, so
	 * matching alone finds nothing.
	 */
	@Test
	public void testTextOfOneHalfTypedWordIsAnsweredByMatchingAlone() throws IOException {
		var index = matchedAndCompleted();

		var result = search(
			index,
			Query.text(TextMatcher.of("runni").withRelax(TextMatcher.Relax.OFF))
		);

		assertThat(ids(result), is(empty()));
	}

	@Test
	public void testTextWithAutocompleteBesideMatchingNeedsEveryWord() throws IOException {
		var index = matchedAndCompleted();

		var found = search(index, Query.text("running sho"));
		assertThat(ids(found), contains("1"));

		var mixed = search(
			index,
			Query.text(TextMatcher.of("sandal sho").withRelax(TextMatcher.Relax.OFF))
		);
		assertThat(ids(mixed), is(empty()));
	}

	/**
	 * When the matching and autocomplete chains disagree on how many words the
	 * text holds - here the matching chain drops the stopword {@code the} -
	 * which of their words is which cannot be told, and the half typed word is
	 * matched among the matching terms as it is on a field without
	 * autocomplete.
	 */
	@Test
	public void testTextEndingInAStopwordStillMatches() throws IOException {
		var index = matchedAndCompleted();

		var result = search(index, Query.text("running the"));

		assertThat(ids(result), contains("1"));
	}

	/**
	 * An index whose name is defined for both matching and autocomplete, the
	 * shape a search box searching and completing over one field uses.
	 */
	private Index matchedAndCompleted() throws IOException {
		var index = create(
			"matched-and-completed",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
							.setAutocomplete(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Running Shoes")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Sandals")
			)
		);
		index.commit();

		return index;
	}

	@Test
	public void testPhraseNeedsWordsInOrder() throws IOException {
		var index = books();

		var inOrder = search(index, Query.text(phrase("silent spring")));
		assertThat(ids(inOrder), contains("1"));

		// The same words the other way around - `all` would still find them
		var reversed = search(index, Query.text(phrase("spring silent")));
		assertThat(ids(reversed), is(empty()));
	}

	@Test
	public void testPhraseOfOneWordIsThatWord() throws IOException {
		var index = books();

		var result = search(index, Query.text(phrase("spring")));

		assertThat(ids(result), containsInAnyOrder("1", "2"));
	}

	@Test
	public void testPhraseMatchesWordStillBeingTyped() throws IOException {
		var index = books();

		var result = search(index, Query.text(phrase("silent spr")));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testPhraseWithoutPrefixNeedsWholeWords() throws IOException {
		var index = books();

		var result = search(
			index,
			Query.text(phrase("silent spr").withPrefix(TextMatcher.Prefix.OFF))
		);

		assertThat(ids(result), is(empty()));
	}

	@Test
	public void testPhraseDoesNotMatchWordsSpreadOverFields() throws IOException {
		var index = books();

		// `quiet` is in the name and `waters` in the description of the same book
		var result = search(index, Query.text(phrase("quiet waters")));

		assertThat(ids(result), is(empty()));
	}

	@Test
	public void testPhraseInNamedFieldOnly() throws IOException {
		var index = books();

		var result = search(index, Query.text(phrase("quiet sea")).withField("name"));

		assertThat(ids(result), contains("3"));
	}

	/**
	 * The default chain drops stopwords on both sides, leaving the same hole
	 * in the phrase that indexing left in the value - so a phrase written with
	 * its stopwords finds the value, and one written without them does not
	 * pretend the hole is not there.
	 */
	@Test
	public void testPhraseKeepsTheHolesOfDroppedStopwords() throws IOException {
		var index = books();

		// The description holds `the silent spring of 1962`
		var withStopword = search(index, Query.text(phrase("spring of 1962")));
		assertThat(ids(withStopword), contains("1"));

		var wholeWords = search(
			index,
			Query.text(phrase("spring of 1962").withPrefix(TextMatcher.Prefix.OFF))
		);
		assertThat(ids(wholeWords), contains("1"));

		var withoutStopword = search(index, Query.text(phrase("spring 1962")));
		assertThat(ids(withoutStopword), is(empty()));
	}

	/**
	 * A phrase takes every word as typed however the field is defined - fuzzy
	 * positions are expensive, and a phrase is asked for when the words are
	 * known exactly.
	 */
	@Test
	public void testPhraseIgnoresTypoTolerance() throws IOException {
		var index = typos(
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);

		assertThat(ids(search(index, Query.text(phrase("spring cleaning")))), contains("1"));
		assertThat(ids(search(index, Query.text(phrase("sprnig cleaning")))), is(empty()));
	}

	/**
	 * An autocomplete field stacks every prefix of a word at one position, so
	 * order means nothing in it. A search naming no fields skips such fields;
	 * naming one outright is refused.
	 */
	@Test
	public void testPhraseSkipsFieldsWrittenOnlyForAutocomplete() throws IOException {
		var index = create(
			"phrase-and-autocomplete",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).build()
				)
				.putFields(
					"completion",
					string(
						StringFieldTypeDef.newBuilder()
							.setAutocomplete(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Silent Spring"),
				new Document.Value("completion", "Silent Spring")
			)
		);
		index.commit();

		var result = search(index, Query.text(phrase("silent spring")));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testPhraseRefusesAFieldWrittenOnlyForAutocomplete() throws IOException {
		var index = create(
			"phrase-autocomplete-only",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setAutocomplete(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).build()
				)
		);

		assertThrows(IndexFieldUsageException.class, () -> {
			search(index, Query.text(phrase("silent spring")).withField("name"));
		});
	}

	@Test
	public void testPhraseSlopLetsOtherWordsSitBetween() throws IOException {
		var index = spaced();

		var adjacent = search(index, Query.text(phrase("silent spring")));
		assertThat(ids(adjacent), contains("near"));

		var apart = search(index, Query.text(phrase("silent spring").withSlop(1)));
		assertThat(ids(apart), containsInAnyOrder("near", "far"));
	}

	/**
	 * Slop says how far the words may be moved apart, never that they may come
	 * in another order - a phrase is asked for by somebody who knows how it
	 * reads.
	 */
	@Test
	public void testPhraseSlopKeepsTheWordsInTheOrderTyped() throws IOException {
		var index = spaced();

		var result = search(index, Query.text(phrase("spring silent").withSlop(5)));

		assertThat(ids(result), is(empty()));
	}

	@Test
	public void testPhraseSlopRanksTheClosestMatchFirst() throws IOException {
		var index = spaced();

		var result = search(index, Query.text(phrase("silent spring").withSlop(1)));

		assertThat(ids(result), contains("near", "far"));
	}

	@Test
	public void testPhraseSlopWithWordStillBeingTyped() throws IOException {
		var index = spaced();

		var adjacent = search(index, Query.text(phrase("silent spr")));
		assertThat(ids(adjacent), contains("near"));

		var apart = search(index, Query.text(phrase("silent spr").withSlop(1)));
		assertThat(ids(apart), containsInAnyOrder("near", "far"));
	}

	@Test
	public void testUserTextNeedsEveryLooseWord() throws IOException {
		var index = books();

		var result = search(index, Query.text(user("silent spring")));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testUserTextQuotesAskForAPhrase() throws IOException {
		var index = books();

		var inOrder = search(index, Query.text(user("\"silent spring\"")));
		assertThat(ids(inOrder), contains("1"));

		// The same two words are found by the loose reading, but not in this order
		var reversed = search(index, Query.text(user("\"spring silent\"")));
		assertThat(ids(reversed), is(empty()));
	}

	@Test
	public void testUserTextMinusLeavesSomethingOut() throws IOException {
		var index = books();

		// `Spring Cleaning` is the other book a search for spring finds
		var result = search(index, Query.text(user("spring -cleaning")));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testUserTextMinusLeavesOutAQuotedPhrase() throws IOException {
		var index = books();

		var result = search(index, Query.text(user("spring -\"spring cleaning\"")));

		assertThat(ids(result), contains("1"));
	}

	/**
	 * Leaving something out brings nothing in, so a text of nothing else still
	 * runs against everything the index holds.
	 */
	@Test
	public void testUserTextOfOnlyExclusionsRunsAgainstTheWholeIndex() throws IOException {
		var index = books();

		var result = search(index, Query.text(user("-spring")));

		assertThat(ids(result), contains("3"));
	}

	@Test
	public void testUserTextLetsTheLastWordBeHalfTyped() throws IOException {
		var index = books();

		var result = search(index, Query.text(user("silent spr")));

		assertThat(ids(result), contains("1"));
	}

	/**
	 * Somebody halfway through typing a phrase has not made a mistake yet, so
	 * the quote runs to the end of the text and its last word is the one still
	 * being typed. Closing the quote says the words are finished.
	 */
	@Test
	public void testUserTextQuoteNobodyClosedIsStillBeingTyped() throws IOException {
		var index = books();

		var open = search(index, Query.text(user("\"silent spr")));
		assertThat(ids(open), contains("1"));

		var closed = search(index, Query.text(user("\"silent spr\"")));
		assertThat(ids(closed), is(empty()));
	}

	/**
	 * An exclusion throws documents away, and what is thrown away is the one
	 * thing a search cannot show - so it is taken exactly as typed rather than
	 * as a word somebody is still writing.
	 */
	@Test
	public void testUserTextExclusionIsNeverHalfTyped() throws IOException {
		var index = books();

		// `clea` starts the `cleaning` of the second book without being it
		var result = search(index, Query.text(user("spring -clea")));

		assertThat(ids(result), containsInAnyOrder("1", "2"));
	}

	@Test
	public void testUserTextPunctuationThatMeansNothingIsText() throws IOException {
		var index = books();

		// The lone minus excludes nothing, leaving the two words to be searched
		var result = search(index, Query.text(user("spring - cleaning")));

		assertThat(ids(result), contains("2"));
	}

	@Test
	public void testUserTextWithNothingToSearchForMatchesNothing() throws IOException {
		var index = books();

		assertThat(ids(search(index, Query.text(user("")))), is(empty()));
		assertThat(ids(search(index, Query.text(user("   ")))), is(empty()));
	}

	@Test
	public void testUserTextSlopReachesTheQuotedParts() throws IOException {
		var index = spaced();

		var adjacent = search(index, Query.text(user("\"silent spring\"")));
		assertThat(ids(adjacent), contains("near"));

		var apart = search(index, Query.text(user("\"silent spring\"").withSlop(1)));
		assertThat(ids(apart), containsInAnyOrder("near", "far"));
	}

	/**
	 * The quotes were typed by somebody who cannot be expected to know which
	 * fields a search covers, so a field that holds no order to ask for
	 * answers them as the loose words inside - where the same quotes written
	 * as a `phrase` clause are refused.
	 */
	@Test
	public void testUserTextQuotesFallBackInAFieldWrittenOnlyForAutocomplete()
		throws IOException {
		var index = create(
			"user-autocomplete-only",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setAutocomplete(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Silent Spring")
			)
		);
		index.commit();

		var named = search(index, Query.text(user("\"silent spring\"")).withField("name"));
		assertThat(ids(named), contains("1"));

		// The same when the search left the fields to the index
		var everywhere = search(index, Query.text(user("\"silent spring\"")));
		assertThat(ids(everywhere), contains("1"));
	}

	@Test
	public void testUserTextInAFieldClause() throws IOException {
		var index = books();

		var result = search(index, Query.field("name", user("spring -cleaning")));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testTypoToleranceMatchesMisspelledWord() throws IOException {
		var index = typos(
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);

		var result = search(index, Query.text("sprnig"));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testTypoToleranceAppliesToWordsThatAreNotLast() throws IOException {
		var index = typos(
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);

		var result = search(index, Query.text("sprnig clean"));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testTypoToleranceCanBeTurnedOffPerQuery() throws IOException {
		var index = typos(
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);

		var result = search(
			index,
			Query.text(TextMatcher.of("sprnig").withTypos(TextMatcher.Typos.OFF))
		);

		assertThat(ids(result), is(empty()));
	}

	@Test
	public void testTyposNeedDeclaringOnTheField() throws IOException {
		var index = books();

		var result = search(index, Query.text("sprnig"));

		assertThat(ids(result), is(empty()));
	}

	@Test
	public void testShortWordsGetNoTypos() throws IOException {
		var index = typos(
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);

		var result = search(index, Query.text("cta"));

		assertThat(ids(result), is(empty()));
	}

	@Test
	public void testNumbersGetNoTypos() throws IOException {
		var index = numbered(
			"typos-numbers",
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);

		// A number one digit off is a different number, not a misspelling
		assertThat(ids(search(index, Query.text("12346"))), is(empty()));

		// The number itself, and the misspelled word beside it, still match
		assertThat(ids(search(index, Query.text("12345"))), contains("1"));
		assertThat(ids(search(index, Query.text("reprot"))), contains("1"));
	}

	@Test
	public void testNumbersCanBeFuzzedWhenAskedFor() throws IOException {
		var index = numbered(
			"typos-numbers-fuzzed",
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.newBuilder()
				.setNumbers(
					StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.NumbersConfig
						.getDefaultInstance()
				)
				.build()
		);

		var result = search(index, Query.text("12346"));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testWordsMixingDigitsAndLettersKeepTheirTypos() throws IOException {
		var index = numbered(
			"typos-numbers-mixed",
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);

		// The last two letters of `spring2024` swapped - digits inside a word
		// do not make it a number
		var result = search(index, Query.text("sprign2024"));

		assertThat(ids(result), contains("2"));
	}

	@Test
	public void testNumbersGetNoTyposWhileCompleting() throws IOException {
		var index = create(
			"completing-numbers",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setAutocomplete(
								StringFieldTypeDef.TextUsageConfig.newBuilder()
									.setTypoTolerance(
										StringFieldTypeDef.TextUsageConfig
											.TypoToleranceConfig.getDefaultInstance()
									)
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Report 12345")
			)
		);
		index.commit();

		// A digit off on the way to `12345` is another number, not a typo
		assertThat(ids(search(index, Query.text("12245"))), is(empty()));

		// The number typed as it stands still completes
		assertThat(ids(search(index, Query.text("1234"))), contains("1"));
	}

	@Test
	public void testTypoThresholdsComeFromTheDefinition() throws IOException {
		// Two mistakes in a six letter word, more than the defaults allow
		var strict = typos(
			"typos-strict",
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);
		assertThat(ids(search(strict, Query.text("spnirg"))), is(empty()));

		var lenient = typos(
			"typos-lenient",
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.newBuilder()
				.setMinLengthOneTypo(4)
				.setMinLengthTwoTypos(6)
				.build()
		);
		assertThat(ids(search(lenient, Query.text("spnirg"))), contains("1"));
	}

	@Test
	public void testTypoPrefixMustMatchExactly() throws IOException {
		// A mistake in the second letter, which the prefix of two protects
		var index = typos(
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.newBuilder()
				.setPrefixLength(2)
				.build()
		);

		var result = search(index, Query.text("srring"));

		assertThat(ids(result), is(empty()));
	}

	@Test
	public void testTypoToleranceAppliesToTheWordBeingTyped() throws IOException {
		var index = typos(
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);

		// Halfway through `cleaning`, with the last two letters swapped
		var result = search(index, Query.text("claen"));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testTypoThresholdsApplyToTheWordBeingTyped() throws IOException {
		// Two mistakes on the way to `cleaning`, more than the defaults allow
		var strict = typos(
			"typos-typing-strict",
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);
		assertThat(ids(search(strict, Query.text("claeni"))), is(empty()));

		var lenient = typos(
			"typos-typing-lenient",
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.newBuilder()
				.setMinLengthOneTypo(4)
				.setMinLengthTwoTypos(6)
				.build()
		);
		assertThat(ids(search(lenient, Query.text("claeni"))), contains("1"));
	}

	@Test
	public void testTypoPrefixProtectsTheWordBeingTyped() throws IOException {
		// A mistake in the second letter, which the prefix of two protects
		var index = typos(
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.newBuilder()
				.setPrefixLength(2)
				.build()
		);

		var result = search(index, Query.text("celani"));

		assertThat(ids(result), is(empty()));
	}

	@Test
	public void testWordTypedExactlyRanksAboveItsTypos() throws IOException {
		var index = create(
			"typos-ranking",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.newBuilder()
									.setTypoTolerance(
										StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig
											.getDefaultInstance()
									)
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "exact"),
				new Document.Value("name", "Claen Room")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "typo"),
				new Document.Value("name", "Cleaning Service")
			)
		);
		index.commit();

		var result = search(index, Query.text("claen"));

		assertThat(ids(result), contains("exact", "typo"));
	}

	@Test
	public void testOneMistakeRanksAboveTwo() throws IOException {
		var index = mistakes("typos-band-ranking");

		// One letter from `wonderfull`, and two
		index.addDocument(
			new Document(
				new Document.Value("id", "one"),
				new Document.Value("name", "Wonderful")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "two"),
				new Document.Value("name", "Wonderfus")
			)
		);
		index.commit();

		var result = search(
			index,
			Query.text(TextMatcher.of("wonderfull").withPrefix(TextMatcher.Prefix.OFF))
		);

		assertThat(ids(result), contains("one", "two"));
	}

	/**
	 * A document is as close to the word as the closest reading it holds:
	 * also holding a worse reading must not add to its score, or a document
	 * full of misspellings would climb past one that got closer.
	 */
	@Test
	public void testWorseReadingAddsNothingToACloserOne() throws IOException {
		var index = mistakes("typos-best-reading");

		index.addDocument(
			new Document(
				new Document.Value("id", "close"),
				new Document.Value("name", "Wonderful")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "both"),
				new Document.Value("name", "Wonderful Wonderfus")
			)
		);
		index.commit();

		var result = search(
			index,
			Query.text(TextMatcher.of("wonderfull").withPrefix(TextMatcher.Prefix.OFF))
		);

		assertThat(ids(result), containsInAnyOrder("close", "both"));
		assertThat(
			result.hits().get(0).score(),
			is(result.hits().get(1).score())
		);
	}

	/**
	 * An empty index whose name forgives typos over a chain that only
	 * normalizes, so the words of a test stand in the index as typed and the
	 * mistakes counted between them can be read off the letters.
	 */
	private Index mistakes(String name) throws IOException {
		return create(
			name,
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.newBuilder()
									.setAnalyzer(
										AnalyzerDef.newBuilder()
											.addFilters(
												TokenFilterDef.newBuilder()
													.setNormalize(
														TokenFilterDef.Normalize.getDefaultInstance()
													)
											)
									)
									.setTypoTolerance(
										StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig
											.getDefaultInstance()
									)
							)
					).build()
				)
		);
	}

	@Test
	public void testCompletingMatchesAMisspelledWord() throws IOException {
		var index = completing(
			"completing-typos",
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);

		var result = search(index, Query.text("sprnig"));

		assertThat(ids(result), contains("1"));
	}

	/**
	 * The point of forgiving typos where text is completed: the mistake sits
	 * in the part typed so far, which the prefixes the field wrote are what
	 * make findable.
	 */
	@Test
	public void testCompletingMatchesAMisspelledWordStillBeingTyped() throws IOException {
		var index = completing(
			"completing-typing",
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);

		// Halfway through `cleaning`, with the last two letters swapped
		var result = search(index, Query.text("claen"));

		assertThat(ids(result), contains("1"));
	}

	@Test
	public void testCompletingNeedsTyposDeclaringOnTheField() throws IOException {
		var index = create(
			"completing-plain",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setAutocomplete(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Spring Cleaning")
			)
		);
		index.commit();

		assertThat(ids(search(index, Query.text("sprnig"))), is(empty()));
	}

	@Test
	public void testCompletingShortWordsGetNoTypos() throws IOException {
		var index = completing(
			"completing-short",
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);

		var result = search(index, Query.text("cta"));

		assertThat(ids(result), is(empty()));
	}

	@Test
	public void testCompletingTyposCanBeTurnedOffPerQuery() throws IOException {
		var index = completing(
			"completing-off",
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);

		var result = search(
			index,
			Query.text(TextMatcher.of("claen").withTypos(TextMatcher.Typos.OFF))
		);

		assertThat(ids(result), is(empty()));
	}

	/**
	 * A word being typed is forgiven one mistake unless the definition names a
	 * length for two - the prefixes make the second one cost several times what
	 * the first does, on every long word rather than only the misspelled ones.
	 */
	@Test
	public void testCompletingForgivesASecondTypoOnlyWhenAsked() throws IOException {
		// Two mistakes on the way to `photography`, in a word long enough for them
		var ceiling = completing(
			"completing-one",
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
		);
		assertThat(ids(search(ceiling, Query.text("photografy"))), is(empty()));

		var asked = completing(
			"completing-two",
			StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.newBuilder()
				.setMinLengthTwoTypos(9)
				.build()
		);
		assertThat(ids(search(asked, Query.text("photografy"))), contains("3"));
	}

	@Test
	public void testBoostLiftsWithoutNarrowing() throws IOException {
		var index = ranking();

		var plain = search(index, Query.text("spring"));
		assertThat(ids(plain), containsInAnyOrder("named", "described"));

		var boosted = index.search(
			SearchRequest.create()
				.withQuery(
					Query.text("spring"),
					Query.boost(5f, Query.field("category", Matchers.equalTo("staff-picks")))
				)
				.build()
		);

		assertThat(ids(boosted), contains("described", "named"));
	}

	@Test
	public void testTieBreakerOrdersEqualMatches() throws IOException {
		var index = tied(RankingConfig.TieBreaker.Direction.DIRECTION_DESCENDING);

		var result = search(index, Query.text("spring"));

		assertThat(ids(result), contains("c", "b", "a"));
	}

	@Test
	public void testTieBreakerDirectionComesFromTheDefinition() throws IOException {
		var index = tied(RankingConfig.TieBreaker.Direction.DIRECTION_ASCENDING);

		var result = search(index, Query.text("spring"));

		assertThat(ids(result), contains("a", "b", "c"));
	}

	@Test
	public void testTieBreakersAppendAfterAnExplicitSort() throws IOException {
		var index = tied(RankingConfig.TieBreaker.Direction.DIRECTION_DESCENDING);

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("category"))
				.build()
		);

		// Categories ascending, and the tie within `one` broken by code descending
		assertThat(ids(result), contains("b", "a", "c"));
	}

	@Test
	public void testExplicitSortOnTheTieBreakerFieldWins() throws IOException {
		var index = tied(RankingConfig.TieBreaker.Direction.DIRECTION_DESCENDING);

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("code"))
				.build()
		);

		assertThat(ids(result), contains("a", "b", "c"));
	}

	@Test
	public void testListingIsDeterministicWithTieBreakers() throws IOException {
		var index = tied(RankingConfig.TieBreaker.Direction.DIRECTION_DESCENDING);

		var result = index.search(SearchRequest.all());

		assertThat(ids(result), contains("c", "b", "a"));
	}

	@Test
	public void testOrderByField() throws IOException {
		var index = books();

		var ascending = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.build()
		);
		assertThat(ids(ascending), contains("1", "2", "3"));

		var descending = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name", SortBy.Order.DESCENDING))
				.build()
		);
		assertThat(ids(descending), contains("3", "2", "1"));
	}

	@Test
	public void testOrderByScore() throws IOException {
		var index = ranking();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("spring").withField("name", 5f).withField("description"))
				.withSort(SortBy.score())
				.build()
		);

		assertThat(ids(result), contains("named", "described"));
	}

	@Test
	public void testLimitAndOffset() throws IOException {
		var index = books();

		var first = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.withLimit(2)
				.build()
		);
		assertThat(ids(first), contains("1", "2"));
		assertThat(first.total().count(), is(3L));

		var second = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.withLimit(2)
				.withOffset(2)
				.build()
		);
		assertThat(ids(second), contains("3"));
		assertThat(second.total().count(), is(3L));
	}

	@Test
	public void testContinuingAfterAHit() throws IOException {
		var index = books();

		var first = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.withLimit(1)
				.build()
		);
		assertThat(ids(first), contains("1"));

		var rest = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.withAfter(first.hits().get(0).key())
				.withLimit(2)
				.build()
		);

		assertThat(ids(rest), contains("2", "3"));
	}

	@Test
	public void testWalkingBackwardsReadsInTheRequestedOrder() throws IOException {
		var index = books();

		var all = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.build()
		);
		assertThat(ids(all), contains("1", "2", "3"));

		var before = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.withBefore(all.hits().get(2).key())
				.withLimit(2)
				.build()
		);

		assertThat(ids(before), contains("1", "2"));
	}

	@Test
	public void testBackwardsWindowEndsJustBeforeThePosition() throws IOException {
		var index = books();

		var all = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.build()
		);

		var before = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.withBefore(all.hits().get(2).key())
				.withLimit(1)
				.build()
		);

		assertThat(ids(before), contains("2"));
	}

	@Test
	public void testContinuingCarriesTheTieBreakers() throws IOException {
		var index = tied(RankingConfig.TieBreaker.Direction.DIRECTION_DESCENDING);

		// Categories ascending, the tie within `one` broken by code descending
		var first = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("category"))
				.withLimit(1)
				.build()
		);
		assertThat(ids(first), contains("b"));

		var rest = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("category"))
				.withAfter(first.hits().get(0).key())
				.build()
		);
		assertThat(ids(rest), contains("a", "c"));

		var back = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("category"))
				.withBefore(rest.hits().get(0).key())
				.build()
		);
		assertThat(ids(back), contains("b"));
	}

	@Test
	public void testContinuingByRelevance() throws IOException {
		var index = ranking();

		var query = Query.text("spring").withField("name", 5f).withField("description");

		var first = index.search(
			SearchRequest.create()
				.withQuery(query)
				.withLimit(1)
				.build()
		);
		assertThat(ids(first), contains("named"));

		var rest = index.search(
			SearchRequest.create()
				.withQuery(query)
				.withAfter(first.hits().get(0).key())
				.build()
		);
		assertThat(ids(rest), contains("described"));

		var back = index.search(
			SearchRequest.create()
				.withQuery(query)
				.withBefore(rest.hits().get(0).key())
				.build()
		);
		assertThat(ids(back), contains("named"));
	}

	@Test
	public void testWalkingBackwardsMirrorsDocumentsWithoutAValue() throws IOException {
		/*
		 * A document without a value sits at one end of the order, and going
		 * backwards has to keep it at that end - the mirror flips the
		 * comparison, not where missing sits as seen by the caller.
		 */
		var index = create(
			"keyset-missing",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string().setSort(SortConfig.getDefaultInstance()).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "unnamed")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "a"),
				new Document.Value("name", "A")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "b"),
				new Document.Value("name", "B")
			)
		);
		index.commit();

		var all = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.build()
		);
		assertThat(ids(all), contains("a", "b", "unnamed"));

		var beforeMissing = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.withBefore(all.hits().get(2).key())
				.build()
		);
		assertThat(ids(beforeMissing), contains("a", "b"));

		var beforeLast = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.withBefore(all.hits().get(1).key())
				.build()
		);
		assertThat(ids(beforeLast), contains("a"));
	}

	@Test
	public void testKeyThatDoesNotFitTheSortIsRefused() throws IOException {
		var index = books();

		var byName = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("name"))
				.withLimit(1)
				.build()
		);

		// The key was taken under a field sort, the search orders by score
		assertThrows(IndexInvalidCursorException.class, () -> {
			index.search(
				SearchRequest.create()
					.withAfter(byName.hits().get(0).key())
					.build()
			);
		});
	}

	@Test
	public void testCountWithoutResults() throws IOException {
		var index = books();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("published", Matchers.equalTo(true)))
				.withLimit(0)
				.build()
		);

		assertThat(ids(result), is(empty()));
		assertThat(result.total().count(), is(2L));
		assertThat(result.total().exact(), is(true));
	}

	@Test
	public void testResultsCarryTheStoredDocument() throws IOException {
		var index = books();

		var result = search(index, Query.field("category", Matchers.equalTo("fiction")));
		var hit = result.hits().get(0);

		assertThat(hit.id(), is("2"));
		assertThat(hit.document().get("name"), is("Spring Cleaning"));
		assertThat(hit.document().get("published"), is(false));
	}

	@Test
	public void testOnlyTheFieldsAskedForAreReturned() throws IOException {
		var index = books();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("category", Matchers.equalTo("fiction")))
				.withFields("name")
				.build()
		);

		var hit = result.hits().get(0);
		assertThat(hit.document().get("name"), is(notNullValue()));
		assertThat(hit.document().get("category"), is(nullValue()));

		// The primary key comes along, as it is what identifies the result
		assertThat(hit.id(), is("2"));
	}

	@Test
	public void testUnknownFieldIsRefused() throws IOException {
		var index = books();

		assertThrows(IndexFieldNotFoundException.class, () -> {
			search(index, Query.field("nope", Matchers.equalTo("value")));
		});
	}

	@Test
	public void testFilteringFieldThatIsNotFilterableIsRefused() throws IOException {
		var index = books();

		assertThrows(IndexFieldUsageException.class, () -> {
			search(index, Query.field("description", Matchers.equalTo("value")));
		});
	}

	@Test
	public void testOrderingByFieldThatIsNotSortableIsRefused() throws IOException {
		var index = books();

		assertThrows(IndexFieldUsageException.class, () -> {
			index.search(
				SearchRequest.create()
					.withSort(SortBy.field("category"))
					.build()
			);
		});
	}

	@Test
	public void testMatcherTheTypeHasNoMeaningForIsRefused() throws IOException {
		var index = books();

		assertThrows(IndexInvalidQueryTypeException.class, () -> {
			search(index, Query.field("published", Matchers.text("true")));
		});
	}

	@Test
	public void testValueOfTheWrongKindIsRefused() throws IOException {
		var index = books();

		assertThrows(IndexInvalidQueryValueException.class, () -> {
			search(index, Query.field("category", Matchers.equalTo(42)));
		});
	}

	/**
	 * An index of three books, holding a field for every way of searching that
	 * the engine has.
	 *
	 * @return
	 * @throws IOException
	 */
	private Index books() throws IOException {
		var index = create(
			"books",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					)
						.setStored(true)
						.setSort(SortConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"description",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).build()
				)
				.putFields(
					"category",
					string()
						.setStored(true)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"code",
					string().setFilter(FilterConfig.getDefaultInstance()).build()
				)
				.putFields(
					"tags",
					string()
						.setMultiple(true)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"published",
					bool()
						.setStored(true)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Silent Spring"),
				new Document.Value("description", "A book about the silent spring of 1962"),
				new Document.Value("category", "non-fiction"),
				new Document.Value("code", "EX-100"),
				new Document.Value("tags", "nature"),
				new Document.Value("published", true)
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Spring Cleaning"),
				new Document.Value("description", "Tidying up"),
				new Document.Value("category", "fiction"),
				new Document.Value("code", "EX-200"),
				new Document.Value("published", false)
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "The Quiet Sea"),
				new Document.Value("description", "Silent waters run deep"),
				new Document.Value("category", "poetry"),
				new Document.Value("code", "AB-300"),
				new Document.Value("published", true)
			)
		);

		index.commit();
		return index;
	}

	/**
	 * An index of two documents that hold the same word, one in its name and
	 * the other in its description, for telling apart what ranking did from
	 * what matching did.
	 *
	 * @return
	 * @throws IOException
	 */
	private Index ranking() throws IOException {
		var index = create(
			"ranking",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).setStored(true).build()
				)
				.putFields(
					"description",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).build()
				)
				.putFields(
					"category",
					string().setFilter(FilterConfig.getDefaultInstance()).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "named"),
				new Document.Value("name", "Spring"),
				new Document.Value("description", "Nothing of interest"),
				new Document.Value("category", "everyday")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "described"),
				new Document.Value("name", "Ocean"),
				new Document.Value("description", "A story about spring"),
				new Document.Value("category", "staff-picks")
			)
		);

		index.commit();
		return index;
	}

	/**
	 * The two documents of {@link #ranking()}, with the definition declaring
	 * that a description hit outweighs a name hit.
	 *
	 * @return
	 * @throws IOException
	 */
	private Index weighted() throws IOException {
		var index = create(
			"weighted",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).build()
				)
				.putFields(
					"description",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.newBuilder()
									.setWeight(5f)
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "named"),
				new Document.Value("name", "Spring"),
				new Document.Value("description", "Nothing of interest")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "described"),
				new Document.Value("name", "Ocean"),
				new Document.Value("description", "A story about spring")
			)
		);

		index.commit();
		return index;
	}

	/**
	 * An index of a name that can be misspelled and one too short for that,
	 * with the given typo tolerance.
	 *
	 * @param config
	 * @return
	 * @throws IOException
	 */
	private Index typos(
		StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig config
	) throws IOException {
		return typos("typos", config);
	}

	private Index typos(
		String name,
		StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig config
	) throws IOException {
		var index = create(
			name,
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.newBuilder()
									.setTypoTolerance(config)
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Spring Cleaning")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Cat")
			)
		);

		index.commit();
		return index;
	}

	/**
	 * An index holding a name with a number long enough that the length rules
	 * alone would fuzz it, and one where the digits sit inside a word, with
	 * the given typo tolerance.
	 *
	 * @param name
	 * @param config
	 * @return
	 * @throws IOException
	 */
	private Index numbered(
		String name,
		StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig config
	) throws IOException {
		var index = create(
			name,
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.newBuilder()
									.setTypoTolerance(config)
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Report 12345")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "spring2024")
			)
		);

		index.commit();
		return index;
	}

	/**
	 * An index that completes what is typed with the given typo tolerance,
	 * holding a name that can be misspelled, one too short to be, and one long
	 * enough to carry two mistakes.
	 *
	 * @param name
	 * @param config
	 * @return
	 * @throws IOException
	 */
	private Index completing(
		String name,
		StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig config
	) throws IOException {
		var index = create(
			name,
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setAutocomplete(
								StringFieldTypeDef.TextUsageConfig.newBuilder()
									.setTypoTolerance(config)
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Spring Cleaning")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Cat")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "Nature Photography")
			)
		);

		index.commit();
		return index;
	}

	/**
	 * An index of three documents that match a search for {@code spring}
	 * exactly as well as each other, with ties broken by their code in the
	 * given direction.
	 *
	 * @param direction
	 * @return
	 * @throws IOException
	 */
	private Index tied(RankingConfig.TieBreaker.Direction direction) throws IOException {
		var index = create(
			"tied",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).build()
				)
				.putFields(
					"category",
					string()
						.setFilter(FilterConfig.getDefaultInstance())
						.setSort(SortConfig.getDefaultInstance())
						.build()
				)
				.putFields("code", string().setSort(SortConfig.getDefaultInstance()).build())
				.setRanking(
					RankingConfig.newBuilder()
						.addTieBreakers(
							RankingConfig.TieBreaker.newBuilder()
								.setField("code")
								.setDirection(direction)
						)
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "a"),
				new Document.Value("name", "Spring"),
				new Document.Value("category", "one"),
				new Document.Value("code", "A")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "b"),
				new Document.Value("name", "Spring"),
				new Document.Value("category", "one"),
				new Document.Value("code", "B")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "c"),
				new Document.Value("name", "Spring"),
				new Document.Value("category", "two"),
				new Document.Value("code", "C")
			)
		);

		index.commit();
		return index;
	}

	/**
	 * An index of two documents whose names hold the same two words with a
	 * different number of words between them.
	 *
	 * @return
	 * @throws IOException
	 */
	private Index spaced() throws IOException {
		var index = create(
			"spaced",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "near"),
				new Document.Value("name", "Silent Spring")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "far"),
				new Document.Value("name", "Silent Green Spring")
			)
		);

		index.commit();
		return index;
	}

	private static TextMatcher phrase(String text) {
		return TextMatcher.of(text).withMatch(TextMatcher.Match.PHRASE);
	}

	private static TextMatcher user(String text) {
		return TextMatcher.of(text).withMatch(TextMatcher.Match.USER);
	}

	private static FieldDef.Builder string() {
		return string(StringFieldTypeDef.newBuilder());
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(type));
	}

	private static FieldDef.Builder bool() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setBoolean(BooleanFieldTypeDef.getDefaultInstance())
			);
	}

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
