package se.l4.exofind.engine.api.v1alpha1.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
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
import se.l4.exofind.engine.api.v1alpha1.admin.model.FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.IndexInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.Int64FieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.SearchSettingsDefinition;
import se.l4.exofind.engine.api.v1alpha1.admin.model.SearchSettingsInfo;
import se.l4.exofind.engine.api.v1alpha1.admin.model.StringFieldDefinition;
import se.l4.exofind.engine.api.v1alpha1.documents.DocumentResource;
import se.l4.exofind.engine.api.v1alpha1.documents.model.DocumentsRequest;
import se.l4.exofind.engine.api.v1alpha1.search.SearchResource;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchRequest;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchResponse;
import se.l4.exofind.engine.api.v1alpha1.search.model.Signal;
import se.l4.exofind.engine.auth.Principal;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.settings.InMemorySearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.settings.SearchSettingsNotFoundException;
import se.l4.exofind.engine.index.settings.SearchSettingsVersionMismatchException;
import se.l4.exofind.engine.index.state.LocalIndexerOwnership;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.reindex.TestReindexJobs;
import jakarta.ws.rs.core.UriInfo;

/**
 * Tests for changing how an index ranks through its search settings: the
 * definition stays as it is while searches answer differently, and everything
 * a promotion or a deletion should keep or clear behaves as documented.
 */
public class IndexSettingsResourceTest {
	@TempDir
	Path storageDirectory;

	Indexes indexes;
	InMemorySearchSettingsStorage storage;
	SearchSettings searchSettings;
	IndexResource admin;
	IndexSettingsResource resource;
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

		var auth = new AuthContext();
		auth.set(Principal.unchecked());

		var reindexJobs = TestReindexJobs.create(
			nodeState, indexes, registry, storageDirectory
		);

		storage = new InMemorySearchSettingsStorage();
		searchSettings = new SearchSettings(storage, Duration.ofSeconds(10));

		admin = new IndexResource(
			indexes, auth, new LocalIndexerOwnership(), reindexJobs, searchSettings
		);
		resource = new IndexSettingsResource(indexes, searchSettings);
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
	 * The definition of products: an id, and a sales count defined for
	 * sorting - which is what a ranking can read.
	 */
	private static IndexDefinition definition(boolean withSales) {
		var fields = new LinkedHashMap<String, FieldDefinition>();
		fields.put(
			"id",
			new StringFieldDefinition(
				true, null, null, null, null, null, null, null, null, null, null, null
			)
		);
		if(withSales) {
			fields.put(
				"sales",
				new Int64FieldDefinition(
					null, null, null, true, null, null,
					new FieldDefinition.Sort(null, null), null, null
				)
			);
		}

		return new IndexDefinition(null, null, fields, null, null, null);
	}

	private static SearchSettingsDefinition rankBySales(
		IndexDefinition.Ranking.TieBreaker.Direction direction
	) {
		return new SearchSettingsDefinition(
			new IndexDefinition.Ranking(
				List.of(new IndexDefinition.Ranking.TieBreaker("sales", direction)),
				null
			)
		);
	}

	private void add(String name, String id, Long sales) {
		var document = new LinkedHashMap<String, Object>();
		document.put("id", id);
		if(sales != null) {
			document.put("sales", sales);
		}

		documents.add(name, new DocumentsRequest(List.of(document)));
		admin.commit(name);
	}

	/**
	 * The index before any settings: three documents whose ids read in the
	 * order they were added, so any reordering is the settings' doing.
	 */
	private void products() {
		admin.put("products", null, null, false, uriInfo, definition(true));

		add("products", "1", 5L);
		add("products", "2", 50L);
		add("products", "3", 500L);
	}

	private List<Object> ids(List<Signal> signals) {
		var response = search.search(
			"products",
			new SearchRequest(
				null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, signals
			)
		);

		return response.hits().stream().map(SearchResponse.Hit::id).toList();
	}

	private List<Object> ids() {
		return ids(null);
	}

	@Test
	public void testSettingsReorderSearchesWithoutTouchingTheDefinition() {
		products();

		var before = (IndexInfo) admin.get("products").getEntity();
		assertThat(ids(), contains("1", "2", "3"));

		var response = resource.put(
			"products",
			null,
			rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING)
		);
		var info = (SearchSettingsInfo) response.getEntity();
		assertThat(response.getEntityTag().getValue(), is(info.version()));

		assertThat(ids(), contains("3", "2", "1"));

