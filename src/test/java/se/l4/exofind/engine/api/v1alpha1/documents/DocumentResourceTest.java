package se.l4.exofind.engine.api.v1alpha1.documents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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
import se.l4.exofind.engine.api.v1alpha1.search.model.Clause;
import se.l4.exofind.engine.api.v1alpha1.search.model.Matcher;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.GeoPoint;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.GeoPointFieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.reindex.TestReindexJobs;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * Tests for putting documents into an index over the API - that the shape a
 * document arrives in becomes the values the index holds, that a document
 * replaces the one indexed under its key, and that a document the index
 * refuses says where in the request it sat.
 */
public class DocumentResourceTest {
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

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	/**
	 * An index of foods, holding the shapes a document can arrive in: a name
	 * per locale, several tags, a number and a point.
	 */
	private Index foods() throws IOException {
		return indexes.create(
			"foods",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string()
						.setStored(true)
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("sv")
								.addLocales("en")
								.build()
						)
						.build()
				)
				.putFields(
					"tags",
					string()
						.setStored(true)
						.setMultiple(true)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"energy",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setDouble(DoubleFieldTypeDef.getDefaultInstance())
						)
						.setStored(true)
						.build()
				)
				.putFields(
					"origin",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setGeoPoint(GeoPointFieldTypeDef.getDefaultInstance())
						)
						.setStored(true)
						.build()
				)
				.build()
		);
	}

	private static Map<String, Object> document(Object... keysAndValues) {
		var result = new java.util.LinkedHashMap<String, Object>();
		for(var i = 0; i < keysAndValues.length; i += 2) {
			result.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}

		return result;
	}

	@Test
	public void testDocumentsAreIndexedInTheShapeTheyArriveIn() throws IOException {
		var index = foods();

		var response = resource.add(
			"foods",
			new DocumentsRequest(
				List.of(
					document(
						"id", "1",
						"name", Map.of("sv", "blåbärssylt", "en", "blueberry jam"),
						"tags", List.of("sylt", "bär"),
						"energy", 234.5,
						"origin", Map.of("lat", 59.33, "lon", 18.07)
					)
				)
			)
		);

		assertThat(response.indexed(), is(1));

		index.commit();

		var stored = index.getDocument("1");
		assertThat(stored.get("energy"), is(234.5));
		assertThat(stored.get("origin"), is(new GeoPoint(59.33, 18.07)));
		assertThat(stored.getAll("tags"), contains("sylt", "bär"));
		assertThat(
			stored.getAll("name"),
			containsInAnyOrder("blåbärssylt", "blueberry jam")
		);
	}

	@Test
	public void testALocaleSpecificFieldKeepsTheLocaleItsValueArrivedUnder() throws IOException {
		var index = foods();

		resource.add(
			"foods",
			new DocumentsRequest(
				List.of(
					document(
						"id", "1",
						"name", Map.of("sv", "blåbärssylt", "en", "blueberry jam")
					)
				)
			)
		);

		index.commit();

		var stored = index.getDocument("1");
		var locales = java.util.Arrays.stream(stored.fields())
			.filter(value -> value.name().equals("name"))
			.map(value -> value.locale() + "=" + value.value())
			.toList();

		assertThat(locales, containsInAnyOrder("sv=blåbärssylt", "en=blueberry jam"));
	}

	@Test
	public void testALocaleSpecificFieldTakesAValueGivenWithoutALocale() throws IOException {
		var index = foods();

		resource.add(
			"foods",
			new DocumentsRequest(List.of(document("id", "1", "name", "blåbärssylt")))
		);

		index.commit();

		assertThat(index.getDocument("1").get("name"), is("blåbärssylt"));
	}

	@Test
	public void testAFieldWrittenAsNullIsAFieldThatWasNotGiven() throws IOException {
		var index = foods();

		var body = document("id", "1", "energy", null);
		resource.add("foods", new DocumentsRequest(List.of(body)));

		index.commit();

		assertThat(index.getDocument("1").get("energy"), is((Object) null));
	}

	@Test
	public void testADocumentReplacesTheOneIndexedUnderItsKey() throws IOException {
		var index = foods();

		resource.add("foods", new DocumentsRequest(List.of(document("id", "1", "energy", 100.0))));
		resource.add("foods", new DocumentsRequest(List.of(document("id", "1", "energy", 200.0))));

		index.commit();

		assertThat(index.getDocument("1").get("energy"), is(200.0));
	}

	@Test
	public void testTheValueOfAnObjectFieldIsADocumentOfItsOwn() throws IOException {
		var index = indexes.create(
			"products",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setObject(
									ObjectFieldTypeDef.newBuilder()
										.putFields("size", string().build())
										.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
								)
						)
						.setMultiple(true)
						.build()
				)
				.build()
		);

		resource.add(
			"products",
			new DocumentsRequest(
				List.of(
					document(
						"id", "1",
						"variants", List.of(Map.of("size", "S"), Map.of("size", "M"))
					)
				)
			)
		);

		index.commit();

		var sizes = index.getDocument("1")
			.getAll("variants")
			.stream()
			.map(value -> ((se.l4.exofind.engine.index.Document) value).get("size"))
			.toList();

		assertThat(sizes, contains("S", "M"));
	}

	@Test
	public void testTheValueOfAFlattenedObjectFieldReadsItsInnerTypes() throws IOException {
		var index = indexes.create(
			"products",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"dimensions",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setObject(
									ObjectFieldTypeDef.newBuilder()
										.putFields("label", string().setMultiple(true).build())
								)
						)
						.build()
				)
				.build()
		);

		resource.add(
			"products",
			new DocumentsRequest(
				List.of(
					document(
						"id", "1",
						"dimensions", Map.of("label", List.of("wide", "tall"))
					)
				)
			)
		);

		index.commit();

		var labels = ((se.l4.exofind.engine.index.Document) index.getDocument("1")
			.get("dimensions"))
			.getAll("label");

		assertThat(labels, contains("wide", "tall"));
	}

	@Test
	public void testAnObjectInsideAnObjectReadsItsInnerTypes() throws IOException {
		var index = indexes.create(
			"products",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"product",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder().putFields(
									"dims",
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
							)
						)
						.build()
				)
				.build()
		);

		resource.add(
			"products",
			new DocumentsRequest(
				List.of(
					document(
						"id", "1",
						"product", Map.of("dims", Map.of("width", 12.5))
					)
				)
			)
		);

		index.commit();

		var product = (se.l4.exofind.engine.index.Document) index.getDocument("1")
			.get("product");
		var dims = (se.l4.exofind.engine.index.Document) product.get("dims");

		// Read through the inner field's own type, resolved by the full path
		assertThat(dims.get("width"), is(12.5));
	}

	@Test
	public void testDocumentsCanBeSentAsOnePerLine() throws IOException {
		var index = foods();

		var body = """
			{"id": "1", "name": "blåbärssylt"}
			{"id": "2", "name": "rågbröd"}
			""";

		var response = resource.addStream(
			"foods",
			new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
		);

		assertThat(response.indexed(), is(2));

		index.commit();

		assertThat(index.getDocument("2").get("name"), is("rågbröd"));
	}

	@Test
	public void testADocumentTheIndexRefusesSaysWhereInTheRequestItSat() throws IOException {
		foods();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.add(
				"foods",
				new DocumentsRequest(
					List.of(
						document("id", "1"),
						document("id", "2", "nonexistent", "value")
					)
				)
			)
		);

		assertThat(
			e.getErrors().collect(error -> error.getLocation().describe()).toList(),
			contains("documents[1].nonexistent")
		);
	}

	@Test
	public void testARequestWithoutDocumentsIsRefused() {
		assertThrows(
			ValidationException.class,
			() -> resource.add("foods", new DocumentsRequest(null))
		);
	}

	@Test
	public void testAKeyInThePathRemovesItsDocument() throws IOException {
		var index = foods();

		resource.add(
			"foods",
			new DocumentsRequest(List.of(document("id", "1"), document("id", "2")))
		);
		index.commit();

		var response = resource.delete("foods", "1");
		index.commit();

		assertThat(response.getStatus(), is(204));
		assertThat(index.getDocument("1"), is(nullValue()));
		assertThat(index.getDocument("2"), is(notNullValue()));
	}

	/**
	 * Removing is desired state the way indexing is, so a key nothing was
	 * indexed under is answered the same way as one that was.
	 */
	@Test
	public void testAKeyThatWasNeverIndexedIsNotAnError() throws IOException {
		foods();

		assertThat(resource.delete("foods", "1").getStatus(), is(204));
	}

	@Test
	public void testKeysAreRemovedTogether() throws IOException {
		var index = foods();

		resource.add(
			"foods",
			new DocumentsRequest(
				List.of(document("id", "1"), document("id", "2"), document("id", "3"))
			)
		);
		index.commit();

		var response = resource.delete(
			"foods",
			new DeleteRequest(List.of("1", "3"), null, null)
		);
		index.commit();

		assertThat(response.deleted(), is(2));
		assertThat(index.getDocument("2"), is(notNullValue()));
		assertThat(index.getDocument("1"), is(nullValue()));
		assertThat(index.getDocument("3"), is(nullValue()));
	}

	@Test
	public void testAQueryRemovesTheDocumentsItMatches() throws IOException {
		var index = foods();

		resource.add(
			"foods",
			new DocumentsRequest(
				List.of(
					document("id", "1", "tags", List.of("sylt")),
					document("id", "2", "tags", List.of("bröd"))
				)
			)
		);
		index.commit();

		var response = resource.delete(
			"foods",
			new DeleteRequest(
				null,
				List.of(new Clause.Field("tags", new Matcher.Equals("sylt"))),
				null
			)
		);
		index.commit();

		assertThat(response.deleted(), is(1));
		assertThat(index.getDocument("1"), is(nullValue()));
		assertThat(index.getDocument("2"), is(notNullValue()));
	}

	@Test
	public void testARequestNamingNothingToRemoveIsRefused() throws IOException {
		foods();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.delete("foods", new DeleteRequest(null, null, null))
		);

		assertThat(
			e.getErrors().collect(error -> error.getCode()).toList(),
			contains("request:delete:target_required")
		);
	}

	@Test
	public void testARequestNamingBothKeysAndAQueryIsRefused() throws IOException {
		foods();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.delete("foods", new DeleteRequest(List.of("1"), List.of(), null))
		);

		assertThat(
			e.getErrors().collect(error -> error.getCode()).toList(),
			contains("request:delete:target_conflicting")
		);
	}

	@Test
	public void testAMissingKeySaysWhereInTheRequestItSat() throws IOException {
		foods();

		var keys = new java.util.ArrayList<Object>();
		keys.add("1");
		keys.add(null);

		var e = assertThrows(
			ValidationException.class,
			() -> resource.delete("foods", new DeleteRequest(keys, null, null))
		);

		assertThat(
			e.getErrors().collect(error -> error.getLocation().describe()).toList(),
			contains("/keys/1")
		);
	}

	@Test
	public void testAClauseTheRequestCanNotRunSaysWhereItSat() throws IOException {
		foods();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.delete(
				"foods",
				new DeleteRequest(null, List.of(new Clause.Field("tags", null)), null)
			)
		);

		assertThat(
			e.getErrors().collect(error -> error.getLocation().describe()).toList(),
			contains("/query/0/match")
		);
	}
}
