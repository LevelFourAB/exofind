package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.BooleanFieldTypeDef;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for the copy of a document that is kept alongside the index - what
 * comes back out of it, when one is kept at all, and what happens to documents
 * indexed either side of that being changed.
 */
public class DocumentSourceTest extends AbstractIndexTest {
	@Test
	public void testDocumentIsKeptWithoutAnyFieldAskingToBeStored() throws IOException {
		var index = books(IndexDef.newBuilder());

		var doc = index.getDocument("1");

		assertThat(doc, is(notNullValue()));
		assertThat(doc.get("name"), is("Silent Spring"));
		assertThat(doc.get("category"), is("non-fiction"));
	}

	/**
	 * A boolean is a byte on disk and a string once a field has been analyzed,
	 * so a value that came back as either would look nothing like the document
	 * that was indexed.
	 */
	@Test
	public void testValuesComeBackAsTheTypeTheyWereGivenAs() throws IOException {
		var index = books(IndexDef.newBuilder());

		assertThat(index.getDocument("1").get("published"), is((Object) true));
		assertThat(index.getDocument("2").get("published"), is((Object) false));
	}

	@Test
	public void testSeveralValuesOfOneFieldAllComeBack() throws IOException {
		var index = books(IndexDef.newBuilder());

		assertThat(valuesOf(index.getDocument("1"), "tags"), contains("nature", "science"));
	}

	@Test
	public void testDocumentIsNotKeptWhenTurnedOff() throws IOException {
		var index = books(IndexDef.newBuilder().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE));

		var doc = index.getDocument("1");

