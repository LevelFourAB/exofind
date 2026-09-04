package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.greaterThan;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.settings.QuerySynonyms;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.settings.SearchSettingsStore;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for the synonym sets of an index's search settings, which widen what a
 * search asks for rather than what a document was indexed as - so a rule takes
 * effect over documents that were indexed before it.
 */
public class QuerySynonymSearchTest extends AbstractIndexTest {
	/**
	 * A shop whose documents were indexed with no synonyms at all, which is
	 * what every search here widens after the fact.
	 */
	private Index shop() throws IOException {
		var index = create(
			"shop",
			IndexDef.newBuilder()
				.putFields(
					"id",
					string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build()
				)
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
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "running sneakers"),
				new Document.Value("description", "For the road")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "leather trainers"),
				new Document.Value("description", "For the street")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "ny cheesecake"),
				new Document.Value("description", "Baked in new york today")
			)
		);

		index.commit();
		return index;
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(type));
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}

	/**
	 * Settings holding one set of equivalent words, applied to every field.
	 */
	private static SearchSettings.Snapshot equivalent(String... terms) {
		return settings(
			"merch",
			QuerySynonyms.newBuilder()
				.setSet(
					ResourcesDef.SynonymsResource.newBuilder()
						.addRules(
							ResourcesDef.SynonymsResource.Rule.newBuilder()
								.setEquivalent(
									ResourcesDef.SynonymsResource.Rule.Equivalent.newBuilder()
										.addAllTerms(List.of(terms))
								)
						)
				)
				.build()
		);
	}

	private static SearchSettings.Snapshot settings(String name, QuerySynonyms synonyms) {
		return settings(name, synonyms, "\"1\"");
	}

	/**
	 * Settings holding one set. The version identifies what the object holds -
	 * the sets are compiled once per version - so two objects in one test are
	 * given versions of their own.
	 */
	private static SearchSettings.Snapshot settings(
		String name,
		QuerySynonyms synonyms,
		String version
	) {
		var stored = SearchSettingsStore.newBuilder()
			.putSynonyms(name, synonyms)
			.build();

		return new SearchSettings.Snapshot(
			stored,
			null,
			stored.getSynonymsMap(),
			Map.of(),
			Map.of(),
			Lists.immutable.empty(),
			version
		);
	}

	private static SearchResult search(
		Index index,
		String text,
		SearchSettings.Snapshot settings
	) throws IOException {
		return index.search(
			SearchRequest.create().withQuery(Query.text(text)).build(),
			settings
		);
	}

	private static SearchResult phrase(
		Index index,
		String text,
		SearchSettings.Snapshot settings
	) throws IOException {
		return index.search(
			SearchRequest.create()
				.withQuery(Query.text(TextMatcher.of(text).withMatch(TextMatcher.Match.PHRASE)))
				.build(),
			settings
		);
	}

	/**
	 * The point of the whole thing: the documents were indexed before the rule
	 * existed, and the rule finds them anyway.
	 */
	@Test
	public void testSetAddedAfterIndexingFindsWhatIsAlreadyThere() throws IOException {
		var index = shop();

		assertThat(ids(search(index, "trainers", null)), contains("2"));

		var settings = equivalent("trainers", "sneakers");
		assertThat(
			ids(search(index, "trainers", settings)),
			containsInAnyOrder("1", "2")
		);
	}

	@Test
	public void testEquivalentWordsMatchEachOther() throws IOException {
		var index = shop();
		var settings = equivalent("trainers", "sneakers");

		assertThat(ids(search(index, "sneakers", settings)), containsInAnyOrder("1", "2"));
		assertThat(ids(search(index, "trainers", settings)), containsInAnyOrder("1", "2"));
	}

	/**
	 * A mapping goes one way: searching what it reads from also searches what
	 * it adds, and searching what it adds does not search back.
	 */
	@Test
	public void testMappedWordsGoOneWay() throws IOException {
		var index = shop();

		var settings = settings(
			"merch",
			QuerySynonyms.newBuilder()
				.setSet(
					ResourcesDef.SynonymsResource.newBuilder()
						.addRules(
							ResourcesDef.SynonymsResource.Rule.newBuilder()
								.setMapping(
									ResourcesDef.SynonymsResource.Rule.Mapping.newBuilder()
										.addFrom("trainers")
										.addTo("sneakers")
								)
						)
				)
				.build()
		);

		assertThat(
			ids(search(index, "trainers", settings)),
			containsInAnyOrder("1", "2")
		);
		assertThat(ids(search(index, "sneakers", settings)), contains("1"));
	}

	/**
	 * A rule standing for several words asks for them in sequence, so it finds
	 * a document holding the words rather than one holding any of them.
	 */
	@Test
	public void testRuleOfSeveralWordsAsksForThemInSequence() throws IOException {
		var index = shop();

		var settings = settings(
			"places",
			QuerySynonyms.newBuilder()
				.setSet(
					ResourcesDef.SynonymsResource.newBuilder()
						.addRules(
							ResourcesDef.SynonymsResource.Rule.newBuilder()
								.setEquivalent(
									ResourcesDef.SynonymsResource.Rule.Equivalent.newBuilder()
										.addTerms("ny")
										.addTerms("new york")
								)
						)
				)
				.build()
		);

		// `ny` also asks for the two words, which the description holds
		assertThat(ids(search(index, "ny", settings)), contains("3"));

		// And the two words also ask for `ny`, which the name holds
		assertThat(ids(search(index, "new york", settings)), contains("3"));

		/*
		 * The words of the rule are asked for together: a document holding
		 * `new` somewhere and `york` somewhere else is not a document holding
		 * `new york`.
		 */
		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("name", "new bagels"),
				new Document.Value("description", "york street")
			)
		);
		index.commit();

		assertThat(ids(search(index, "ny", settings)), contains("3"));
	}

	/**
	 * A rule of several words is a reading of the text, so a phrase written
	 * with one of its sides finds a document written with the other.
	 */
	@Test
	public void testPhrasesRunThroughARuleOfSeveralWords() throws IOException {
		var index = shop();

		var settings = settings(
			"places",
			QuerySynonyms.newBuilder()
				.setSet(
					ResourcesDef.SynonymsResource.newBuilder()
						.addRules(
							ResourcesDef.SynonymsResource.Rule.newBuilder()
								.setEquivalent(
									ResourcesDef.SynonymsResource.Rule.Equivalent.newBuilder()
										.addTerms("ny")
										.addTerms("new york")
								)
						)
				)
				.build()
		);

		assertThat(ids(phrase(index, "new york cheesecake", settings)), contains("3"));
		assertThat(ids(phrase(index, "ny cheesecake", settings)), contains("3"));
	}

	/**
	 * A set naming fields is applied to those and no others, so a word can be
	 * widened where a merchandiser means it and left alone elsewhere.
	 */
	@Test
	public void testSetNamingFieldsIsAppliedToThoseAlone() throws IOException {
		var index = shop();

		var settings = settings(
			"streets",
			QuerySynonyms.newBuilder()
				.setSet(
					ResourcesDef.SynonymsResource.newBuilder()
						.addRules(
							ResourcesDef.SynonymsResource.Rule.newBuilder()
								.setEquivalent(
									ResourcesDef.SynonymsResource.Rule.Equivalent.newBuilder()
										.addTerms("road")
										.addTerms("street")
								)
						)
				)
				.addFields("description")
				.build()
		);

		// Both descriptions answer, as the rule reads them as the same word
		assertThat(
			ids(search(index, "road", settings)),
			containsInAnyOrder("1", "2")
		);

		var onName = settings(
			"streets",
			settings.stored().getSynonymsMap().get("streets").toBuilder()
				.clearFields()
				.addFields("name")
				.build(),
			"\"2\""
		);

		// Applied to `name` alone, the descriptions are read as they were typed
		assertThat(ids(search(index, "road", onName)), contains("1"));
	}

	/**
	 * A document holding the word that was typed ranks above one holding only
	 * a word the rules added.
	 */
	@Test
	public void testTypedWordCountsForMoreThanTheWordARuleAdded() throws IOException {
		var index = shop();
		var settings = equivalent("trainers", "sneakers");

		var result = search(index, "trainers", settings);

		var hits = result.hits().toList();
		assertThat(hits.size(), is(2));
		assertThat(hits.get(0).id(), is("2"));
		assertThat(hits.get(0).score(), greaterThan(hits.get(1).score()));
	}

	/**
	 * A boost of one makes the two count the same, which is what an index-time
	 * set does.
	 */
	@Test
	public void testBoostOfOneCountsBothTheSame() throws IOException {
		var index = shop();

		var settings = settings(
			"merch",
			equivalent("trainers", "sneakers")
				.stored()
				.getSynonymsMap()
				.get("merch")
				.toBuilder()
				.setBoost(1f)
				.build()
		);

		var hits = search(index, "trainers", settings).hits().toList();

		assertThat(hits.size(), is(2));
		assertThat(hits.get(0).score(), is(hits.get(1).score()));
	}

	/**
	 * A term the analysis chain leaves nothing of cannot be a rule, and the
	 * rest of the set is applied all the same.
	 */
	@Test
	public void testRuleOfAStopwordIsLeftOut() throws IOException {
		var index = shop();

		var settings = settings(
			"merch",
			QuerySynonyms.newBuilder()
				.setSet(
					ResourcesDef.SynonymsResource.newBuilder()
						.addRules(
							ResourcesDef.SynonymsResource.Rule.newBuilder()
								.setMapping(
									ResourcesDef.SynonymsResource.Rule.Mapping.newBuilder()
										.addFrom("the")
										.addTo("sneakers")
								)
						)
						.addRules(
							ResourcesDef.SynonymsResource.Rule.newBuilder()
								.setEquivalent(
									ResourcesDef.SynonymsResource.Rule.Equivalent.newBuilder()
										.addTerms("trainers")
										.addTerms("sneakers")
								)
						)
				)
				.build()
		);

		assertThat(ids(search(index, "the", settings)), is(empty()));
		assertThat(
			ids(search(index, "trainers", settings)),
			containsInAnyOrder("1", "2")
		);
	}

	/**
	 * A field written for completing what is typed is widened too: the rules
	 * are read through its own chain, so what they add is looked up among the
	 * prefixes it wrote.
	 *
	 * A rule reads whole words, so it takes effect once the word it reads is
	 * finished - what is typed before that is the prefix of a word the rule
	 * has nothing to say about.
	 */
	@Test
	public void testAutocompleteUsageIsWidenedToo() throws IOException {
		var index = create(
			"completion",
			IndexDef.newBuilder()
				.putFields(
					"id",
					string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build()
				)
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
				new Document.Value("name", "running sneakers")
			)
		);
		index.commit();

		var settings = equivalent("trainers", "sneakers");

		assertThat(ids(search(index, "trainers", null)), is(empty()));
		assertThat(ids(search(index, "trainers", settings)), contains("1"));

		// The word is only read as a rule once it is finished
		assertThat(ids(search(index, "traine", settings)), is(empty()));
	}

	/**
	 * A whole-value match is what a document is named, so widening never
	 * reaches it - a search for a synonym finds the document through its words
	 * and is not treated as having named it.
	 */
	@Test
	public void testWholeValueMatchIsNotWidened() throws IOException {
		var index = create(
			"named",
			IndexDef.newBuilder()
				.putFields(
					"id",
					string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build()
				)
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.newBuilder()
									.setExact(
										StringFieldTypeDef.TextUsageConfig.ExactConfig
											.newBuilder()
											.setBoost(100f)
									)
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "named-it"),
				new Document.Value("name", "trainers")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "synonym"),
				new Document.Value("name", "sneakers")
			)
		);
		index.commit();

		var hits = search(index, "trainers", equivalent("trainers", "sneakers"))
			.hits()
			.toList();

		assertThat(hits.size(), is(2));
		assertThat(hits.get(0).id(), is("named-it"));
	}

	/**
	 * A rule that adds the word it read leaves the word asked for once, rather
	 * than asking for it twice and counting a document that holds it twice.
	 */
	@Test
	public void testRuleAddingTheWordItReadAsksForItOnce() throws IOException {
		var index = shop();

		var settings = settings(
			"merch",
			QuerySynonyms.newBuilder()
				.setSet(
					ResourcesDef.SynonymsResource.newBuilder()
						.addRules(
							ResourcesDef.SynonymsResource.Rule.newBuilder()
								.setMapping(
									ResourcesDef.SynonymsResource.Rule.Mapping.newBuilder()
										.addFrom("trainers")
										.addTo("trainers")
								)
						)
				)
				.build()
		);

		var plain = search(index, "trainers", null).hits().toList();
		var widened = search(index, "trainers", settings).hits().toList();

		assertThat(ids(search(index, "trainers", settings)), contains("2"));
		assertThat(widened.get(0).score(), is(plain.get(0).score()));
	}

	/**
	 * Settings the node cannot honour whole are set aside whole, so a search
	 * runs on the definition alone rather than on half of what was written.
	 */
	@Test
	public void testSettingsSetAsideWidenNothing() throws IOException {
		var index = shop();

		var stored = equivalent("trainers", "sneakers").stored();
		var setAside = new SearchSettings.Snapshot(
			stored,
			null,
			Map.of(),
			Map.of(),
			Map.of(),
			Lists.immutable.of("something_this_build_lacks"),
			"\"1\""
		);

		assertThat(ids(search(index, "trainers", setAside)), contains("2"));
	}
}
