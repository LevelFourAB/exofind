package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.AnalyzerDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.TokenFilterDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for the bands a word with typos is read as: one band per number of
 * mistakes, each holding the terms that many mistakes reach and fewer do not.
 *
 * <p>The field normalizes its text and nothing else, so a term is the word
 * that was written and the distances these tests turn on are the distances
 * between those words. The words are nine letters or more, the length the
 * defaults ask for before a second mistake is forgiven.
 */
public class TypoBandSearchTest extends AbstractIndexTest {
	/**
	 * An index of one word per document, identified by how far the word sits
	 * from the one the tests type.
	 */
	private Index words(String name, String... values) throws IOException {
		var index = create(
			name,
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
										.setMatching(
											StringFieldTypeDef.TextUsageConfig.newBuilder()
												.setAnalyzer(
													AnalyzerDef.newBuilder()
														.addFilters(
															TokenFilterDef.newBuilder()
																.setNormalize(
																	TokenFilterDef.Normalize
																		.getDefaultInstance()
																)
														)
												)
												.setTypoTolerance(
													StringFieldTypeDef.TextUsageConfig
														.TypoToleranceConfig.getDefaultInstance()
												)
										)
								)
						)
						.build()
				)
		);

		for(var i = 0; i < values.length; i++) {
			index.addDocument(
				new Document(
					new Document.Value("id", "d" + i),
					new Document.Value("name", values[i])
				)
			);
		}

		index.commit();
		return index;
	}

	private static SearchResult search(
		Index index,
		String text,
		TextMatcher.Prefix prefix
	) throws IOException {
		return index.search(
			SearchRequest.create()
				.withQuery(Query.text(TextMatcher.of(text).withPrefix(prefix)))
				.build()
		);
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}

	/**
	 * Assert that every hit scored above the one after it, so the ranking
	 * decided the order the ids came back in.
	 */
	private static void assertRanked(SearchResult result) {
		var hits = result.hits();
		for(var i = 1; i < hits.size(); i++) {
			assertThat(hits.get(i - 1).score(), greaterThan(hits.get(i).score()));
		}
	}

	/**
	 * A word is found at every distance the field forgives and at none beyond
	 * it, and the closer reading comes first.
	 */
	@Test
	public void testWordIsFoundAtEveryForgivenDistance() throws IOException {
		var index = words(
			"typo-bands",
			// `stockholm`, then one, two and three letters of it changed
			"stockholm",
			"stockholn",
			"stockhoin",
			"stockhain",
			"gothenburg"
		);

		var result = search(index, "stockholm", TextMatcher.Prefix.OFF);

		assertThat(ids(result), contains("d0", "d1", "d2"));
		assertRanked(result);
	}

	/**
	 * The same for a word that is still being typed, which matches the terms a
	 * near reading of it starts. A term three mistakes from every prefix the
	 * word could stand for stays out.
	 */
	@Test
	public void testWordBeingTypedIsFoundAtEveryForgivenDistance() throws IOException {
		var index = words(
			"typo-bands-typing",
			// Each word starts with `stockholm` at one more letter changed
			"stockholmare",
			"stockholnare",
			"stockhoinare",
			"stockhainare",
			"gothenburgare"
		);

		var result = search(index, "stockholm", TextMatcher.Prefix.LAST_TOKEN);

		assertThat(ids(result), contains("d0", "d1", "d2"));
		assertRanked(result);
	}
}
