package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests that filling every Lucene document out with the analyzed fields it has
 * no text for leaves a text search answering the same thing.
 *
 * <p>An empty entry writes no terms, so it neither matches nor moves the
 * scoring of the documents that do. The two indexes here hold the same
 * definition and the same text; one of them also gives every document values
 * of an object field, which triples the Lucene documents a segment holds.
 */
public class NormDensityScoringTest extends AbstractIndexTest {
	/**
	 * How close two scores have to be to count as the same. Scores are floats
	 * that have been through several multiplications, so they are compared as
	 * numbers rather than for equality.
	 */
	private static final double PRECISION = 0.0001;

	@Test
	public void testOnlyDocumentsHoldingTheTextMatch() throws IOException {
		var result = search(places("with-values", true));

		assertThat(ids(result), contains("short", "long"));
	}

	@Test
	public void testValuesOfObjectFieldsDoNotChangeTheScores() throws IOException {
		var withValues = scores(search(places("scores-with-values", true)));
		var withoutValues = scores(search(places("scores-without-values", false)));

		assertThat(withValues.get(0), is(closeTo(withoutValues.get(0), PRECISION)));
		assertThat(withValues.get(1), is(closeTo(withoutValues.get(1), PRECISION)));
	}

	/**
	 * The word is finished, so it is looked up as a term. A word still being
	 * typed is a prefix, which matches at a flat score.
	 */
	private static SearchResult search(Index index) throws IOException {
		return index.search(
			SearchRequest.create()
				.withQuery(
					Query.text(TextMatcher.of("london").withPrefix(TextMatcher.Prefix.OFF))
				)
				.build()
		);
	}

	/**
	 * Three places, two of them named after the same one. The third holds none
	 * of the text and only takes part in what the field says about the
	 * collection.
	 *
	 * @param name
	 *   name of the index to create
	 * @param withValues
	 *   whether the documents also hold values of the object field, which are
	 *   Lucene documents of their own
	 */
	private Index places(String name, boolean withValues) throws IOException {
		var index = create(name, definition());

		index.addDocument(document("short", "London", withValues));
		index.addDocument(
			document(
				"long",
				"London borough council housing services department office records",
				withValues
			)
		);
		index.addDocument(document("other", "Berlin", withValues));

		index.commit();
		return index;
	}

	private static Document document(String id, String title, boolean withValues) {
		var values = new ArrayList<Document.Value>();
		values.add(new Document.Value("id", id));
		values.add(new Document.Value("title", title));

		if(withValues) {
			values.add(
				new Document.Value("variants", new Document(new Document.Value("name", "paper")))
			);
			values.add(
				new Document.Value("variants", new Document(new Document.Value("name", "cloth")))
			);
		}

		return new Document(values.toArray(new Document.Value[values.size()]));
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				FieldDef.newBuilder()
					.setPrimaryKey(true)
					.setType(
						FieldTypeDef.newBuilder()
							.setString(StringFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.putFields("title", matching())
			.putFields("subtitle", matching())
			.putFields(
				"variants",
				FieldDef.newBuilder()
					.setMultiple(true)
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder()
								.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
								.putFields("name", matching())
						)
					)
					.build()
			);
	}

	private static FieldDef matching() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(
					StringFieldTypeDef.newBuilder()
						.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
				)
			)
			.build();
	}

	private static List<Double> scores(SearchResult result) {
		return result.hits().collect(hit -> (double) hit.score()).toList();
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
