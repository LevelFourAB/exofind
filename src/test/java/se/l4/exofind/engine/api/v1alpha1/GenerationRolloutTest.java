package se.l4.exofind.engine.api.v1alpha1;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
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
import se.l4.exofind.engine.api.auth.AuthContext;
import se.l4.exofind.engine.api.v1alpha1.admin.IndexResource;
import se.l4.exofind.engine.api.v1alpha1.admin.model.FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.GenerationSummary;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.StringFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.documents.DocumentResource;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentsRequest;
import se.l4.exofind.engine.api.v1alpha1.search.SearchResource;
import se.l4.exofind.engine.api.v1alpha1.search.model.Clause;
import se.l4.exofind.engine.api.v1alpha1.search.model.Matcher;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchRequest;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchResponse;
import se.l4.exofind.engine.auth.Principal;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.IndexDefinitionIncompatibleException;
import se.l4.exofind.engine.index.IndexFieldUsageException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.settings.InMemorySearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.state.LocalIndexerOwnership;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.reindex.TestReindexJobs;
import se.l4.exofind.engine.storage.StorageMode;
import jakarta.ws.rs.core.UriInfo;

/**
 * Tests for rolling a definition change out through a generation, walking the
 * whole of the flow the how-to describes: a change the live generation refuses
 * is created as a generation of its own, filled and searched under its own
 * name while the bare name goes on answering as it did, and promoted to make
 * it the one the bare name answers for.
 */
public class GenerationRolloutTest {
	@TempDir
	Path storageDirectory;

	Indexes indexes;
	IndexResource admin;
	DocumentResource documents;
	SearchResource search;
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

		var auth = new AuthContext();
		auth.set(Principal.unchecked());

		var reindexJobs = TestReindexJobs.create(
			nodeState, indexes, registry, storageDirectory
		);

		var searchSettings = new SearchSettings(
			new InMemorySearchSettingsStorage(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			Duration.ofSeconds(10),
			Duration.ofMinutes(10)
		);

		admin = new IndexResource(
			indexes, auth, new LocalIndexerOwnership(), reindexJobs, searchSettings
		);
		documents = new DocumentResource(indexes, new ObjectMapper(), reindexJobs);
		search = new SearchResource(indexes, searchSettings, 10_000);

		uriInfo = mock(UriInfo.class);
		when(uriInfo.getAbsolutePath())
			.thenReturn(URI.create("http://localhost/v1alpha1/admin/indexes/products"));
	}

	@AfterEach
	void cleanup() {
		indexes.close();
	}

	/**
	 * The definition of products, with the brand filtered on only when a
	 * filter is given. Turning filtering on writes terms nothing already
	 * indexed has, which is what makes the change one that has to be rolled
	 * out rather than sent to the generation holding the documents.
	 */
	private static IndexDefinition definition(FieldDefinition.Filter brandFilter) {
		return new IndexDefinition(
			null,
			null,
			Map.of(
				"id",
				new StringFieldDefinition(
					null, true, null, null, null, null, null, null, null, null, null, null,
					null
				),
				"title",
				new StringFieldDefinition(
					null, null, null, null, true, null, null, null, null, null, null, null,
					null
				),
				"brand",
				new StringFieldDefinition(
					null, null, null, null, true, null, brandFilter, null, null, null, null,
					null, null
				)
			),
			null,
			null,
			null
		);
	}

	private static IndexDefinition brandKept() {
		return definition(null);
	}

	private static IndexDefinition brandFiltered() {
		return definition(new FieldDefinition.Filter());
	}

	private IndexInfo put(String name, IndexDefinition definition) {
		return (IndexInfo) admin.put(name, null, null, false, uriInfo, definition)
			.getEntity();
	}

	private void add(String name, String id, String title, String brand) {
		var document = new LinkedHashMap<String, Object>();
		document.put("id", id);
		document.put("title", title);
		document.put("brand", brand);

		documents.add(name, new DocumentsRequest(List.of(document)));
		admin.commit(name);
	}

	private List<Object> ids(String name, List<Clause> query) {
		var response = search.search(
			name,
			new SearchRequest(
				query, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null
			)
		);

		return response.hits().stream().map(SearchResponse.Hit::id).toList();
	}

	private List<Object> ids(String name) {
		return ids(name, null);
	}

	/**
	 * The index as it stands before a rollout: one generation, holding two
	 * documents, with the brand kept but not filtered on.
	 */
	private void products() {
		put("products", brandKept());

		add("products", "1", "Trail Runner", "acme");
		add("products", "2", "Rain Shell", "borden");
	}

	/**
	 * A definition that reaches documents already indexed is refused by the
	 * generation holding them, which is what the rollout exists to get around
	 * - the same definition is taken by a generation of its own.
	 */
	@Test
	public void testTheChangeTheLiveGenerationRefusesIsTakenByANewOne() {
		products();

		var e = assertThrows(
			IndexDefinitionIncompatibleException.class,
			() -> admin.put("products", null, null, false, uriInfo, brandFiltered())
		);

		assertThat(e.getCode(), is("index:definition:incompatible"));

		var added = put("products@2", brandFiltered());
		assertThat(added.generation(), is("2"));
		assertThat(added.live(), is(false));
	}

