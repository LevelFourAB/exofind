package se.l4.exofind.engine.api.v1alpha1.documents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;

import se.l4.exofind.engine.CustomProviders;
import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.api.v1alpha1.FreshnessTokens;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentsRequest;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.freshness.TestFreshnessWaiters;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexInvalidQueryValueException;
import se.l4.exofind.engine.index.IndexNoPrimaryKeyException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int64FieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.reindex.TestReindexJobs;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * Tests for indexing one document under the primary key in the URL path - what
 * the path supplies that the body can leave out, what a body naming another
 * document answers with, and that the write replaces whatever the key held.
 */
public class DocumentPutResourceTest {
	@TempDir
	Path storageDirectory;

	Indexes indexes;
	DocumentResource resource;

	@BeforeEach
	void setup() throws IOException {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		var registry = new IndexRegistry(
			new LocalRegistryStorage(storageDirectory.resolve("registry.ef.bin")),
			Duration.ofMinutes(5)
		);

		indexes = new Indexes(
			nodeState,
			new NoopSyncProvider(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			storageDirectory,
			OptionalInt.empty(),
			Duration.ofMinutes(5),
			Duration.ofMinutes(10),
			4,
			Duration.ofSeconds(10),
			0,
			Duration.ZERO,
			Optional.empty(),
			Optional.empty(),
			Duration.ofHours(24),
			Duration.ofHours(168),
			Duration.ofHours(1)
		);

		resource = new DocumentResource(
			indexes,
			new CustomProviders().objectMapper(),
			TestReindexJobs.create(nodeState, indexes, registry, storageDirectory),
			TestFreshnessWaiters.create(indexes, registry)
		);
	}

	@AfterEach
	void cleanup() {
		indexes.close();
	}

	/**
	 * The path names the document, so the body holds the rest of it.
	 */
	@Test
	public void aBodyWithoutTheKeyFieldIsIndexedUnderTheKeyInThePath() throws IOException {
		var index = catalogue();

		resource.put("catalogue", "4", document("name", "Crispbread", "energy", 340.0));

		index.commit();

		var stored = index.getDocument("4");
		assertThat(stored.get("id"), is("4"));
		assertThat(stored.get("name"), is("Crispbread"));
		assertThat(stored.get("energy"), is(340.0));
	}

	@Test
	public void aKeyThatHoldsADocumentAlreadyIsReplacedWhole() throws IOException {
		var index = catalogue();

		resource.put("catalogue", "1", document("name", "Cloudberry jam"));

		index.commit();

		var stored = index.getDocument("1");
		assertThat(stored.get("name"), is("Cloudberry jam"));

		// The document went in whole, so a field the body left out is gone
		assertThat(stored.get("energy"), is(nullValue()));
	}

	@Test
	public void theSameRequestSentTwiceLeavesTheSameDocument() throws IOException {
		var index = catalogue();

		resource.put("catalogue", "4", document("name", "Crispbread", "energy", 340.0));
		resource.put("catalogue", "4", document("name", "Crispbread", "energy", 340.0));

		index.commit();

		assertThat(index.getDocument("4").get("name"), is("Crispbread"));
		assertThat(index.scanDocuments(null, 100, d -> {}), is(4));
	}

	@Test
	public void theStateTheWriteLandsInComesBackInTheHeader() throws IOException {
		catalogue();

		var response = resource.put("catalogue", "4", document("name", "Crispbread"));

		assertThat(response.getStatus(), is(204));
		assertThat(response.getHeaderString(FreshnessTokens.HEADER), is(notNullValue()));
	}

	@Test
	public void aBodyRepeatingTheKeyThePathNamesIsAccepted() throws IOException {
		var index = catalogue();

		resource.put("catalogue", "4", document("id", "4", "name", "Crispbread"));

		index.commit();
		assertThat(index.getDocument("4").get("name"), is("Crispbread"));
	}

	@Test
	public void aBodyNamingAnotherDocumentThanThePathIsRefused() throws IOException {
		var index = catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.put("catalogue", "4", document("id", "5", "name", "Crispbread"))
		);

		assertThat(e.getErrors().get(0).getCode(), is("document:key_conflicting"));

		index.commit();
		assertThat(index.getDocument("4"), is(nullValue()));
		assertThat(index.getDocument("5"), is(nullValue()));
	}

	/**
	 * The key arrives as text whatever the key field holds, the same way the
	 * key in the path of a delete does.
	 */
	@Test
	public void aWholeNumberKeyIsReadFromThePathAsText() throws IOException {
		var index = orders();

		resource.put("orders", "4", document("total", 12.5));

		index.commit();

		var stored = index.getDocument(4L);
		assertThat(stored.get("id"), is(4L));
		assertThat(stored.get("total"), is(12.5));
	}

