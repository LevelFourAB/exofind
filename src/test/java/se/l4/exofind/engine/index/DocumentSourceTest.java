package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;

import se.l4.exofind.engine.index.source.SourceField;

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
	 * Turning it on is the direction that leaves documents behind, so it only
	 * goes through by saying the documents may go stale.
	 */
	@Test
	public void testDocumentsIndexedBeforeItWasTurnedOnStillRead() throws IOException {
		var index = books(IndexDef.newBuilder().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE));

		index.updateDefinition(
			definition(IndexDef.newBuilder().setSource(IndexDef.SourceMode.SOURCE_MODE_FULL))
				.build(),
			null,
			true
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
	 * is the only thing that could ever answer for one. A field inside it can
	 * be stored on its own though, so what is missing there is the field's
	 * setting rather than the copy.
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
			IndexFieldUsageException.class,
			() -> index.search(SearchRequest.create().withFields("dimensions.width").build())
		);

		assertThat(inside.getCode(), is("index:query:usage_not_enabled"));
	}

	/**
	 * A stored field below single objects answers without the copy, and comes
	 * back in the shape it was given in - rebuilt around the path, because
	 * only its own values were stored.
	 */
	@Test
	public void testAskingForAStoredFieldInsideObjectsIsAnsweredWithoutTheCopy() throws IOException {
		var index = create(
			"albums",
			IndexDef.newBuilder()
				.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"release",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"label",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setObject(
													ObjectFieldTypeDef.newBuilder().putFields(
														"name",
														string().setStored(true).build()
													)
												)
											)
											.build()
									)
									.putFields("year", string().setStored(true).build())
									.putFields(
										"note",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
							)
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value(
					"release",
					new Document(
						new Document.Value(
							"label",
							new Document(new Document.Value("name", "Verve"))
						),
						new Document.Value("year", "1964"),
						new Document.Value("note", "first pressing")
					)
				)
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withFields("release.label.name", "release.year")
				.build()
		);

		var release = (Document) result.hits().get(0).document().get("release");
		assertThat(release.get("year"), is("1964"));
		assertThat(release.get("note"), is(nullValue()));

		var label = (Document) release.get("label");
		assertThat(label.get("name"), is("Verve"));
	}

	/**
	 * Asking only for fields stored on their own is answered without reading
	 * the copy of the document, so the values - all of them, in order - have
	 * to come back the same as they would have from the copy.
	 */
	@Test
	public void testAskingForOnlyStoredFieldsIsAnsweredWithoutTheCopy() throws IOException {
		var index = create(
			"albums",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", string().setStored(true).build())
				.putFields(
					"artists",
					string().setMultiple(true).setStored(true).build()
				)
				.putFields(
					"category",
					string().setFilter(FilterConfig.getDefaultInstance()).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Getz/Gilberto"),
				new Document.Value("artists", "Stan Getz"),
				new Document.Value("artists", "João Gilberto"),
				new Document.Value("category", "jazz")
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("category", Matchers.equalTo("jazz")))
				.withFields("name", "artists")
				.build()
		);

		var document = result.hits().get(0).document();
		assertThat(document.get("name"), is("Getz/Gilberto"));
		assertThat(valuesOf(document, "artists"), contains("Stan Getz", "João Gilberto"));
		// The primary key always comes back, it is what a result is identified by
		assertThat(document.get("id"), is("1"));
	}

	/**
	 * A field that asked to be stored after a document was indexed is only in
	 * that document's copy, which asking for stored fields alone does not
	 * read - the value stays behind until the document is indexed again, which
	 * is why the change only goes through by saying the documents may go
	 * stale. Asking for no fields in particular still reads the copy and
	 * brings it back.
	 */
	@Test
	public void testFieldStoredAfterADocumentWasIndexedStaysInItsCopy() throws IOException {
		var index = books(IndexDef.newBuilder());

		index.updateDefinition(
			definition(IndexDef.newBuilder())
				.putFields(
					"category",
					string()
						.setStored(true)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
				.build(),
			null,
			true
		);

		var some = index.search(
			SearchRequest.create()
				.withQuery(Query.field("category", Matchers.equalTo("non-fiction")))
				.withFields("category")
				.build()
		);

		assertThat(some.hits().get(0).document().get("category"), is(nullValue()));

		var everything = index.search(
			SearchRequest.create()
				.withQuery(Query.field("category", Matchers.equalTo("non-fiction")))
				.build()
		);

		assertThat(everything.hits().get(0).document().get("category"), is("non-fiction"));
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

	/*
	 * The tests below hand the decoder bytes the encoder of this version never
	 * writes but a document on disk can hold: what a newer version keeps, what
	 * another protobuf writer lays out differently, and what corruption leaves
	 * behind. They build the bytes by hand because that is the only way to get
	 * them.
	 */

	/**
	 * A newer version can hold a value of a type this one has no case for.
	 * Nothing can say what it was, so the field is left out rather than
	 * guessed at - and the rest of the document stays readable.
	 */
	@Test
	public void testValueOfAnUnknownTypeIsLeftOut() throws IOException {
		var mystery = concat(
			SourceField.newBuilder().setName("mystery").build().toByteArray(),
			encoded(out -> out.writeBytes(15, ByteString.copyFromUtf8("later")))
		);

		var doc = encoded(out -> {
			out.writeBytes(1, ByteString.copyFrom(mystery));
			out.writeBytes(
				1,
				SourceField.newBuilder().setName("name").setString("Silent Spring").build()
					.toByteString()
			);
		});

		var read = DocumentSource.decode(new BytesRef(doc));

		assertThat(read.fields().length, is(1));
		assertThat(read.fields()[0], is(new Document.Value("name", "Silent Spring", null)));
	}

	/**
	 * A newer version can keep something else about a document beside its
	 * fields, and a field it keeps something else about may carry no name this
	 * version knows. Both are stepped over, and the fields stay readable.
	 */
	@Test
	public void testWhatANewerVersionKeepsIsSteppedOver() throws IOException {
		var doc = encoded(out -> {
			// Something else about the document
			out.writeInt32(2, 42);
			// A field that carries no name
			out.writeBytes(1, SourceField.newBuilder().setString("nameless").build().toByteString());
			out.writeBytes(
				1,
				SourceField.newBuilder().setName("name").setString("Silent Spring").build()
					.toByteString()
			);
		});

		var read = DocumentSource.decode(new BytesRef(doc));

		assertThat(read.fields().length, is(1));
		assertThat(read.fields()[0], is(new Document.Value("name", "Silent Spring", null)));
	}

	/**
	 * Protobuf lets a writer lay a repeated float out one tagged value at a
	 * time instead of as one packed run, and lets the parts of a message come
	 * in any order. This version's encoder does neither, but the bytes are
	 * legal, so reading them is part of keeping the format readable.
	 */
	@Test
	public void testUnpackedVectorAndReorderedFieldReadBack() throws IOException {
		var vector = encoded(out -> {
			out.writeFloat(1, 1.5f);
			out.writeFloat(1, -2f);
		});

		// The value ahead of the name
		var embedding = encoded(out -> {
			out.writeBytes(5, ByteString.copyFrom(vector));
			out.writeString(1, "embedding");
		});

		var doc = encoded(out -> out.writeBytes(1, ByteString.copyFrom(embedding)));

		var read = DocumentSource.decode(new BytesRef(doc));

		assertThat(read.fields().length, is(1));
		assertThat(read.fields()[0].name(), is("embedding"));
		assertThat(read.fields()[0].value(), is(new float[] { 1.5f, -2f }));

		var unwanted = DocumentSource.decode(new BytesRef(doc), name -> false);
		assertThat(unwanted.fields().length, is(0));
	}

	/**
	 * Bytes that stop before the length they declare, or hold no readable
	 * message at all, are refused as unreadable rather than answered with
	 * whatever part of them was read.
	 */
	@Test
	public void testCorruptBytesAreRefused() throws IOException {
		// A field whose declared length runs past the end of the bytes
		var truncated = encoded(out -> {
			out.writeTag(1, 2);
			out.writeUInt32NoTag(100);
		});

		var e = assertThrows(
			IndexException.class,
			() -> DocumentSource.decode(new BytesRef(truncated))
		);
		assertThat(e.getCode(), is("index:source:unreadable"));
	}

	/**
	 * Documents inside documents nest as far as a definition does, which is
	 * nowhere near this - bytes that go deeper are corrupt, and are refused
	 * before they can exhaust the stack.
	 */
	@Test
	public void testDocumentsNestedTooDeeplyAreRefused() throws IOException {
		var doc = encoded(out -> out.writeBytes(
			1,
			SourceField.newBuilder().setName("leaf").setString("x").build().toByteString()
		));

		for(var i = 0; i < 105; i++) {
			var inner = doc;
			var field = encoded(out -> {
				out.writeString(1, "child");
				out.writeBytes(11, ByteString.copyFrom(inner));
			});
			doc = encoded(out -> out.writeBytes(1, ByteString.copyFrom(field)));
		}

		var bytes = new BytesRef(doc);
		var e = assertThrows(
			IndexException.class,
			() -> DocumentSource.decode(bytes)
		);
		assertThat(e.getCode(), is("index:source:unreadable"));
	}

	private static byte[] encoded(Writer writer) throws IOException {
		var bytes = new ByteArrayOutputStream();
		var out = CodedOutputStream.newInstance(bytes);
		writer.write(out);
		out.flush();
		return bytes.toByteArray();
	}

	private static byte[] concat(byte[] a, byte[] b) {
		var result = new byte[a.length + b.length];
		System.arraycopy(a, 0, result, 0, a.length);
		System.arraycopy(b, 0, result, a.length, b.length);
		return result;
	}

	@FunctionalInterface
	private interface Writer {
		void write(CodedOutputStream out) throws IOException;
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
