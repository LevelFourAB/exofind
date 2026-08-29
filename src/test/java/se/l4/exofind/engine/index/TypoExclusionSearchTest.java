package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.AnalyzerDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.TokenFilterDef;
import se.l4.exofind.engine.index.settings.QueryTypoExclusions;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.settings.SearchSettingsStore;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for the words an index's search settings match as they are spelled,
 * which keep a name inside text that is otherwise typo tolerant.
 *
 * <p>The fields analyze their text by normalizing it and nothing else, so a
 * term is the word that was written and the distances the tests turn on are
 * the distances between those words.
 */
public class TypoExclusionSearchTest extends AbstractIndexTest {
	/**
	 * A shop where two words sit one mistake apart - a camera brand and a
	 * boat - on fields that forgive one mistake in a word of five letters.
	 */
	private Index cameras() throws IOException {
		var index = create(
			"cameras",
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setPrimaryKey(true)
						.build()
				)
				.putFields("name", typoTolerantText())
				.putFields("description", typoTolerantText())
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "canon camera"),
				new Document.Value("description", "canon lens")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "canoe camera"),
				new Document.Value("description", "canoe paddle")
			)
		);

		index.commit();
		return index;
	}

	/**
	 * A shop whose only usage completes what is typed, where the words sit the
	 * same one mistake apart.
	 */
	private Index tags() throws IOException {
		var index = create(
			"tags",
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.setPrimaryKey(true)
						.build()
				)
				.putFields(
					"name",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setString(
									StringFieldTypeDef.newBuilder()
										.setAutocomplete(text())
								)
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "canon")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "canoe")
			)
		);

		index.commit();
		return index;
	}

	private static FieldDef typoTolerantText() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setString(StringFieldTypeDef.newBuilder().setMatching(text()))
			)
			.build();
	}

	/**
	 * A usage that forgives mistakes and leaves the words themselves alone,
	 * which is what lets a test count letters.
	 */
	private static StringFieldTypeDef.TextUsageConfig text() {
		return StringFieldTypeDef.TextUsageConfig.newBuilder()
			.setAnalyzer(
				AnalyzerDef.newBuilder()
					.addFilters(
						TokenFilterDef.newBuilder()
							.setNormalize(TokenFilterDef.Normalize.getDefaultInstance())
					)
			)
			.setTypoTolerance(
				StringFieldTypeDef.TextUsageConfig.TypoToleranceConfig.getDefaultInstance()
			)
			.build();
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}

	/**
	 * Settings holding one list of words, applied to every field.
	 */
	private static SearchSettings.Snapshot excluding(String... words) {
		return settings(QueryTypoExclusions.newBuilder().addAllWords(List.of(words)).build());
	}

	private static SearchSettings.Snapshot settings(QueryTypoExclusions exclusions) {
		return settings(exclusions, "\"1\"");
	}

	/**
	 * Settings holding one list. The version identifies what the object holds -
	 * the lists are read once per version - so two objects in one test are
	 * given versions of their own.
	 */
	private static SearchSettings.Snapshot settings(
		QueryTypoExclusions exclusions,
		String version
	) {
		var stored = SearchSettingsStore.newBuilder()
			.putTypoExclusions("brands", exclusions)
			.build();

		return new SearchSettings.Snapshot(
			stored,
			null,
			Map.of(),
			stored.getTypoExclusionsMap(),
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

	private static SearchResult searchField(
		Index index,
		String field,
		String text,
		SearchSettings.Snapshot settings
	) throws IOException {
		return index.search(
			SearchRequest.create().withQuery(Query.field(field, TextMatcher.of(text))).build(),
			settings
		);
	}

	/**
	 * The point of the whole thing: a brand is spelled the way it is spelled,
	 * while the field it sits in keeps forgiving mistakes in everything else.
	 */
	@Test
	public void testExcludedWordMatchesOnlyItsOwnSpelling() throws IOException {
		var index = cameras();

		assertThat(ids(search(index, "canon", null)), containsInAnyOrder("1", "2"));

		assertThat(ids(search(index, "canon", excluding("canon"))), contains("1"));
	}

	/**
	 * A word nobody listed is still read as generously as its field says, in
	 * the same search as an excluded one.
	 */
	@Test
	public void testUnlistedWordKeepsTheToleranceOfItsField() throws IOException {
		var index = cameras();
		var settings = excluding("canon");

		assertThat(ids(search(index, "canoe", settings)), containsInAnyOrder("1", "2"));
	}

	/**
	 * The list is read against what was typed, so a misspelling of an excluded
	 * word still finds it. Keeping a word out of every reading of every other
	 * word would cost the walks rather than the list.
	 */
	@Test
	public void testMisspellingStillReachesAnExcludedWord() throws IOException {
		var index = cameras();

		assertThat(ids(search(index, "canonn", excluding("canon"))), contains("1"));
	}

	/**
	 * A list that names fields covers those fields, and the rest of the index
	 * is searched as the definition says.
	 */
	@Test
	public void testListCoversTheFieldsItNames() throws IOException {
		var index = cameras();
		var settings = settings(
			QueryTypoExclusions.newBuilder()
				.addWords("canon")
				.addFields("name")
				.build()
		);

		assertThat(ids(searchField(index, "name", "canon", settings)), contains("1"));
		assertThat(
			ids(searchField(index, "description", "canon", settings)),
			containsInAnyOrder("1", "2")
		);
	}

	/**
	 * Settings outlive generations, so a list left with no field this
	 * generation has excludes nothing rather than failing the search.
	 */
	@Test
	public void testListOfFieldsTheGenerationLacksExcludesNothing() throws IOException {
		var index = cameras();
		var settings = settings(
			QueryTypoExclusions.newBuilder()
				.addWords("canon")
				.addFields("missing")
				.build()
		);

		assertThat(ids(search(index, "canon", settings)), containsInAnyOrder("1", "2"));
	}

	/**
	 * The words are read through the chain of the field, so a word written the
	 * way a person would write it excludes the term the field wrote.
	 */
	@Test
	public void testWordsAreReadThroughTheChainOfTheField() throws IOException {
		var index = cameras();

		assertThat(ids(search(index, "canon", excluding("Canon"))), contains("1"));
	}

	/**
	 * A field that completes what is typed forgives mistakes in the prefixes it
	 * wrote, and an excluded word is looked up among them as it stands.
	 */
	@Test
	public void testExclusionsReachAnAutocompleteUsage() throws IOException {
		var index = tags();

		assertThat(ids(search(index, "canon", null)), containsInAnyOrder("1", "2"));

		assertThat(ids(search(index, "canon", excluding("canon"))), contains("1"));
	}

	/**
	 * Settings the node cannot honour whole are set aside whole, so a search
	 * forgives mistakes as the definition says rather than honouring half of
	 * what was written.
	 */
	@Test
	public void testSettingsSetAsideExcludeNothing() throws IOException {
		var index = cameras();

		var stored = excluding("canon").stored();
		var setAside = new SearchSettings.Snapshot(
			stored,
			null,
			Map.of(),
			Map.of(),
			Lists.immutable.of("something_this_build_lacks"),
			"\"1\""
		);

		assertThat(ids(search(index, "canon", setAside)), containsInAnyOrder("1", "2"));
	}
}