	/**
	 * The path supplies the key, so a body repeating it is read as naming a
	 * document, not as a value of the key field. A whole number written as
	 * text still names the document the path names.
	 */
	@Test
	public void aWholeNumberKeyRepeatedAsTextNamesTheSameDocument() throws IOException {
		var index = orders();

		resource.put("orders", "4", document("id", "4", "total", 12.5));

		index.commit();
		assertThat(index.getDocument(4L).get("id"), is(4L));
	}

	@Test
	public void aKeyTheIndexCannotReadIsRefused() throws IOException {
		orders();

		assertThrows(
			IndexInvalidQueryValueException.class,
			() -> resource.put("orders", "not-a-number", document("total", 12.5))
		);
	}

	@Test
	public void anIndexWithoutAPrimaryKeyIsRefused() throws IOException {
		indexes.create(
			"logs",
			IndexDef.newBuilder().putFields("message", string().build()).build()
		);

		assertThrows(
			IndexNoPrimaryKeyException.class,
			() -> resource.put("logs", "1", document("message", "started"))
		);
	}

	/**
	 * Indexing needs no copy of the document, so an index that keeps none
	 * takes a whole document the way it takes one in a batch.
	 */
	@Test
	public void anIndexThatKeepsNoCopiesTakesTheDocument() throws IOException {
		var index = indexes.create(
			"logs",
			IndexDef.newBuilder()
				.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
				.putFields("id", string().setPrimaryKey(true).setStored(true).build())
				.putFields("message", string().setStored(true).build())
				.build()
		);

		resource.put("logs", "1", document("message", "started"));

		index.commit();
		assertThat(index.getDocument("1").get("message"), is("started"));
	}

	@Test
	public void aFieldTheIndexDoesNotHaveIsRefused() throws IOException {
		var index = catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.put("catalogue", "4", document("colour", "blue"))
		);

		assertThat(e.getErrors().get(0).getCode(), is("document:field_unknown"));

		index.commit();
		assertThat(index.getDocument("4"), is(nullValue()));
	}

	@Test
	public void aRequestWithNoBodyIsRefused() throws IOException {
		catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.put("catalogue", "4", null)
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:body_required"));
	}

	/**
	 * What a read answers goes back in under the key it came from, so one
	 * document can be copied from one index to another.
	 */
	@Test
	public void whatAReadAnswersGoesBackInUnderItsKey() throws IOException {
		catalogue();

		var read = resource.read("catalogue", "1").document();

		var copy = indexes.create("catalogue-2", definition().build());
		resource.put("catalogue-2", "1", asJson(read));
		copy.commit();

		var written = resource.read("catalogue-2", "1").document();
		assertThat(written.get("name"), is(read.get("name")));
		assertThat(written.get("energy"), is(read.get("energy")));
	}

	/**
	 * Read a document as the JSON a write takes, by way of the serializer the
	 * API answers with.
	 */
	private static Map<String, Object> asJson(Document document) throws IOException {
		var mapper = new CustomProviders().objectMapper();

		return mapper.readValue(
			mapper.writeValueAsString(document),
			new TypeReference<Map<String, Object>>() {}
		);
	}

	private static Map<String, Object> document(Object... keysAndValues) {
		var result = new LinkedHashMap<String, Object>();
		for(var i = 0; i < keysAndValues.length; i += 2) {
			result.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}

		return result;
	}

	/**
	 * An index of foods holding three documents, committed so that a write
	 * over one of them can be told apart from the documents around it.
	 */
	private Index catalogue() throws IOException {
		var index = indexes.create("catalogue", definition().build());

		resource.add(
			"catalogue",
			null,
			new DocumentsRequest(
				List.of(
					document("id", "1", "name", "Blueberry jam", "energy", 234.5),
					document("id", "2", "name", "Rye bread", "energy", 217.0),
					document("id", "3", "name", "Lingonberry jam", "energy", 198.0)
				)
			)
		);

		index.commit();

		return index;
	}

	/**
	 * An index keyed by a whole number, holding three documents.
	 */
	private Index orders() throws IOException {
		var index = indexes.create(
			"orders",
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setPrimaryKey(true)
						.setType(
							FieldTypeDef.newBuilder()
								.setInt64(Int64FieldTypeDef.getDefaultInstance())
						)
						.build()
				)
				.putFields(
					"total",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setDouble(DoubleFieldTypeDef.getDefaultInstance())
						)
						.build()
				)
				.build()
		);

		resource.add(
			"orders",
			null,
			new DocumentsRequest(
				List.of(document("id", 1), document("id", 2), document("id", 3))
			)
		);

		index.commit();

		return index;
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build())
			.putFields("name", string().build())
			.putFields(
				"energy",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setDouble(DoubleFieldTypeDef.getDefaultInstance())
					)
					.build()
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
