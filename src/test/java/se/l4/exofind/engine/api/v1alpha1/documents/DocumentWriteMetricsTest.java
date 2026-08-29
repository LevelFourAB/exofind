package se.l4.exofind.engine.api.v1alpha1.documents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DeleteRequest;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentsRequest;
import se.l4.exofind.engine.api.v1alpha1.documents.model.UpdateRequest;
import se.l4.exofind.engine.api.v1alpha1.search.model.Clause;
import se.l4.exofind.engine.api.v1alpha1.search.model.Matcher;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexDocumentNotFoundException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.metrics.Meters;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.reindex.TestReindexJobs;
import se.l4.exofind.engine.storage.StorageMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Tests for what the write endpoints report to {@code exofind.write} - that
 * every operation is named, that the two forms of one request share a name, and
 * that a request counts the documents it covered.
 */
public class DocumentWriteMetricsTest {
	@TempDir
	Path storageDirectory;

	SimpleMeterRegistry registry;
	Indexes indexes;
	DocumentResource resource;

	@BeforeEach
	void setup() throws IOException {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		var indexRegistry = new IndexRegistry(
			new LocalRegistryStorage(storageDirectory.resolve("registry.ef.bin")),
			Duration.ofMinutes(5)
		);

		indexes = new Indexes(
			nodeState,
			new NoopSyncProvider(),
			indexRegistry,
			new RegistryHints(indexRegistry, StorageMode.LOCAL),
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

		registry = new SimpleMeterRegistry();

		resource = new DocumentResource(
			indexes,
			new ObjectMapper(),
			TestReindexJobs.create(nodeState, indexes, indexRegistry, storageDirectory),
			new RequestMetrics(registry, false)
		);
	}

	@AfterEach
	void cleanup() {
		indexes.close();
		registry.close();
	}

	@Test
	public void indexingReportsTheAddOperation() throws IOException {
		catalogue();

		assertThat(writes("add", Meters.OUTCOME_SUCCESS), is(1L));
		assertThat(documents("add"), is(2.0));
	}

	@Test
	public void newlineDelimitedIndexingSharesTheOperationOfTheJsonForm() throws IOException {
		catalogue();

		resource.addStream("catalogue", ndjson("""
			{"id": "3", "name": "Oat milk", "category": "dairy", "price": 18.0}
			{"id": "4", "name": "Butter", "category": "dairy", "price": 42.0}
			"""));

		assertThat(writes("add", Meters.OUTCOME_SUCCESS), is(2L));
		assertThat(documents("add"), is(4.0));
	}

	@Test
	public void updatingReportsTheUpdateOperationAndTheDocumentsItChanged() throws IOException {
		catalogue();

		resource.update(
			"catalogue",
			null,
			new UpdateRequest(
				List.of(document("id", "1", "price", 9.5), document("id", "2", "price", 8.0))
			)
		);

		assertThat(writes("update", Meters.OUTCOME_SUCCESS), is(1L));
		assertThat(documents("update"), is(2.0));
	}

	@Test
	public void changingOneDocumentReportsTheUpdateOperation() throws IOException {
		catalogue();

		resource.patch("catalogue", "1", document("price", 9.5));

		assertThat(writes("update", Meters.OUTCOME_SUCCESS), is(1L));
		assertThat(documents("update"), is(1.0));
	}

	@Test
	public void removingOneDocumentReportsTheDeleteOperation() throws IOException {
		catalogue();

		resource.delete("catalogue", "1");

		assertThat(writes("delete", Meters.OUTCOME_SUCCESS), is(1L));
		assertThat(documents("delete"), is(1.0));
	}

	@Test
	public void removingByKeysCountsTheKeysTheRequestCarried() throws IOException {
		var index = catalogue();
		index.commit();

		resource.delete("catalogue", new DeleteRequest(List.of("1", "2"), null, null));

		assertThat(writes("delete", Meters.OUTCOME_SUCCESS), is(1L));
		assertThat(documents("delete"), is(2.0));
	}

	@Test
	public void removingByQueryReportsAnOperationOfItsOwn() throws IOException {
		var index = catalogue();
		index.commit();

		resource.delete(
			"catalogue",
			new DeleteRequest(
				null,
				List.of(new Clause.Field("category", new Matcher.Equals("bread"))),
				null
			)
		);

		assertThat(writes("delete_by_query", Meters.OUTCOME_SUCCESS), is(1L));
		assertThat(documents("delete_by_query"), is(1.0));
	}

	@Test
	public void aWriteThatFailedIsReportedAsAnErrorCoveringNoDocuments() throws IOException {
		catalogue();

		assertThrows(
			IndexDocumentNotFoundException.class,
			() -> resource.patch("catalogue", "404", document("price", 1.0))
		);

		assertThat(writes("update", Meters.OUTCOME_ERROR), is(1L));
		assertThat(
			registry.find(Meters.WRITE_DOCUMENTS)
				.tag(Meters.TAG_OPERATION, "update")
				.counter(),
			is(nullValue())
		);
	}

	/**
	 * How many write requests were reported under an operation and an outcome.
	 */
	private long writes(String operation, String outcome) {
		return registry.get(Meters.WRITE)
			.tag(Meters.TAG_OPERATION, operation)
			.tag(Meters.TAG_OUTCOME, outcome)
			.timer()
			.count();
	}

	/**
	 * How many documents were reported under an operation.
	 */
	private double documents(String operation) {
		return registry.get(Meters.WRITE_DOCUMENTS)
			.tag(Meters.TAG_OPERATION, operation)
			.counter()
			.count();
	}

	private static ByteArrayInputStream ndjson(String body) {
		return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String, Object> document(Object... keysAndValues) {
		var result = new LinkedHashMap<String, Object>();
		for(var i = 0; i < keysAndValues.length; i += 2) {
			result.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}

		return result;
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	/**
	 * An index of two products, indexed through the endpoint so that the add
	 * operation has been reported once by the time a test starts.
	 */
	private Index catalogue() throws IOException {
		var index = indexes.create(
			"catalogue",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", string().build())
				.putFields(
					"category",
					string().setFilter(FilterConfig.getDefaultInstance()).build()
				)
				.putFields(
					"price",
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
			"catalogue",
			new DocumentsRequest(
				List.of(
					document("id", "1", "name", "Blueberry jam", "category", "preserves", "price", 24.5),
					document("id", "2", "name", "Rye bread", "category", "bread", "price", 12.0)
				)
			)
		);

		return index;
	}
}