		// `name` asks to be stored, the rest of the document does not
		assertThat(doc.get("name"), is("Silent Spring"));
		assertThat(doc.get("category"), is(nullValue()));
		assertThat(doc.get("published"), is(nullValue()));
	}

	/**
	 * Changing the setting does not rewrite what has already been indexed, so
	 * both kinds of document have to keep reading whichever way it is left.
	 */
	@Test
	public void testDocumentsIndexedBeforeItWasTurnedOnStillRead() throws IOException {
		var index = books(IndexDef.newBuilder().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE));

		index.updateDefinition(
			definition(IndexDef.newBuilder().setSource(IndexDef.SourceMode.SOURCE_MODE_FULL))
				.build()
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("name", "Winter Light"),
				new Document.Value("category", "fiction"),
				new Document.Value("published", true)
			)
		);
		index.commit();

		// Indexed before, so only what was stored per field survives
		var before = index.getDocument("1");
		assertThat(before.get("name"), is("Silent Spring"));
		assertThat(before.get("category"), is(nullValue()));

		// Indexed after, so the whole document is there
		var after = index.getDocument("4");
		assertThat(after.get("name"), is("Winter Light"));
		assertThat(after.get("category"), is("fiction"));
	}

	@Test
	public void testDocumentsIndexedBeforeItWasTurnedOffStillRead() throws IOException {
		var index = books(IndexDef.newBuilder());

		index.updateDefinition(
			definition(IndexDef.newBuilder().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE))
				.build()
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("name", "Winter Light"),
				new Document.Value("category", "fiction"),
				new Document.Value("published", true)
			)
		);
		index.commit();

		var before = index.getDocument("1");
		assertThat(before.get("category"), is("non-fiction"));

		var after = index.getDocument("4");
		assertThat(after.get("name"), is("Winter Light"));
		assertThat(after.get("category"), is(nullValue()));
	}

	@Test
	public void testAskingForAFieldNothingCanAnswerForIsRefused() throws IOException {
		var index = books(IndexDef.newBuilder().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE));

		var e = assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(SearchRequest.create().withFields("category").build())
		);

		assertThat(e.getCode(), is("index:query:usage_not_enabled"));
	}

	@Test
	public void testAskingForAStoredFieldIsAnswered() throws IOException {
		var index = books(IndexDef.newBuilder().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE));

		var result = index.search(
			SearchRequest.create().withFields("name", "id").build()
		);

		assertThat(result.hits().get(0).document().get("name"), is(notNullValue()));
	}

	/**
	 * An object holds no value of its own to store, so the copy of the document
	 * is the only thing that could ever answer for one.
	 */
	@Test
	public void testAskingForAnObjectWithoutTheKeptDocumentIsRefused() throws IOException {
		var index = books(IndexDef.newBuilder().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE));

		var e = assertThrows(
			IndexSourceRequiredException.class,
			() -> index.search(SearchRequest.create().withFields("dimensions").build())
		);

		assertThat(e.getCode(), is("index:query:source_not_kept"));

		var inside = assertThrows(
			IndexSourceRequiredException.class,
			() -> index.search(SearchRequest.create().withFields("dimensions.width").build())
		);

		assertThat(inside.getCode(), is("index:query:source_not_kept"));
	}

	@Test
	public void testEveryFieldCanBeAskedForWhileTheDocumentIsKept() throws IOException {
		var index = books(IndexDef.newBuilder());

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("category", Matchers.equalTo("non-fiction")))
				.withFields("category", "dimensions.width")
				.build()
		);

		var document = result.hits().get(0).document();
		assertThat(document.get("category"), is("non-fiction"));
		assertThat(((Document) document.get("dimensions")).get("width"), is(12.5d));
	}

	/**
	 * Arrays compare by identity in a record, so the vector is checked element
	 * by element rather than through the value as a whole.
	 */
	@Test
	public void testVectorReadsBackTheSame() {
		var doc = new Document(
			new Document.Value("embedding", new float[] { 1.5f, -2f, 0.25f })
		);

		var read = DocumentSource.decode(new BytesRef(DocumentSource.encode(doc)));

		assertThat(read.fields().length, is(1));
		assertThat(read.fields()[0].name(), is("embedding"));
		assertThat(read.fields()[0].value(), is(new float[] { 1.5f, -2f, 0.25f }));
	}

	/**
	 * Each width of number is its own case in the stored format, so a value
	 * comes back as the type it was given as rather than the widest one.
	 */
	@Test
	public void testNumbersReadBackAsTheTypeTheyWereGivenAs() {
		var doc = new Document(
			new Document.Value("pages", 320),
			new Document.Value("isbn", 9780000000001L),
			new Document.Value("weight", 0.4f),
			new Document.Value("price", 24.5d)
		);

		var read = DocumentSource.decode(new BytesRef(DocumentSource.encode(doc)));

		assertThat(read.fields()[0], is(new Document.Value("pages", 320, null)));
		assertThat(read.fields()[1], is(new Document.Value("isbn", 9780000000001L, null)));
		assertThat(read.fields()[2], is(new Document.Value("weight", 0.4f, null)));
		assertThat(read.fields()[3], is(new Document.Value("price", 24.5d, null)));
	}

	@Test
	public void testDecodingSomeFieldsLeavesTheRestUnread() {
		var doc = new Document(
			new Document.Value("name", "Silent Spring"),
			new Document.Value("published", true),
			new Document.Value("tags", "nature"),
			new Document.Value("tags", "science"),
			new Document.Value("summary", "Vår tysta vår", "sv"),
			new Document.Value(
				"dimensions",
				new Document(new Document.Value("width", 12.5d))
			)
		);

		var read = DocumentSource.decode(
			new BytesRef(DocumentSource.encode(doc)),
			name -> name.equals("tags") || name.equals("summary") || name.equals("dimensions")
		);

		assertThat(read.fields().length, is(4));
		assertThat(read.fields()[0], is(new Document.Value("tags", "nature", null)));
		assertThat(read.fields()[1], is(new Document.Value("tags", "science", null)));
		assertThat(read.fields()[2], is(new Document.Value("summary", "Vår tysta vår", "sv")));
		assertThat(read.fields()[3].name(), is("dimensions"));
		assertThat(((Document) read.fields()[3].value()).get("width"), is(12.5d));
	}

	@Test
	public void testDecodingNoFieldsReadsNothing() {
		var doc = new Document(
			new Document.Value("name", "Silent Spring"),
			new Document.Value("published", true)
		);

		var read = DocumentSource.decode(new BytesRef(DocumentSource.encode(doc)), name -> false);

		assertThat(read.fields().length, is(0));
	}

	@Test
	public void testGeoPointReadsBackTheSame() {
		var doc = new Document(
			new Document.Value("location", new GeoPoint(59.325, 18.070))
		);

		var read = DocumentSource.decode(new BytesRef(DocumentSource.encode(doc)));

		assertThat(
			read.fields()[0],
			is(new Document.Value("location", new GeoPoint(59.325, 18.070), null))
		);
	}

	@Test
	public void testEncodedDocumentReadsBackTheSame() {
		var doc = new Document(
			new Document.Value("name", "Silent Spring"),
			new Document.Value("published", true),
			new Document.Value("tags", "nature"),
			new Document.Value("tags", "science"),
			new Document.Value("summary", "Vår tysta vår", "sv")
		);

		var read = DocumentSource.decode(new BytesRef(DocumentSource.encode(doc)));

		assertThat(read.fields().length, is(5));
		assertThat(read.fields()[0], is(new Document.Value("name", "Silent Spring", null)));
		assertThat(read.fields()[1], is(new Document.Value("published", true, null)));
		assertThat(read.fields()[2], is(new Document.Value("tags", "nature", null)));
		assertThat(read.fields()[3], is(new Document.Value("tags", "science", null)));
		assertThat(read.fields()[4], is(new Document.Value("summary", "Vår tysta vår", "sv")));
	}

	/**
	 * An index of two books where only `name` asks to be stored - and an object
	 * field, which can not ask at all - so that what a document comes back as
	 * says which of the two ways it was read.
	 *
	 * @param def
	 *   the definition to build on, which is where the source mode under test
	 *   is set
	 * @return
	 * @throws IOException
	 */
	private Index books(IndexDef.Builder def) throws IOException {
		var index = create("books", definition(def));

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Silent Spring"),
				new Document.Value("category", "non-fiction"),
				new Document.Value("tags", "nature"),
				new Document.Value("tags", "science"),
				new Document.Value("published", true),
				new Document.Value(
					"dimensions",
					new Document(new Document.Value("width", 12.5d))
				)
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Spring Cleaning"),
				new Document.Value("category", "fiction"),
				new Document.Value("published", false)
			)
		);

		index.commit();
		return index;
	}

	private static IndexDef.Builder definition(IndexDef.Builder def) {
		return def
			.putFields("id", string().setPrimaryKey(true).build())
			.putFields("name", string().setStored(true).build())
			.putFields(
				"dimensions",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setObject(
							ObjectFieldTypeDef.newBuilder().putFields(
								"width",
								FieldDef.newBuilder()
									.setType(
										FieldTypeDef.newBuilder().setDouble(
											DoubleFieldTypeDef.getDefaultInstance()
										)
									)
									.setFilter(FilterConfig.getDefaultInstance())
									.build()
							)
						)
					)
					.build()
			)
			.putFields(
				"category",
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
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setBoolean(BooleanFieldTypeDef.getDefaultInstance())
					)
					.setFilter(FilterConfig.getDefaultInstance())
					.build()
			);
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.newBuilder())
			);
	}

	private static List<Object> valuesOf(Document doc, String name) {
		var values = new ArrayList<>();
		for(var field : doc.fields()) {
			if(field.name().equals(name)) {
				values.add(field.value());
			}
		}
		return values;
	}
}
