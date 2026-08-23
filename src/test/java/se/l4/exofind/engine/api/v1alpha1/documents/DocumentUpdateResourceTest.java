package se.l4.exofind.engine.api.v1alpha1.documents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyIterable;
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
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentsRequest;
import se.l4.exofind.engine.api.v1alpha1.documents.model.UpdateRequest;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexSourceNotKeptException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;

/**
 * Tests for changing some of the fields of a document over the API - what the
 * shape of a change means, and what comes back for a key nothing is indexed
 * under.
 */
public class DocumentUpdateResourceTest {
	@TempDir
	Path storageDirectory;

	Indexes indexes;
	DocumentResource resource;

	@BeforeEach
	void setup() throws IOException {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		indexes = new Indexes(
			nodeState,
			new NoopSyncProvider(),
			new IndexRegistry(
				new LocalRegistryStorage(storageDirectory.resolve("registry.ef.bin")),
				Duration.ofMinutes(5)
			),
			storageDirectory,
			OptionalInt.empty(),
			Duration.ofMinutes(5),
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

		resource = new DocumentResource(indexes, new ObjectMapper());
	}

	@AfterEach
	void cleanup() {
		indexes.close();
	}

	@Test
	public void aChangeReplacesTheFieldsItNamesAndLeavesTheRest() throws IOException {
		var index = catalogue();

		var response = resource.update(
			"catalogue",
			null,
			new UpdateRequest(List.of(document("id", "1", "price", 9.5)))
		);

		assertThat(response.updated(), is(1));
		assertThat(response.missing(), is(emptyIterable()));

		index.commit();

		var stored = index.getDocument("1");
		assertThat(stored.get("price"), is(9.5));
		assertThat(stored.get("name"), is("Blueberry jam"));
	}

	/**
	 * The one place a field written as null empties the field rather than being
	 * a field that was not given.
	 */
	@Test
	public void aFieldWrittenAsNullIsEmptied() throws IOException {
		var index = catalogue();

		resource.update(
			"catalogue",
			null,
			new UpdateRequest(List.of(document("id", "1", "price", null)))
		);

		index.commit();

		assertThat(index.getDocument("1").get("price"), is(nullValue()));
		assertThat(index.getDocument("1").get("name"), is("Blueberry jam"));
	}

	@Test
	public void changesAreReadAsTheyComeFromNewlineDelimitedJson() throws IOException {
		var index = catalogue();

		var body = """
			{"id": "1", "price": 1.5}
			{"id": "2", "price": 2.5}
			""";

		var response = resource.updateStream(
			"catalogue",
			null,
			new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
		);

		assertThat(response.updated(), is(2));

		index.commit();
		assertThat(index.getDocument("1").get("price"), is(1.5));
		assertThat(index.getDocument("2").get("price"), is(2.5));
	}

	@Test
	public void aKeyNothingIsIndexedUnderFailsTheRequest() throws IOException {
		catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.update(
				"catalogue",
				null,
				new UpdateRequest(List.of(document("id", "404", "price", 1.0)))
			)
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:update:not_found"));
	}

	@Test
	public void askingToSkipMissingKeysAppliesTheRestAndNamesThem() throws IOException {
		var index = catalogue();

		var response = resource.update(
			"catalogue",
			"skip",
			new UpdateRequest(
				List.of(
					document("id", "1", "price", 5.0),
					document("id", "404", "price", 1.0),
					document("id", "2", "price", 6.0)
				)
			)
		);

		assertThat(response.updated(), is(2));
		assertThat(response.missing(), contains("404"));

		index.commit();
		assertThat(index.getDocument("1").get("price"), is(5.0));
		assertThat(index.getDocument("2").get("price"), is(6.0));
	}

	@Test
	public void aWayOfHandlingMissingKeysThatDoesNotExistIsRefused() throws IOException {
		catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.update(
				"catalogue",
				"upsert",
				new UpdateRequest(List.of(document("id", "1", "price", 1.0)))
			)
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:update:missing_unknown"));
	}

	@Test
	public void aChangeThatLeavesTheDocumentUnacceptableSaysWhereInTheRequestItSat()
		throws IOException {
		catalogue();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.update(
				"catalogue",
				null,
				new UpdateRequest(
					List.of(
						document("id", "1", "price", 1.0),
						document("id", "2", "price", "not a number")
					)
				)
			)
		);

		assertThat(e.getErrors().get(0).getLocation().describe(), is("documents[1].price"));
	}

	@Test
	public void anIndexThatKeepsNoCopyOfItsDocumentsIsRefused() throws IOException {
		indexes.create(
			"sourceless",
			IndexDef.newBuilder()
				.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", string().build())
				.build()
		);

		resource.add("sourceless", new DocumentsRequest(List.of(document("id", "1"))));

		assertThrows(
			IndexSourceNotKeptException.class,
			() -> resource.update(
				"sourceless",
				null,
				new UpdateRequest(List.of(document("id", "1", "name", "jam")))
			)
		);
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
