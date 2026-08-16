package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.AnalyzerDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ResourcesDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.TokenFilterDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for the resources of an index taking effect in search - a synonym
 * set widening what a value matches, a shared stopword list dropping its
 * words, and a named chain being what a field analyzes with.
 */
public class ResourcesSearchTest extends AbstractIndexTest {
	/**
	 * An index whose `name` analyzes through the shared `prose` chain:
	 * normalized, a shared stopword list, and synonyms.
	 */
	private Index shop() throws IOException {
		var index = create(
			"shop",
			IndexDef.newBuilder()
				.setResources(
					ResourcesDef.newBuilder()
						.putAnalyzers(
							"prose",
							AnalyzerDef.newBuilder()
								.addFilters(
									TokenFilterDef.newBuilder()
										.setNormalize(
											TokenFilterDef.Normalize.getDefaultInstance()
										)
								)
								.addFilters(
									TokenFilterDef.newBuilder()
										.setStopwords(
											TokenFilterDef.Stopwords.newBuilder()
												.setNamed(
													TokenFilterDef.Stopwords.NamedWords
														.newBuilder()
														.setName("brands")
												)
										)
								)
								.addFilters(
									TokenFilterDef.newBuilder()
										.setSynonyms(
											TokenFilterDef.Synonyms.newBuilder()
												.setName("cars")
										)
								)
								.build()
						)
						.putStopwords(
							"brands",
							ResourcesDef.StopwordsResource.newBuilder()
								.addWords("acme")
								.build()
						)
						.putSynonyms(
							"cars",
							ResourcesDef.SynonymsResource.newBuilder()
								.addRules(
									ResourcesDef.SynonymsResource.Rule.newBuilder()
										.setEquivalent(
											ResourcesDef.SynonymsResource.Rule.Equivalent
												.newBuilder()
												.addTerms("car")
												.addTerms("automobile")
										)
								)
								.addRules(
									ResourcesDef.SynonymsResource.Rule.newBuilder()
										.setMapping(
											ResourcesDef.SynonymsResource.Rule.Mapping
												.newBuilder()
												.addFrom("ny")
												.addTo("new york")
										)
								)
								.build()
						)
				)
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
									.setAnalyzerRef("prose")
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "car dealership")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "ny pizza")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "acme widgets")
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

	private static SearchResult search(Index index, String text) throws IOException {
		return index.search(SearchRequest.create().withQuery(Query.text(text)).build());
	}

	private static SearchResult phrase(Index index, String text) throws IOException {
		return index.search(
			SearchRequest.create()
				.withQuery(
					Query.text(TextMatcher.of(text).withMatch(TextMatcher.Match.PHRASE))
				)
				.build()
		);
	}

	@Test
	public void testEquivalentSynonymsMatchEachOther() throws IOException {
		var index = shop();

		assertThat(ids(search(index, "automobile")), contains("1"));
		assertThat(ids(search(index, "car")), contains("1"));
	}

	/**
	 * A mapping goes one way: a value containing `ny` answers searches for
	 * `new york`, but a value containing `new york` would not answer `ny`.
	 */
	@Test
	public void testMappedSynonymsGoOneWay() throws IOException {
		var index = shop();

		assertThat(ids(search(index, "new york")), contains("2"));
		assertThat(ids(search(index, "pizza")), contains("2"));

		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("name", "new york bagels")
			)
		);
		index.commit();

		assertThat(ids(search(index, "ny")), contains("2"));
	}

	@Test
	public void testNamedStopwordsAreDropped() throws IOException {
		var index = shop();

		assertThat(ids(search(index, "widgets")), contains("3"));
		assertThat(ids(search(index, "acme")), is(empty()));
	}

	/**
	 * A synonym of several words matches them in sequence - the one-word
	 * `ny` in a document is found by the two words of the search.
	 */
	@Test
	public void testMultiWordSynonymsMatchAllTheirWords() throws IOException {
		var index = shop();

		assertThat(ids(search(index, "new york pizza")), contains("2"));
	}

	/**
	 * Synonyms are written into the index with positions, so a phrase runs
	 * through them - a value holding a synonym answers a phrase written with
	 * the other term, even when the two count a different number of words.
	 */
	@Test
	public void testPhrasesMatchThroughSynonyms() throws IOException {
		var index = shop();

		assertThat(ids(phrase(index, "automobile dealership")), contains("1"));
		assertThat(ids(phrase(index, "new york pizza")), contains("2"));
	}

	/**
	 * The same chain, shared by name, serves several fields - which is the
	 * point of naming it once in the resources.
	 */
	@Test
	public void testNamedChainServesSeveralFields() throws IOException {
		var index = create(
			"multi",
			IndexDef.newBuilder()
				.setResources(
					ResourcesDef.newBuilder()
						.putAnalyzers(
							"plain",
							AnalyzerDef.newBuilder()
								.addFilters(
									TokenFilterDef.newBuilder()
										.setNormalize(
											TokenFilterDef.Normalize.getDefaultInstance()
										)
								)
								.build()
						)
				)
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
									.setAnalyzerRef("plain")
							)
					).build()
				)
				.putFields(
					"description",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.newBuilder()
									.setAnalyzerRef("plain")
							)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "The Guide"),
				new Document.Value("description", "About the towel")
			)
		);
		index.commit();

		// The chain keeps stopwords, so `the` matches in both fields
		assertThat(ids(search(index, "the")), containsInAnyOrder("1"));
		assertThat(ids(search(index, "towel")), contains("1"));
	}

	/**
	 * Fields whose chains cut the text into different numbers of words cannot
	 * be lined up word by word, so each group of fields that agree matches on
	 * its own - a document has to satisfy the search within one group.
	 */
	@Test
	public void testWordsSpreadOverFieldsWhoseChainsDisagree() throws IOException {
		var index = create(
			"misaligned",
			IndexDef.newBuilder()
				.setResources(
					ResourcesDef.newBuilder()
						.putAnalyzers(
							"verbatim",
							AnalyzerDef.newBuilder()
								.addFilters(
									TokenFilterDef.newBuilder()
										.setNormalize(
											TokenFilterDef.Normalize.getDefaultInstance()
										)
								)
								.build()
						)
				)
				.putFields(
					"id",
					string(StringFieldTypeDef.newBuilder()).setPrimaryKey(true).build()
				)
				.putFields(
					"title",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.newBuilder()
									.setAnalyzerRef("verbatim")
							)
					).build()
				)
				.putFields(
					"body",
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
				new Document.Value("id", "in-title"),
				new Document.Value("title", "The red one"),
				new Document.Value("body", "Nothing of interest")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "in-body"),
				new Document.Value("title", "Something else"),
				new Document.Value("body", "Red shoes")
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "missing-a-word"),
				new Document.Value("title", "Red only"),
				new Document.Value("body", "Nothing here")
			)
		);
		index.commit();

		/*
		 * `the red` is two words to the title, whose chain keeps stopwords, and
		 * one to the body, whose default chain drops `the`. The title group
		 * asks for both words; the body group asks for `red` alone - so a title
		 * holding only `red` satisfies neither.
		 */
		var result = search(index, "the red");

		assertThat(ids(result), containsInAnyOrder("in-title", "in-body"));
	}
}
