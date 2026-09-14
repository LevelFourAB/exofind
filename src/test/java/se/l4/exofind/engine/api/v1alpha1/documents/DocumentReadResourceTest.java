package se.l4.exofind.engine.api.v1alpha1.documents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentsRequest;
import se.l4.exofind.engine.freshness.TestFreshnessWaiters;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexDocumentNotFoundException;
import se.l4.exofind.engine.index.IndexInvalidQueryValueException;
import se.l4.exofind.engine.index.IndexNoPrimaryKeyException;
import se.l4.exofind.engine.index.IndexSourceNotKeptException;
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
 * Tests for reading one document back out of an index by its primary key over
 * the API - what comes back under a key, what a key nothing is indexed under
 * answers with, and that the document goes back into the indexing endpoint as
 * it came.
 */
public class DocumentReadResourceTest {
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

	@Test
	public void aDocumentComesBackAsItWasGiven() throws IOException {
		catalogue();

		var read = resource.read("catalogue", "2").document();

		assertThat(read.get("id"), is("2"));
		assertThat(read.get("name"), is("Rye bread"));
		assertThat(read.get("energy"), is(217.0));
	}

	@Test
	public void theStateTheDocumentWasReadFromComesBackWithIt() throws IOException {
		catalogue();

		assertThat(resource.read("catalogue", "1").freshness(), is(notNullValue()));
	}

	@Test
	public void aKeyNothingIsIndexedUnderIsRefused() throws IOException {
		catalogue();

		assertThrows(
			IndexDocumentNotFoundException.class,
			() -> resource.read("catalogue", "4")
		);
	}

	/**
	 * A read is answered from the last commit, so a document indexed since then
	 * is not there to read yet.
	 */
	@Test
	public void aDocumentIndexedSinceTheLastCommitIsNotThereYet() throws IOException {
		catalogue();

		resource.add(
			"catalogue",
			null,
			new DocumentsRequest(List.of(document("id", "4", "name", "Crispbread")))
		);

		assertThrows(
			IndexDocumentNotFoundException.class,
			() -> resource.read("catalogue", "4")
		);
	}

	/**
	 * A key arrives as text whatever the key field holds, the same way the key
	 * in the path of a delete does.
	 */
	@Test
	public void aWholeNumberKeyIsReadAsText() throws IOException {
		orders();

		assertThat(resource.read("orders", "2").document().get("id"), is(2));
	}

	@Test
	public void aKeyTheIndexCannotReadIsRefused() throws IOException {
		orders();

		assertThrows(
			IndexInvalidQueryValueException.class,
			() -> resource.read("orders", "not-a-number")
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
			() -> resource.read("logs", "1")
		);
	}

	@Test
	public void anIndexThatKeepsNoCopiesIsRefused() throws IOException {
		indexes.create(
			"logs",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
				.build()
		);

		assertThrows(
			IndexSourceNotKeptException.class,
			() -> resource.read("logs", "1")
		);
	}

	/**
	 * What a read answers is what the indexing endpoint takes back in, so a
	 * document can be copied from one index to another without the system it
	 * came from.
	 */
	@Test
	public void whatTheReadAnswersIsWhatTheIndexTakesBackIn() throws IOException {
		catalogue();

		var read = resource.read("catalogue", "1").document();

		var copy = indexes.create("catalogue-2", definition().build());
		var indexed = resource.add(
			"catalogue-2",
			null,
			new DocumentsRequest(List.of(asJson(read)))
		);
		copy.commit();

		assertThat(indexed.indexed(), is(1));

		var written = resource.read("catalogue-2", "1").document();
		assertThat(written.get("name"), is(read.get("name")));
		assertThat(written.get("energy"), is(read.get("energy")));
	}

	@Test
	public void aDocumentRemovedSinceTheLastCommitIsStillRead() throws IOException {
		var index = catalogue();

		resource.delete("catalogue", "1");

		assertThat(resource.read("catalogue", "1").document().get("name"), is("Blueberry jam"));

		index.commit();

		assertThrows(
			IndexDocumentNotFoundException.class,
			() -> resource.read("catalogue", "1")
		);
	}

	/**
	 * Read a document as the JSON the indexing endpoint takes, by way of the
	 * serializer the API answers with.
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
	 * An index of foods holding three documents, committed so that they can be
	 * read back.
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