		// The definition was never involved, so its version stands
		var after = (IndexInfo) admin.get("products").getEntity();
		assertThat(after.version(), is(before.version()));
	}

	/**
	 * Signals a search brings replace whatever ranks the index, settings
	 * included - an empty list ranks by how well documents match alone.
	 */
	@Test
	public void testSignalsOfTheSearchWinOverTheSettings() {
		products();

		resource.put(
			"products",
			null,
			new SearchSettingsDefinition(
				new IndexDefinition.Ranking(
					null,
					List.of(new IndexDefinition.Ranking.Signal(
						"sales",
						new IndexDefinition.Ranking.Signal.Saturation(50d),
						null,
						null
					))
				)
			)
		);

		assertThat(ids(), contains("3", "2", "1"));
		assertThat(ids(List.of()), contains("1", "2", "3"));
	}

	@Test
	public void testDeleteReturnsTheIndexToItsDefinition() {
		products();

		resource.put(
			"products",
			null,
			rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING)
		);
		assertThat(ids(), contains("3", "2", "1"));

		var response = resource.delete("products");
		assertThat(response.getStatus(), is(204));

		assertThat(ids(), contains("1", "2", "3"));
		assertThrows(SearchSettingsNotFoundException.class, () -> resource.get("products"));
	}

	/**
	 * An index without settings has none rather than empty ones, so the
	 * {@code ETag} always names a version that exists.
	 */
	@Test
	public void testGetWithoutSettingsIsNotFound() {
		products();

		var e = assertThrows(
			SearchSettingsNotFoundException.class,
			() -> resource.get("products")
		);
		assertThat(e.getCode(), is("index:settings:not_found"));
	}

	@Test
	public void testGetAnswersWhatWasStored() {
		products();

		var stored = (SearchSettingsInfo) resource.put(
			"products",
			null,
			rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING)
		).getEntity();

		var response = resource.get("products");
		var info = (SearchSettingsInfo) response.getEntity();

		assertThat(response.getEntityTag().getValue(), is(stored.version()));
		assertThat(info.ranking().tieBreakers().get(0).field(), is("sales"));
		assertThat(info.unsupportedFeatures(), is(nullValue()));
	}

	/**
	 * A caller building on a version they read is told when someone else has
	 * changed the settings, instead of overwriting that change.
	 */
	@Test
	public void testPutWithAStaleVersionIsRefused() {
		products();

		var first = (SearchSettingsInfo) resource.put(
			"products",
			null,
			rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING)
		).getEntity();

		resource.put(
			"products",
			null,
			rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.ASCENDING)
		);

		assertThrows(
			SearchSettingsVersionMismatchException.class,
			() -> resource.put(
				"products",
				"\"" + first.version() + "\"",
				rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING)
			)
		);
	}

	@Test
	public void testPutWithTheCurrentVersionIsTaken() {
		products();

		var first = (SearchSettingsInfo) resource.put(
			"products",
			null,
			rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING)
		).getEntity();

		var second = (SearchSettingsInfo) resource.put(
			"products",
			"\"" + first.version() + "\"",
			rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.ASCENDING)
		).getEntity();

		assertThat(second.version(), not(is(first.version())));
	}

	/**
	 * Settings are validated against the live generation the way a
	 * definition's own ranking is, with the same codes.
	 */
	@Test
	public void testRankingByAnUnknownFieldIsRefused() {
		products();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.put(
				"products",
				null,
				new SearchSettingsDefinition(
					new IndexDefinition.Ranking(
						List.of(new IndexDefinition.Ranking.TieBreaker("missing", null)),
						null
					)
				)
			)
		);

		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:unknown_field"));
	}

	@Test
	public void testMissingBodyIsRefused() {
		products();

		assertThrows(
			ValidationException.class,
			() -> resource.put("products", null, null)
		);
	}

	/**
	 * Settings outlive generations. A generation promoted after they were
	 * written may lack the field they rank by; searches then skip the entry
	 * rather than fail, so promotion never depends on the settings having
	 * been rewritten first.
	 */
	@Test
	public void testAGenerationWithoutTheFieldSkipsTheEntry() {
		products();

		resource.put(
			"products",
			null,
			rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING)
		);
		assertThat(ids(), contains("3", "2", "1"));

		admin.put("products@2", null, null, false, uriInfo, definition(false));
		add("products@2", "1", null);
		add("products@2", "2", null);
		add("products@2", "3", null);
		admin.promote("products@2");

		assertThat(ids(), contains("1", "2", "3"));
	}

	/**
	 * Settings needing features this build does not have are set aside whole,
	 * and both the settings endpoint and the index's status say so.
	 */
	@Test
	public void testUnknownRequiredFeatureFallsBackToTheDefinition() {
		products();

		storage.set(
			"products",
			storage(rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING))
				.toBuilder()
				.addRequiredFeatures("pin_rules")
				.build()
		);

		assertThat(ids(), contains("1", "2", "3"));

		var info = (SearchSettingsInfo) resource.get("products").getEntity();
		assertThat(info.unsupportedFeatures(), contains("pin_rules"));
		assertThat(info.ranking(), not(nullValue()));

		var status = ((IndexInfo) admin.get("products").getEntity()).status();
		assertThat(status.settingsUnsupportedFeatures(), contains("pin_rules"));
	}

	/**
	 * What a definition-shaped settings object is stored as, for injecting
	 * through the fake the way another version of the engine would have
	 * written it.
	 */
	private static se.l4.exofind.engine.index.settings.SearchSettingsStore storage(
		SearchSettingsDefinition definition
	) {
		return se.l4.exofind.engine.index.settings.SearchSettingsStore.newBuilder()
			.setRanking(RankingMapper.toStored(definition.ranking()))
			.build();
	}
}
