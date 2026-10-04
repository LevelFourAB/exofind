package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.Int64FieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.VectorFieldTypeDef;

/**
 * Tests for definition changes that give a field Lucene has written another
 * shape. Lucene refuses every later document that writes such a field the new
 * way, so these are refused also when stale documents are allowed and when
 * every document was removed.
 */
public class IndexDefinitionReshapeTest extends AbstractIndexTest {
	@Test
	public void aStaleTypeChangeOfAWrittenFieldIsRefused() throws IOException {
		var index = create(withField("n", int32()));
		index.addDocument(document("1", "n", 5));
		index.commit();

		var e = assertThrows(
			IndexDefinitionIncompatibleException.class,
			() -> index.updateDefinition(withField("n", int64()).build(), null, true)
		);

		assertThat(settings(e), contains("type"));

		// The definition is left as it was, so the documents can be sent again
		index.addDocument(document("1", "n", 6));
		index.commit();
		assertThat(index.getDocument("1").get("n"), is(6));
	}

	@Test
	public void aStaleChangeOfVectorDimensionsIsRefused() throws IOException {
		var index = create(withField("v", vector(2)));
		index.addDocument(document("1", "v", new float[] { 1f, 0f }));
		index.commit();

		var e = assertThrows(
			IndexDefinitionIncompatibleException.class,
			() -> index.updateDefinition(withField("v", vector(3)).build(), null, true)
		);

		assertThat(settings(e), contains("dimensions"));
	}

	@Test
	public void aStaleChangeTurningOnHighlightIsRefused() throws IOException {
		var index = create(withField("name", matching(false)));
		index.addDocument(document("1", "name", "red shoes"));
		index.commit();

		var e = assertThrows(
			IndexDefinitionIncompatibleException.class,
			() -> index.updateDefinition(withField("name", matching(true)).build(), null, true)
		);

		assertThat(
			e.getErrors().collect(error -> error.getArguments().get("usage")).toList(),
			contains("matching.highlight")
		);
	}

	/**
	 * Lucene keeps the shape of a field after every document holding it was
	 * removed, so a generation emptied by removals is not a new one.
	 */
	@Test
	public void aTypeChangeOfAFieldWrittenBeforeEveryDocumentWasRemovedIsRefused()
		throws IOException {
		var index = create(withField("n", int32()));
		index.addDocument(document("1", "n", 5));
		index.commit();
		index.deleteDocument("1");
		index.commit();

		var e = assertThrows(
			IndexDefinitionIncompatibleException.class,
			() -> index.updateDefinition(withField("n", int64()).build())
		);

		assertThat(settings(e), contains("type"));

		index.addDocument(document("2", "n", 7));
		index.commit();
		assertThat(index.getDocument("2").get("n"), is(7));
	}

	/**
	 * A pattern reaches the fields written under the names it accepts.
	 */
	@Test
	public void aStaleTypeChangeOfAPatternWhoseNamesWereWrittenIsRefused() throws IOException {
		var index = create(withField("size_*", int32()));
		index.addDocument(document("1", "size_eu", 42));
		index.commit();

		var e = assertThrows(
			IndexDefinitionIncompatibleException.class,
			() -> index.updateDefinition(withField("size_*", int64()).build(), null, true)
		);

		assertThat(settings(e), contains("type"));
	}

	/**
	 * Lucene holds no shape for a field no document gave a value, so its type
	 * can change in place.
	 */
	@Test
	public void aStaleTypeChangeOfAFieldNothingWroteIsAccepted() throws IOException {
		var index = create(withField("n", int32()));
		index.addDocument(new Document(new Document.Value("id", "1")));
		index.commit();

		index.updateDefinition(withField("n", int64()).build(), null, true);

		index.addDocument(document("2", "n", 7L));
		index.commit();
		assertThat(index.getDocument("2").get("n"), is(7L));
	}

	/**
	 * Quantization decides how the codec stores vectors, not the shape Lucene
	 * keeps for the field, so the documents can be sent again after it.
	 */
	@Test
	public void aStaleChangeOfQuantizationLetsTheDocumentsBeSentAgain() throws IOException {
		var index = create(withField("v", vector(2)));
		index.addDocument(document("1", "v", new float[] { 1f, 0f }));
		index.commit();

		var quantized = FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setVector(
						VectorFieldTypeDef.newBuilder()
							.setDimensions(2)
							.setQuantization(VectorFieldTypeDef.Quantization.QUANTIZATION_INT8)
					)
			);
		index.updateDefinition(withField("v", quantized).build(), null, true);

		index.addDocument(document("1", "v", new float[] { 0f, 1f }));
		index.commit();
		assertThat(index.getDocumentCount(), is(1L));
	}

	private static List<Object> settings(IndexDefinitionIncompatibleException e) {
		return e.getErrors().collect(error -> error.getArguments().get("setting")).toList();
	}

	private static Document document(String id, String field, Object value) {
		return new Document(new Document.Value("id", id), new Document.Value(field, value));
	}

	private static IndexDef.Builder withField(String name, FieldDef.Builder field) {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				FieldDef.newBuilder()
					.setType(FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance()))
					.setPrimaryKey(true)
					.build()
			)
			.putFields(name, field.build());
	}

	private static FieldDef.Builder int32() {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setInt32(Int32FieldTypeDef.getDefaultInstance()))
			.setFilter(FilterConfig.getDefaultInstance());
	}

	private static FieldDef.Builder int64() {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setInt64(Int64FieldTypeDef.getDefaultInstance()))
			.setFilter(FilterConfig.getDefaultInstance());
	}

	private static FieldDef.Builder vector(int dimensions) {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setVector(VectorFieldTypeDef.newBuilder().setDimensions(dimensions))
			);
	}

	private static FieldDef.Builder matching(boolean highlight) {
		var matching = StringFieldTypeDef.TextUsageConfig.newBuilder();
		if(highlight) {
			matching.setHighlight(
				StringFieldTypeDef.TextUsageConfig.HighlightConfig.getDefaultInstance()
			);
		}

		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setString(StringFieldTypeDef.newBuilder().setMatching(matching))
			);
	}
}
