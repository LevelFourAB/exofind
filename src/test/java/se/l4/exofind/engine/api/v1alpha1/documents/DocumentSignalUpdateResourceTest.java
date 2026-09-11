package se.l4.exofind.engine.api.v1alpha1.documents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.is;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentsRequest;
import se.l4.exofind.engine.api.v1alpha1.documents.model.UpdateRequest;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexSourceNotKeptException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.SignalConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.reindex.TestReindexJobs;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * Tests for refreshing a signal field over the API - the same update action
 * as any other partial change, taking the in-place path when a change names
 * nothing but signal fields.
 */
public class DocumentSignalUpdateResourceTest {
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
			new ObjectMapper(),
			TestReindexJobs.create(nodeState, indexes, registry, storageDirectory)
		);
	}

	@AfterEach
	void cleanup() {
		indexes.close();
	}

	@Test
	public void aChangeNamingOnlyTheSignalRefreshesItInPlace() throws IOException {
		var index = catalogue(IndexDef.SourceMode.SOURCE_MODE_FULL);

		var response = resource.update(
			"catalogue",
			null,
			new UpdateRequest(List.of(document("id", "1", "popularity", 0.8)))
		);

		assertThat(response.updated(), is(1));
		assertThat(response.missing(), is(emptyIterable()));

		index.commit();

		var stored = index.getDocument("1");
		assertThat(stored.get("popularity"), is(0.8));
		assertThat(stored.get("name"), is("Blueberry jam"));
	}

	@Test
	public void aSignalWrittenAsNullIsEmptied() throws IOException {
		var index = catalogue(IndexDef.SourceMode.SOURCE_MODE_FULL);

		resource.update(
			"catalogue",
			null,
			new UpdateRequest(List.of(document("id", "1", "popularity", null)))
		);

		index.commit();

		assertThat(index.getDocument("1").get("popularity"), is(nullValue()));
		assertThat(index.getDocument("1").get("name"), is("Blueberry jam"));
	}

	/**
	 * A refresh needs no copy of the document, so an index that keeps none
	 * refreshes its signals while it refuses every other partial change.
	 */
	@Test
	public void anIndexKeepingNoCopyRefreshesSignalsAndRefusesTheRest() throws IOException {
		var index = catalogue(IndexDef.SourceMode.SOURCE_MODE_NONE);

		var response = resource.update(
			"catalogue",
			"skip",
			new UpdateRequest(
				List.of(
					document("id", "1", "popularity", 0.8),
					document("id", "404", "popularity", 0.1)
				)
			)
		);

		assertThat(response.updated(), is(1));
		assertThat(response.missing(), contains("404"));

		assertThrows(
			IndexSourceNotKeptException.class,
			() -> resource.update(
				"catalogue",
				null,
				new UpdateRequest(List.of(document("id", "1", "name", "Cloudberry jam")))
			)
		);

		index.commit();
		assertThat(index.getDocument("1").get("popularity"), is(0.8));
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

	private Index catalogue(IndexDef.SourceMode source) throws IOException {
		var index = indexes.create(
			"catalogue",
			IndexDef.newBuilder()
				.setSource(source)
				.putFields("id", string().setPrimaryKey(true).setStored(true).build())
				.putFields("name", string().setStored(true).build())
				.putFields(
					"popularity",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setDouble(DoubleFieldTypeDef.getDefaultInstance())
						)
						.setSignal(SignalConfig.getDefaultInstance())
						.build()
				)
				.build()
		);

		resource.add(
			"catalogue",
			new DocumentsRequest(
				List.of(
					document("id", "1", "name", "Blueberry jam", "popularity", 0.2),
					document("id", "2", "name", "Rye bread", "popularity", 0.5)
				)
			)
		);

		return index;
	}
}
