package se.l4.exofind.engine.api.v1alpha1.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.protobuf.UnknownFieldSet;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.api.auth.AuthContext;
import se.l4.exofind.engine.api.errors.UnrepresentableStateException;
import se.l4.exofind.engine.api.v1alpha1.admin.model.FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.GenerationSummary;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexListResponse;
import se.l4.exofind.engine.api.v1alpha1.admin.model.StringFieldDefinition;
import se.l4.exofind.engine.auth.Principal;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.IndexDefinitionIncompatibleException;
import se.l4.exofind.engine.index.IndexNotFoundException;
import se.l4.exofind.engine.index.IndexState;
import se.l4.exofind.engine.index.IndexVersionMismatchException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.state.IndexerOwnership;
import se.l4.exofind.engine.index.state.LocalIndexerOwnership;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.reindex.ReindexJobs;
import se.l4.exofind.engine.reindex.TestReindexJobs;
import jakarta.ws.rs.core.UriInfo;

public class IndexResourceTest {
	@TempDir
	Path storageDirectory;

	Indexes indexes;
	AuthContext auth;
	IndexResource resource;
	ReindexJobs reindexJobs;
	UriInfo uriInfo;

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

		auth = new AuthContext();
		auth.set(Principal.unchecked());

		reindexJobs = TestReindexJobs.create(nodeState, indexes, registry, storageDirectory);
		resource = new IndexResource(indexes, auth, new LocalIndexerOwnership(), reindexJobs);

