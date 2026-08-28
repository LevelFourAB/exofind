package se.l4.exofind.engine.api.v1alpha1.documents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

import se.l4.exofind.engine.CustomProviders;
import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentsRequest;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexNoPrimaryKeyException;
import se.l4.exofind.engine.index.IndexSourceNotKeptException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.reindex.TestReindexJobs;
import jakarta.ws.rs.core.StreamingOutput;

/**
 * Tests for reading documents back out of an index over the API - the parts a
 * caller reads them in, the key each part says to carry on after, and that
 * what comes back as newline delimited JSON is what the same endpoint takes
 * back in.
 */
public class DocumentScanResourceTest {
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

		resource = new DocumentResource(
			indexes,
			new CustomProviders().objectMapper(),
			TestReindexJobs.create(nodeState, indexes, registry, storageDirectory)
		);
	}

	@AfterEach
	void cleanup() {
		indexes.close();
	}

	@Test
	public void everyDocumentComesBackInKeyOrder() throws IOException {
		catalogue();

		var response = resource.scan("catalogue", null, null);

		assertThat(keysOf(response.documents()), contains("1", "2", "3"));
		assertThat(response.next(), is(nullValue()));
	}

	@Test
	public void aDocumentComesBackAsItWasGiven() throws IOException {
		catalogue();

		var read = resource.scan("catalogue", "1", "1").documents().get(0);

		assertThat(read.get("id"), is("2"));
		assertThat(read.get("name"), is("Rye bread"));
		assertThat(read.get("energy"), is(217.0));
	}

	@Test
	public void afullPartSaysWhichKeyToCarryOnAfter() throws IOException {
		catalogue();

		var response = resource.scan("catalogue", null, "2");

		assertThat(keysOf(response.documents()), contains("1", "2"));
		assertThat(response.next(), is("2"));
	}

	@Test
	public void thePartAfterTheLastKeyIsEmpty() throws IOException {
		catalogue();

		var response = resource.scan("catalogue", "3", null);

		assertThat(response.documents(), is(empty()));
		assertThat(response.next(), is(nullValue()));
	}

	@Test
	public void oneRequestPerPartReadsTheIndexOnce() throws IOException {
		catalogue();

		var first = resource.scan("catalogue", null, "2");
		var second = resource.scan("catalogue", first.next(), "2");

		assertThat(keysOf(first.documents()), contains("1", "2"));
		assertThat(keysOf(second.documents()), contains("3"));
		assertThat(second.next(), is(nullValue()));
	}

	@Test
	public void aLimitThatIsNotAWholeNumberInRangeIsRefused() throws IOException {
		catalogue();

		assertThrows(
			ValidationException.class,
			() -> resource.scan("catalogue", null, "many")
		);

		assertThrows(
			ValidationException.class,
			() -> resource.scan("catalogue", null, "0")
		);

		assertThrows(
			ValidationException.class,
			() -> resource.scan("catalogue", null, "10001")
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
			() -> resource.scan("logs", null, null)
		);

		assertThrows(
			IndexSourceNotKeptException.class,
			() -> resource.scanStream("logs", null, null)
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
			() -> resource.scan("logs", null, null)
		);

		assertThrows(
			IndexNoPrimaryKeyException.class,
			() -> resource.scanStream("logs", null, null)
		);
	}

	@Test
	public void eachLineOfTheStreamIsOneDocument() throws IOException {
		catalogue();

		var lines = ndjson(resource.scanStream("catalogue", null, null));

		assertThat(lines.size(), is(3));
		assertThat(
			lines.get(0),
			is("{\"id\":\"1\",\"name\":\"Blueberry jam\",\"energy\":234.5}")
		);
	}

	@Test
	public void theStreamCarriesOnAfterTheKeyItIsGiven() throws IOException {
		catalogue();

		var lines = ndjson(resource.scanStream("catalogue", "2", null));

		assertThat(lines.size(), is(1));
		assertThat(lines.get(0).contains("\"3\""), is(true));
	}

	@Test
	public void whatTheStreamAnswersIsWhatTheIndexTakesBackIn() throws IOException {
		catalogue();

		var stream = new ByteArrayOutputStream();
		((StreamingOutput) resource.scanStream("catalogue", null, null).getEntity())
			.write(stream);

		var copy = indexes.create("catalogue-2", definition().build());

		var indexed = resource.addStream(
			"catalogue-2",
			new ByteArrayInputStream(stream.toByteArray())
		);
		copy.commit();

		assertThat(indexed.indexed(), is(3));

		var original = resource.scan("catalogue", null, null).documents();
		var written = resource.scan("catalogue-2", null, null).documents();

		assertThat(keysOf(written), contains("1", "2", "3"));
		for(var i = 0; i < original.size(); i++) {
			assertThat(written.get(i).get("name"), is(original.get(i).get("name")));
			assertThat(written.get(i).get("energy"), is(original.get(i).get("energy")));
		}
	}

	/**
	 * Read what a streamed answer wrote, as the lines it wrote.
	 */
	private static List<String> ndjson(jakarta.ws.rs.core.Response response) throws IOException {
		var stream = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(stream);

		var body = stream.toString(StandardCharsets.UTF_8);
		return body.isEmpty() ? List.of() : List.of(body.split("\n"));
	}

	private static List<Object> keysOf(List<se.l4.exofind.engine.index.Document> documents) {
		return documents.stream().map(document -> document.get("id")).toList();
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
