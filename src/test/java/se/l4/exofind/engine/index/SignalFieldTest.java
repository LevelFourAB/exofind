package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.RankingConfig;
import se.l4.exofind.engine.index.schema.SignalConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for fields refreshed in place - what a refresh changes, what it
 * leaves alone, and what every read of a document answers for such a field.
 */
public class SignalFieldTest extends AbstractIndexTest {
	@Test
	public void aRefreshReadsBackWithoutTheDocumentBeingSentAgain() throws IOException {
		var index = catalogue();

		assertTrue(index.updateDocument(patch(set("id", "quiet"), set("popularity", 0.9))));
		index.commit();

		var doc = index.getDocument("quiet");
		assertThat(doc.get("popularity"), is(0.9));
		assertThat(doc.get("name"), is("Trail runner"));
	}

	@Test
	public void aRefreshChangesTheOrderOfResults() throws IOException {
		var index = catalogue();
		index.commit();

		assertThat(ids(search(index, "runner")), contains("popular", "quiet"));

		index.updateDocument(patch(set("id", "quiet"), set("popularity", 1.0)));
		index.updateDocument(patch(set("id", "popular"), set("popularity", 0.1)));
		index.commit();

		assertThat(ids(search(index, "runner")), contains("quiet", "popular"));
	}