		uriInfo = mock(UriInfo.class);
		when(uriInfo.getAbsolutePath())
			.thenReturn(URI.create("http://localhost/v1alpha1/admin/indexes/books"));
	}

	@AfterEach
	void cleanup() {
		indexes.close();
	}

	private IndexDefinition definition() {
		return new IndexDefinition(
			null,
			null,
			Map.of(
				"id",
				new StringFieldDefinition(
					true, null, null, null, null,
					new FieldDefinition.Filter(),
					null,
					null,
					null,
					null,
					null,
					null
				)
			),
			null,
			null,
			null
		);
	}

	private IndexInfo create(String name, IndexDefinition definition) {
		var response = resource.put(name, null, null, false, uriInfo, definition);
		assertThat(response.getStatus(), is(201));
		return (IndexInfo) response.getEntity();
	}

	@Test
	public void testCreate() {
		var info = create("books", definition());

		assertThat(info.name(), is("books"));
		assertThat(info.generation(), is("1"));
		assertThat(info.live(), is(true));
		assertThat(info.definition().fields().keySet(), contains("id"));
		assertThat(info.status().state(), is(IndexState.USABLE));
		assertThat(info.status().readOnly(), is(false));

		// Storing locally names no writer - readOnly already answers
		assertThat(info.status().indexer(), is(nullValue()));
	}

	/**
	 * The status names the node writing the index when the shared state holds
	 * a claim for it - on the node holding it and on every other alike.
	 */
	@Test
	public void testStatusNamesTheIndexer() {
		create("books", definition());

		var ownership = mock(IndexerOwnership.class);
		when(ownership.overview()).thenReturn(Optional.of(new IndexerOwnership.Overview(
			List.of(),
			List.of(new IndexerOwnership.Claim(
				"books",
				"node-1",
				Optional.of("http://node-1:8080"),
				Instant.now().plusSeconds(30)
			))
		)));

		var withOwnership = new IndexResource(indexes, auth, ownership, reindexJobs);
		var info = (IndexInfo) withOwnership.get("books").getEntity();

		assertThat(info.status().indexer(), is(notNullValue()));
		assertThat(info.status().indexer().node(), is("node-1"));
		assertThat(info.status().indexer().address(), is("http://node-1:8080"));
	}

	/**
	 * The whole of a rollout: a generation is added with the definition to move
	 * to, and until it is promoted the name goes on answering from the one that
	 * was there.
	 */
	@Test
	public void testGenerationIsAddedAndPromoted() {
		create("books", definition());

		var added = create("books@2", definition());
		assertThat(added.name(), is("books"));
		assertThat(added.generation(), is("2"));
		assertThat(added.live(), is(false));

		var before = (IndexInfo) resource.get("books").getEntity();
		assertThat(before.generation(), is("1"));

		var promoted = (IndexInfo) resource.promote("books@2").getEntity();
		assertThat(promoted.generation(), is("2"));
		assertThat(promoted.live(), is(true));

		var after = (IndexInfo) resource.get("books").getEntity();
		assertThat(after.generation(), is("2"));
	}

	/**
	 * Reading an index says what could be promoted as well as what is answering
	 * now.
	 */
	@Test
	public void testGenerationsAreListedWithTheIndex() {
		create("books", definition());
		create("books@2", definition());

		var info = (IndexInfo) resource.get("books").getEntity();

		assertThat(
			info.generations().stream().map(GenerationSummary::name).toList(),
			contains("1", "2")
		);

		assertThat(
			info.generations().stream().map(GenerationSummary::live).toList(),
			contains(true, false)
		);
	}

	/**
	 * A generation is added to an index that exists. There is nothing to add
	 * one to otherwise, and creating the index by naming a generation would let
	 * the caller decide a name the engine owns.
	 */
	@Test
	public void testGenerationOfUnknownIndexIsNotFound() {
		assertThrows(
			IndexNotFoundException.class,
			() -> resource.put("books@2", null, null, false, uriInfo, definition())
		);
	}

	@Test
	public void testGenerationIsDeletedWithoutTouchingTheIndex() {
		create("books", definition());
		create("books@2", definition());
		resource.promote("books@2");

		assertThat(resource.delete("books@1").getStatus(), is(204));

		var info = (IndexInfo) resource.get("books").getEntity();
		assertThat(
			info.generations().stream().map(GenerationSummary::name).toList(),
			contains("2")
		);
	}

	@Test
	public void testCreateWithoutDefinitionIsRejected() {
		var e = assertThrows(
			ValidationException.class,
			() -> resource.put("books", null, null, false, uriInfo, null)
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:missing_body"));
	}

	@Test
	public void testCreateWithInvalidNameIsRejected() {
		var e = assertThrows(
			ValidationException.class,
			() -> resource.put("Books!", null, null, false, uriInfo, definition())
		);

		assertThat(e.getErrors().get(0).getCode(), is("index:invalid_name"));
	}

	@Test
	public void testCreateWithInvalidDefinitionLeavesNothingBehind() {
		var invalid = new IndexDefinition(
			null,
			null,
			Map.of(
				"id",
				new StringFieldDefinition(
					true, null, true, null, null,
					null, null, null, null, null, null,
					null
				)
			),
			null,
			null,
			null
		);

		var e = assertThrows(
			ValidationException.class,
			() -> resource.put("books", null, null, false, uriInfo, invalid)
		);

		assertThat(
			e.getErrors().get(0).getCode(),
			is("index:field:invalid_primary_key_multiple")
		);

		assertThat(resource.list().indexes().isEmpty(), is(true));

		// The name is free again, so a corrected definition can be sent
		create("books", definition());
	}

	@Test
	public void testGet() {
		var created = create("books", definition());

		var response = resource.get("books");
		assertThat(response.getStatus(), is(200));

		var info = (IndexInfo) response.getEntity();
		assertThat(info.version(), is(created.version()));
		assertThat(info.definition(), is(created.definition()));
		assertThat(response.getEntityTag().getValue(), is(created.version()));
	}

	@Test
	public void testGetUnknown() {
		assertThrows(IndexNotFoundException.class, () -> resource.get("books"));
	}

	@Test
	public void testList() {
		create("books", definition());
		create("movies", definition());

		var listed = resource.list().indexes();

		assertThat(
			listed.stream().map(IndexListResponse.IndexSummary::name).toList(),
			contains("books", "movies")
		);

		assertThat(
			listed.stream().map(IndexListResponse.IndexSummary::generation).toList(),
			contains("1", "1")
		);
	}

	@Test
	public void testUpdateChangesVersion() {
		var created = create("books", definition());

		var updated = new IndexDefinition(
			null,
			Map.of("owner", "search"),
			definition().fields(),
			null,
			null,
			null
		);

		var response = resource.put("books", null, null, false, uriInfo, updated);
		assertThat(response.getStatus(), is(200));

		var info = (IndexInfo) response.getEntity();
		assertThat(info.definition().metadata(), is(Map.of("owner", "search")));
		assertThat(info.version(), is(not(created.version())));
	}

	@Test
	public void testUpdateWithCurrentVersion() {
		var created = create("books", definition());

		var response = resource.put(
			"books",
			"\"" + created.version() + "\"",
			null,
			false,
			uriInfo,
			new IndexDefinition(
				null, Map.of("owner", "search"), definition().fields(), null, null, null
			)
		);

		assertThat(response.getStatus(), is(200));
	}

	@Test
	public void testUpdateWithOutdatedVersionIsRejected() {
		create("books", definition());

		assertThrows(
			IndexVersionMismatchException.class,
			() -> resource.put(
				"books",
				"\"0000000000000000\"",
				null,
				false,
				uriInfo,
				new IndexDefinition(
					null, Map.of("owner", "search"), definition().fields(), null, null, null
				)
			)
		);
	}

	@Test
	public void testUpdateWithAnyVersionIsAccepted() {
		create("books", definition());

		var response = resource.put(
			"books",
			"*",
			null,
			false,
			uriInfo,
			new IndexDefinition(
				null, Map.of("owner", "search"), definition().fields(), null, null, null
			)
		);

		assertThat(response.getStatus(), is(200));
	}

	/**
	 * The same definition as {@link #definition()} with the field also searched
	 * as text, which writes terms nothing already indexed has.
	 */
	private IndexDefinition definitionWithMatching() {
		return new IndexDefinition(
			null,
			null,
			Map.of(
				"id",
				new StringFieldDefinition(
					true, null, null, null, null,
					new FieldDefinition.Filter(),
					null,
					null,
					null,
					new StringFieldDefinition.TextUsage(
						null, null, null, null, null, null, null
					),
					null,
					null
				)
			),
			null,
			null,
			null
		);
	}

	@Test
	public void testUpdateReachingNoIndexedDocumentIsRefused() throws IOException {
		create("books", definition());

		var index = indexes.getOrThrow("books");
		index.addDocument(new Document(new Document.Value("id", "1")));
		index.commit();

		var e = assertThrows(
			IndexDefinitionIncompatibleException.class,
			() -> resource.put("books", null, null, false, uriInfo, definitionWithMatching())
		);

		assertThat(e.getCode(), is("index:definition:incompatible"));
		assertThat(e.getErrors().get(0).getCode(), is("index:definition:usage_added"));
		assertThat(e.getErrors().get(0).getLocation().describe(), is("id"));
	}

	@Test
	public void testUpdateReachingNoIndexedDocumentIsTakenWhenStaleIsAllowed() throws IOException {
		create("books", definition());

		var index = indexes.getOrThrow("books");
		index.addDocument(new Document(new Document.Value("id", "1")));
		index.commit();

		var response = resource.put(
			"books", null, null, true, uriInfo, definitionWithMatching()
		);

		assertThat(response.getStatus(), is(200));
	}

	@Test
	public void testCreateWithVersionIsRejected() {
		assertThrows(
			IndexNotFoundException.class,
			() -> resource.put("books", "\"0000000000000000\"", null, false, uriInfo, definition())
		);
	}

	/**
	 * A definition written by a newer version of the API, standing in for one
	 * whose extra parts this version has no model for. Sending back what was
	 * read would drop them, so the update is refused instead.
	 */
	@Test
	public void testUpdateOfUnrepresentableDefinitionIsRefused() {
		indexes.create(
			"books",
			IndexDefinitionMapper.toStored(definition()).toBuilder()
				.setUnknownFields(
					UnknownFieldSet.newBuilder()
						.addField(4242, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
						.build()
				)
				.build()
		);

		var e = assertThrows(
			UnrepresentableStateException.class,
			() -> resource.put("books", null, null, false, uriInfo, definition())
		);

		assertThat(e.getCode(), is("index:definition:unrepresentable"));
	}

	@Test
	public void testDelete() {
		create("books", definition());

		var response = resource.delete("books");
		assertThat(response.getStatus(), is(204));

		assertThat(resource.list().indexes().isEmpty(), is(true));
		assertThrows(IndexNotFoundException.class, () -> resource.get("books"));
	}

	@Test
	public void testDeleteUnknown() {
		assertThrows(IndexNotFoundException.class, () -> resource.delete("books"));
	}

	@Test
	public void testCommit() {
		create("books", definition());

		var status = resource.commit("books");
		assertThat(status.state(), is(IndexState.USABLE));
	}

	@Test
	public void testPull() {
		create("books", definition());

		var status = resource.pull("books");
		assertThat(status.state(), is(IndexState.USABLE));
	}
}