	/**
	 * The bare name answers from the generation that was there for as long as
	 * the new one is being filled, and from the new one the moment it is
	 * promoted.
	 */
	@Test
	public void testTheNewGenerationAnswersForTheNameOnceItIsPromoted() {
		products();

		put("products@2", brandFiltered());

		// The generation carries over what the documents are being sent again from
		add("products@2", "1", "Trail Runner", "acme");
		add("products@2", "2", "Rain Shell", "borden");
		add("products@2", "3", "Wind Jacket", "acme");

		// Both are searchable, each answering for what it holds
		assertThat(ids("products"), containsInAnyOrder("1", "2"));
		assertThat(ids("products@2"), containsInAnyOrder("1", "2", "3"));

		// Naming the generation that is live is the same as not naming one
		assertThat(ids("products@1"), containsInAnyOrder("1", "2"));

		admin.promote("products@2");

		assertThat(ids("products"), containsInAnyOrder("1", "2", "3"));

		// The generation left behind is still there to be searched and rolled back to
		assertThat(ids("products@1"), containsInAnyOrder("1", "2"));
	}

	/**
	 * A search answers with the definition of the generation it names, so the
	 * change being rolled out is usable before it is promoted and only there.
	 */
	@Test
	public void testASearchUsesTheDefinitionOfTheGenerationItNames() {
		products();

		put("products@2", brandFiltered());
		add("products@2", "1", "Trail Runner", "acme");
		add("products@2", "3", "Wind Jacket", "acme");

		var byBrand = List.<Clause>of(
			new Clause.Field("brand", new Matcher.Equals("acme"))
		);

		assertThat(ids("products@2", byBrand), containsInAnyOrder("1", "3"));

		// The live generation never had the field written for filtering
		assertThrows(
			IndexFieldUsageException.class,
			() -> ids("products", byBrand)
		);

		admin.promote("products@2");

		assertThat(ids("products", byBrand), containsInAnyOrder("1", "3"));
	}

	/**
	 * Filling the new generation is invisible to the one answering searches,
	 * which is what makes a rollout something that can be done while the index
	 * is being used.
	 */
	@Test
	public void testWritingToTheNewGenerationLeavesTheLiveOneAsItWas() {
		products();

		put("products@2", brandFiltered());

		add("products@2", "3", "Wind Jacket", "acme");
		documents.delete("products@2", "1");
		admin.commit("products@2");

		assertThat(ids("products"), containsInAnyOrder("1", "2"));
		assertThat(ids("products@2"), contains("3"));

		var info = (IndexInfo) admin.get("products").getEntity();
		assertThat(info.generation(), is("1"));
		assertThat(
			info.generations().stream().map(GenerationSummary::name).toList(),
			contains("1", "2")
		);
	}

	/**
	 * A document sent to the bare name while the new generation fills reaches
	 * only the generation answering for it. Keeping the new generation complete
	 * is the caller's to do in this flow - the engine-driven reindex is what
	 * replays such writes instead.
	 */
	@Test
	public void testAWriteToTheNameDuringTheRolloutStaysInTheLiveGeneration() {
		products();

		put("products@2", brandFiltered());
		add("products@2", "1", "Trail Runner", "acme");
		add("products@2", "2", "Rain Shell", "borden");

		add("products", "3", "Wind Jacket", "acme");

		assertThat(ids("products"), containsInAnyOrder("1", "2", "3"));
		assertThat(ids("products@2"), containsInAnyOrder("1", "2"));

		admin.promote("products@2");

		// The promoted generation answers without it, until it is sent again
		assertThat(ids("products"), containsInAnyOrder("1", "2"));
	}

	/**
	 * A rollout is undone the way it was made: the generation that was there
	 * is promoted again, and the name answers from it without callers changing
	 * anything.
	 */
	@Test
	public void testPromotingThePreviousGenerationRollsTheChangeBack() {
		products();

		put("products@2", brandFiltered());
		add("products@2", "1", "Trail Runner", "acme");
		add("products@2", "3", "Wind Jacket", "acme");

		admin.promote("products@2");
		assertThat(ids("products"), containsInAnyOrder("1", "3"));

		var rolledBack = (IndexInfo) admin.promote("products@1").getEntity();
		assertThat(rolledBack.generation(), is("1"));
		assertThat(rolledBack.live(), is(true));

		assertThat(ids("products"), containsInAnyOrder("1", "2"));

		// And with the definition it was rolled back to, so the filter is gone again
		assertThrows(
			IndexFieldUsageException.class,
			() -> ids("products", List.of(new Clause.Field("brand", new Matcher.Equals("acme"))))
		);
	}

	/**
	 * The generation a rollout replaced is removed once it is no longer the
	 * one answering, leaving the promoted one alone to answer for the name.
	 */
	@Test
	public void testThePreviousGenerationIsDeletedAfterTheRollout() {
		products();

		put("products@2", brandFiltered());
		add("products@2", "1", "Trail Runner", "acme");
		add("products@2", "3", "Wind Jacket", "acme");

		admin.promote("products@2");

		assertThat(admin.delete("products@1").getStatus(), is(204));

		var info = (IndexInfo) admin.get("products").getEntity();
		assertThat(
			info.generations().stream().map(GenerationSummary::name).toList(),
			contains("2")
		);

		assertThat(ids("products"), containsInAnyOrder("1", "3"));
	}

	/**
	 * The generation the name answers for is refused deletion, so cleaning up
	 * in the wrong order cannot leave the name answering for nothing.
	 */
	@Test
	public void testTheGenerationAnsweringForTheNameCannotBeDeleted() {
		products();

		put("products@2", brandFiltered());

		var e = assertThrows(
			ValidationException.class,
			() -> admin.delete("products@1")
		);

		assertThat(e.getErrors().get(0).getCode(), is("index:generation:is_live"));

		// The refusal changed nothing: the name answers as before
		assertThat(ids("products"), containsInAnyOrder("1", "2"));
	}
}