	@Test
	public void aRefreshIsReturnedWithTheHits() throws IOException {
		var index = catalogue();
		index.updateDocument(patch(set("id", "quiet"), set("popularity", 0.4)));
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("id", Matchers.equalTo("quiet")))
				.withFields("popularity")
				.build()
		);

		assertThat(result.hits().get(0).document().get("popularity"), is(0.4));
		assertThat(result.hits().get(0).document().get("name"), is(nullValue()));
	}

	@Test
	public void aRefreshIsReturnedWhenEveryFieldIsAskedFor() throws IOException {
		var index = catalogue();
		index.updateDocument(patch(set("id", "quiet"), set("popularity", 0.4)));
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("id", Matchers.equalTo("quiet")))
				.build()
		);

		assertThat(result.hits().get(0).document().get("popularity"), is(0.4));
		assertThat(result.hits().get(0).document().get("name"), is("Trail runner"));
	}

	@Test
	public void resultsCanBeOrderedByTheRefreshedValue() throws IOException {
		var index = catalogue();
		index.updateDocument(patch(set("id", "quiet"), set("popularity", 1.0)));
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text("runner"))
				.withSort(SortBy.field("popularity", SortBy.Order.DESCENDING))
				.build()
		);

		assertThat(ids(result), contains("quiet", "popular"));
	}

	/**
	 * The point of carrying the value over: a catalogue reload that never
	 * mentions the field must not wipe what the last refresh wrote.
	 */
	@Test
	public void aDocumentWrittenWithoutTheFieldKeepsTheValue() throws IOException {
		var index = catalogue();
		index.updateDocument(patch(set("id", "quiet"), set("popularity", 0.7)));

		index.addDocument(
			new Document(
				new Document.Value("id", "quiet"),
				new Document.Value("name", "Trail runner, second edition")
			)
		);
		index.commit();

		var doc = index.getDocument("quiet");
		assertThat(doc.get("popularity"), is(0.7));
		assertThat(doc.get("name"), is("Trail runner, second edition"));
	}

	@Test
	public void aDocumentWrittenWithoutTheFieldKeepsTheValueAcrossACommit() throws IOException {
		var index = catalogue();
		index.updateDocument(patch(set("id", "quiet"), set("popularity", 0.7)));
		index.commit();

		index.addDocument(
			new Document(
				new Document.Value("id", "quiet"),
				new Document.Value("name", "Trail runner, second edition")
			)
		);
		index.commit();

		assertThat(index.getDocument("quiet").get("popularity"), is(0.7));
	}

	@Test
	public void aDocumentWrittenWithTheFieldReplacesTheValue() throws IOException {
		var index = catalogue();
		index.updateDocument(patch(set("id", "quiet"), set("popularity", 0.7)));

		index.addDocument(
			new Document(
				new Document.Value("id", "quiet"),
				new Document.Value("name", "Trail runner"),
				new Document.Value("popularity", 0.2)
			)
		);
		index.commit();

		assertThat(index.getDocument("quiet").get("popularity"), is(0.2));
	}

	@Test
	public void aChangeToAnotherFieldKeepsTheValue() throws IOException {
		var index = catalogue();
		index.updateDocument(patch(set("id", "quiet"), set("popularity", 0.7)));
		index.updateDocument(patch(set("id", "quiet"), set("name", "Road runner")));
		index.commit();

		var doc = index.getDocument("quiet");
		assertThat(doc.get("popularity"), is(0.7));
		assertThat(doc.get("name"), is("Road runner"));
	}

	@Test
	public void aChangeToAnotherFieldKeepsTheValueAcrossACommit() throws IOException {
		var index = catalogue();
		index.updateDocument(patch(set("id", "quiet"), set("popularity", 0.7)));
		index.commit();

		index.updateDocument(patch(set("id", "quiet"), set("name", "Road runner")));
		index.commit();

		var doc = index.getDocument("quiet");
		assertThat(doc.get("popularity"), is(0.7));
		assertThat(doc.get("name"), is("Road runner"));
	}

	@Test
	public void aChangeNamingTheFieldBesideOthersTakesTheNewValue() throws IOException {
		var index = catalogue();
		index.updateDocument(patch(set("id", "quiet"), set("popularity", 0.7)));

		index.updateDocument(
			patch(set("id", "quiet"), set("name", "Road runner"), set("popularity", 0.3))
		);
		index.commit();

		var doc = index.getDocument("quiet");
		assertThat(doc.get("popularity"), is(0.3));
		assertThat(doc.get("name"), is("Road runner"));
	}

	@Test
	public void refreshesOfTheSameDocumentApplyInTheOrderTheyAreGiven() throws IOException {
		var index = catalogue();

		index.updateDocument(patch(set("id", "quiet"), set("popularity", 0.1)));
		index.updateDocument(patch(set("id", "quiet"), set("popularity", 0.2)));
		index.updateDocument(patch(set("id", "quiet"), set("popularity", 0.3)));
		index.commit();

		assertThat(index.getDocument("quiet").get("popularity"), is(0.3));
	}

	@Test
	public void aRefreshOfAKeyNothingIsIndexedUnderChangesNothing() throws IOException {
		var index = catalogue();

		assertFalse(index.updateDocument(patch(set("id", "404"), set("popularity", 0.5))));
		index.commit();

		assertThat(index.getDocument("404"), is(nullValue()));
	}

	@Test
	public void aRefreshOfADocumentRemovedSinceTheLastCommitChangesNothing() throws IOException {
		var index = catalogue();
		index.commit();

		index.deleteDocument("quiet");

		assertFalse(index.updateDocument(patch(set("id", "quiet"), set("popularity", 0.5))));
	}

	@Test
	public void aRefreshOfADocumentAddedSinceTheLastCommitApplies() throws IOException {
		var index = catalogue();
		index.commit();

		index.addDocument(
			new Document(
				new Document.Value("id", "fresh"),
				new Document.Value("name", "Fresh runner")
			)
		);

		assertTrue(index.updateDocument(patch(set("id", "fresh"), set("popularity", 0.5))));
		index.commit();

		assertThat(index.getDocument("fresh").get("popularity"), is(0.5));
	}

	@Test
	public void aFieldNamedAndGivenNothingIsEmptied() throws IOException {
		var index = catalogue();

		assertTrue(index.updateDocument(
			DocumentPatch.replacing(
				Sets.immutable.of("id", "popularity"),
				Lists.immutable.of(new Document.Value("id", "popular"))
			)
		));
		index.commit();

		assertThat(index.getDocument("popular").get("popularity"), is(nullValue()));
		assertThat(ids(search(index, "runner")), contains("popular", "quiet"));
	}

	@Test
	public void aValueTheFieldDoesNotAcceptIsRefused() throws IOException {
		var index = catalogue();

		assertThrows(
			ValidationException.class,
			() -> index.updateDocument(patch(set("id", "quiet"), set("popularity", 2.0)))
		);
		assertThrows(
			ValidationException.class,
			() -> index.updateDocument(patch(set("id", "quiet"), set("popularity", "high")))
		);

		index.commit();
		assertThat(index.getDocument("quiet").get("popularity"), is(0.0));
	}

	/**
	 * A refresh touches nothing but doc values, so it needs no copy of the
	 * document - which is what lets an index that keeps none refresh its
	 * signals while it refuses every other partial change.
	 */
	@Test
	public void anIndexThatKeepsNoCopyOfItsDocumentsStillRefreshes() throws IOException {
		var index = create(
			"sourceless",
			definition().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Trail runner"),
				new Document.Value("popularity", 0.1)
			)
		);

		assertTrue(index.updateDocument(patch(set("id", "1"), set("popularity", 0.9))));
		assertFalse(index.updateDocument(patch(set("id", "404"), set("popularity", 0.9))));
		assertThrows(
			IndexSourceNotKeptException.class,
			() -> index.updateDocument(patch(set("id", "1"), set("name", "Road runner")))
		);

		index.commit();

		assertThat(index.getDocument("1").get("popularity"), is(0.9));

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("id", Matchers.equalTo("1")))
				.withFields("popularity")
				.build()
		);
		assertThat(result.hits().get(0).document().get("popularity"), is(0.9));
	}

	@Test
	public void aScanOfTheIndexAnswersWithTheRefreshedValue() throws IOException {
		var index = catalogue();
		index.updateDocument(patch(set("id", "quiet"), set("popularity", 0.6)));
		index.commit();

		var seen = Lists.mutable.<Document>empty();
		index.scanDocuments(null, 10, seen::add);

		var quiet = seen.detect(doc -> doc.get("id").equals("quiet"));
		assertThat(quiet.get("popularity"), is(0.6));
		assertThat(quiet.get("name"), is("Trail runner"));
	}

	@Test
	public void anIntegerSignalReadsBackAsAnInteger() throws IOException {
		var index = catalogue();

		index.updateDocument(patch(set("id", "quiet"), set("views", 1200)));
		index.commit();

		assertThat(index.getDocument("quiet").get("views"), is(1200));
	}

	private static SearchResult search(Index index, String text) throws IOException {
		return index.search(
			SearchRequest.create()
				.withQuery(Query.text(text))
				.build()
		);
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}

	private static DocumentPatch patch(Document.Value... values) {
		var names = Sets.mutable.<String>empty();
		for(var value : values) {
			names.add(value.name());
		}

		return DocumentPatch.replacing(names.toImmutable(), Lists.immutable.of(values));
	}

	private static Document.Value set(String name, Object value) {
		return new Document.Value(name, value);
	}

	/**
	 * Two products matching one search equally well, told apart by nothing
	 * but the signal - so a refresh of it is what decides their order.
	 */
	private Index catalogue() throws IOException {
		var index = create("catalogue", definition());

		index.addDocument(
			new Document(
				new Document.Value("id", "popular"),
				new Document.Value("name", "Trail runner"),
				new Document.Value("popularity", 0.5)
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "quiet"),
				new Document.Value("name", "Trail runner"),
				new Document.Value("popularity", 0.0)
			)
		);

		return index;
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				string()
					.setPrimaryKey(true)
					.setFilter(FilterConfig.getDefaultInstance())
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
										StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
									)
							)
					)
					.build()
			)
			.putFields(
				"popularity",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setDouble(
								DoubleFieldTypeDef.newBuilder()
									.setValidation(
										DoubleFieldTypeDef.ValidationConfig.newBuilder()
											.setMin(0)
											.setMax(1)
									)
							)
					)
					.setSignal(SignalConfig.getDefaultInstance())
					.build()
			)
			.putFields(
				"views",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setInt32(Int32FieldTypeDef.getDefaultInstance())
					)
					.setSignal(SignalConfig.getDefaultInstance())
					.build()
			)
			.setRanking(
				RankingConfig.newBuilder()
					.addSignals(
						RankingConfig.Signal.newBuilder()
							.setField("popularity")
							.setLinear(RankingConfig.Signal.Linear.newBuilder().setCeiling(1))
					)
			);
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setString(StringFieldTypeDef.getDefaultInstance())
			);
	}
}
