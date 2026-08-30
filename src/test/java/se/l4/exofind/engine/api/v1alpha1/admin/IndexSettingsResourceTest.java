package se.l4.exofind.engine.api.v1alpha1.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
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
import se.l4.exofind.engine.api.errors.UnrepresentableStateException;
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
import se.l4.exofind.engine.api.v1alpha1.search.SearchResourceTest;
import se.l4.exofind.engine.api.v1alpha1.search.model.Clause;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchRequest;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchResponse;
import se.l4.exofind.engine.api.v1alpha1.search.model.Signal;
import se.l4.exofind.engine.auth.Principal;
import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.settings.InMemorySearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.settings.SearchSettingsNotFoundException;
import se.l4.exofind.engine.index.settings.SearchSettingsVersionMismatchException;
import se.l4.exofind.engine.index.state.LocalIndexerOwnership;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.reindex.TestReindexJobs;
import se.l4.exofind.engine.storage.StorageMode;
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

		storage = new InMemorySearchSettingsStorage();
		searchSettings = new SearchSettings(
			storage,
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			Duration.ofSeconds(10),
			Duration.ofMinutes(10)
		);

		admin = new IndexResource(
			indexes, auth, new LocalIndexerOwnership(), reindexJobs, searchSettings
		);
		resource = new IndexSettingsResource(indexes, new ObjectMapper(), searchSettings);
		documents = new DocumentResource(indexes, new ObjectMapper(), reindexJobs);
		search = new SearchResource(
			indexes,
			searchSettings,
			SearchResourceTest.metrics(),
			10_000,
			1_000
		);

		uriInfo = mock(UriInfo.class);
		when(uriInfo.getAbsolutePath())
			.thenReturn(URI.create("http://localhost/v1alpha1/admin/indexes/products"));
	}

	@AfterEach
	void cleanup() {
		indexes.close();
	}

	/**
	 * The definition of products: an id, and two counts defined for sorting -
	 * which is what a ranking can read. Two, so that a change can name one
	 * entry of a ranking and be seen to leave the other alone.
	 */
	private static IndexDefinition definition(boolean withSales) {
		var fields = new LinkedHashMap<String, FieldDefinition>();
		fields.put(
			"id",
			new StringFieldDefinition(
				null, true, null, null, null, null, null, null, null, null, null, null, null
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
			fields.put(
				"views",
				new Int64FieldDefinition(
					null, null, null, true, null, null,
					new FieldDefinition.Sort(null, null), null, null
				)
			);
		}

		return new IndexDefinition(null, null, fields, null, null, null, null);
	}

	private static SearchSettingsDefinition rankBySales(
		IndexDefinition.Ranking.TieBreaker.Direction direction
	) {
		return new SearchSettingsDefinition(
			new IndexDefinition.Ranking(
				List.of(new IndexDefinition.Ranking.TieBreaker("sales", direction)),
				null
			),
			null,
			null
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
		return ids(signals, null);
	}

	private List<Object> ids(List<Signal> signals, SearchRequest.SignalsMode mode) {
		var response = search.search(
			"products",
			new SearchRequest(
				null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, signals, mode, null
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
	 * A search replacing the signals puts aside whatever ranks the index,
	 * settings included - an empty list then ranks by how well documents match
	 * alone.
	 */
	@Test
	public void testSignalsOfTheSearchWinOverTheSettingsWhenTheyReplaceThem() {
		products();

		rankBySalesInSettings();

		assertThat(ids(), contains("3", "2", "1"));
		assertThat(
			ids(List.of(), SearchRequest.SignalsMode.REPLACE),
			contains("1", "2", "3")
		);
	}

	/**
	 * The ranking of the settings survives a search that brings signals of its
	 * own, which is what keeps a personalized search under the ranking the
	 * index owns.
	 */
	@Test
	public void testSettingsStillRankASearchThatAddsItsOwnSignals() {
		products();

		rankBySalesInSettings();

		// No document holds a view count, so this signal reorders nothing itself
		var added = List.of(new Signal("views", new Signal.Saturation(50d), null, null));

		assertThat(ids(added), contains("3", "2", "1"));

		// Replacing drops the settings, leaving the signal that says nothing
		assertThat(
			ids(added, SearchRequest.SignalsMode.REPLACE),
			contains("1", "2", "3")
		);
	}

	private void rankBySalesInSettings() {
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
				),
				null,
				null
			)
		);
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
					),
					null,
					null
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
	 * A change to part of the settings, taking the paths in the order they are
	 * written here.
	 */
	private static Map<String, Object> change(Object... pathsAndValues) {
		var changes = new LinkedHashMap<String, Object>();
		for(var i = 0; i < pathsAndValues.length; i += 2) {
			changes.put((String) pathsAndValues[i], pathsAndValues[i + 1]);
		}

		return changes;
	}

	private SearchSettingsInfo patch(String name, String ifMatch, Object... pathsAndValues) {
		return (SearchSettingsInfo) resource
			.patch(name, ifMatch, change(pathsAndValues))
			.getEntity();
	}

	/**
	 * Settings ranking by both counts, so a change can name one of the two.
	 */
	private static SearchSettingsDefinition rankByBothCounts() {
		return new SearchSettingsDefinition(
			new IndexDefinition.Ranking(
				null,
				List.of(
					new IndexDefinition.Ranking.Signal(
						"sales",
						new IndexDefinition.Ranking.Signal.Saturation(50d),
						null,
						1f
					),
					new IndexDefinition.Ranking.Signal(
						"views",
						new IndexDefinition.Ranking.Signal.Saturation(10d),
						null,
						1f
					)
				)
			),
			null,
			null
		);
	}

	/**
	 * Relevance tuning moves one weight at a time, which is what a change to
	 * part of the settings is for: everything the change does not name is
	 * still stored afterwards.
	 */
	@Test
	public void testPatchChangesOneSignalAndLeavesTheOther() {
		products();
		resource.put("products", null, rankByBothCounts());

		var info = patch("products", null, "ranking.signals[field=views].weight", 3.0);

		var signals = info.ranking().signals();
		assertThat(signals.size(), is(2));
		assertThat(signals.get(0).field(), is("sales"));
		assertThat(signals.get(0).weight(), is(1f));
		assertThat(signals.get(0).saturation().pivot(), is(50d));
		assertThat(signals.get(1).field(), is("views"));
		assertThat(signals.get(1).weight(), is(3f));
	}

	@Test
	public void testPatchClearsOneSignalWithNull() {
		products();
		resource.put("products", null, rankByBothCounts());

		var info = patch("products", null, "ranking.signals[field=sales]", null);

		assertThat(info.ranking().signals().size(), is(1));
		assertThat(info.ranking().signals().get(0).field(), is("views"));
	}

	@Test
	public void testPatchAddsASignal() {
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
				),
				null,
				null
			)
		);

		var info = patch(
			"products",
			null,
			"ranking.signals[]",
			Map.of("field", "views", "saturation", Map.of("pivot", 10))
		);

		assertThat(
			info.ranking().signals().stream().map(IndexDefinition.Ranking.Signal::field).toList(),
			contains("sales", "views")
		);
	}

	/**
	 * An index searching with its definition alone is changed as if it had
	 * empty settings, so the first change does not have to be a whole one.
	 */
	@Test
	public void testPatchOfAnIndexWithoutSettingsStartsFromEmptyOnes() {
		products();

		var info = patch(
			"products",
			null,
			"ranking",
			Map.of("tieBreakers", List.of(Map.of("field", "sales", "direction", "descending")))
		);

		assertThat(info.ranking().tieBreakers().get(0).field(), is("sales"));
		assertThat(ids(), contains("3", "2", "1"));
	}

	@Test
	public void testPatchClearingTheRankingReturnsTheIndexToItsDefinition() {
		products();
		resource.put(
			"products",
			null,
			rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING)
		);
		assertThat(ids(), contains("3", "2", "1"));

		var info = patch("products", null, "ranking", null);

		assertThat(info.ranking(), is(nullValue()));
		assertThat(ids(), contains("1", "2", "3"));

		// Cleared rather than removed, so the settings still have a version
		assertThat(
			((SearchSettingsInfo) resource.get("products").getEntity()).version(),
			is(info.version())
		);
	}

	/**
	 * The result of a change is validated against the generation the index
	 * name answers from, with the codes that validate a whole one.
	 */
	@Test
	public void testPatchIsValidatedAgainstTheGeneration() {
		products();
		resource.put(
			"products",
			null,
			rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING)
		);

		var e = assertThrows(
			ValidationException.class,
			() -> patch("products", null, "ranking.tieBreakers[field=sales].field", "missing")
		);

		assertThat(e.getErrors().get(0).getCode(), is("index:ranking:unknown_field"));
	}

	/**
	 * A path naming nothing is refused rather than answered with settings that
	 * do not hold the change it asked for.
	 */
	@Test
	public void testPatchOfAFieldTheSettingsDoNotHaveIsRefused() {
		products();

		var e = assertThrows(
			ValidationException.class,
			() -> patch("products", null, "rankign", Map.of())
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:update:path_unknown_field"));
	}

	@Test
	public void testPatchNamingNoStoredEntryIsRefused() {
		products();
		resource.put("products", null, rankByBothCounts());

		var e = assertThrows(
			ValidationException.class,
			() -> patch("products", null, "ranking.signals[field=missing].weight", 2.0)
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:update:no_match"));
	}

	@Test
	public void testPatchGivingAFieldAValueItCannotHoldIsRefused() {
		products();

		var e = assertThrows(
			ValidationException.class,
			() -> patch("products", null, "ranking.signals", "not a list")
		);

		assertThat(e.getErrors().get(0).getCode(), is("request:update:value_invalid"));
	}

	@Test
	public void testPatchWithAStaleVersionIsRefused() {
		products();

		var first = (SearchSettingsInfo) resource.put(
			"products",
			null,
			rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING)
		).getEntity();

		patch("products", null, "ranking.tieBreakers[field=sales].direction", "ascending");

		assertThrows(
			SearchSettingsVersionMismatchException.class,
			() -> patch(
				"products",
				"\"" + first.version() + "\"",
				"ranking.tieBreakers[field=sales].direction", "descending"
			)
		);
	}

	@Test
	public void testPatchWithTheCurrentVersionIsTaken() {
		products();

		var first = (SearchSettingsInfo) resource.put(
			"products",
			null,
			rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING)
		).getEntity();

		var second = patch(
			"products",
			"\"" + first.version() + "\"",
			"ranking.tieBreakers[field=sales].direction", "ascending"
		);

		assertThat(second.version(), not(is(first.version())));
		assertThat(
			second.ranking().tieBreakers().get(0).direction(),
			is(IndexDefinition.Ranking.TieBreaker.Direction.ASCENDING)
		);
	}

	/**
	 * Settings holding a feature this build has no name for are set aside
	 * rather than searched with, so a change built on top of them would be
	 * built on half an object and store the half back.
	 */
	@Test
	public void testPatchOfSettingsThisNodeCannotDescribeIsRefused() {
		products();

		storage.set(
			"products",
			storage(rankBySales(IndexDefinition.Ranking.TieBreaker.Direction.DESCENDING))
				.toBuilder()
				.addRequiredFeatures("pin_rules")
				.build()
		);

		var e = assertThrows(
			UnrepresentableStateException.class,
			() -> patch("products", null, "ranking.tieBreakers[field=sales].direction", "ascending")
		);

		assertThat(e.getCode(), is("index:settings:unrepresentable"));
	}

	@Test
	public void testPatchWithoutABodyIsRefused() {
		products();

		assertThrows(
			ValidationException.class,
			() -> resource.patch("products", null, null)
		);
	}

	/**
	 * A shop whose documents were indexed with no synonyms at all, which is
	 * what the sets below widen after the fact.
	 */
	private void shoes() {
		var fields = new LinkedHashMap<String, FieldDefinition>();
		fields.put(
			"id",
			new StringFieldDefinition(
				null, true, null, null, null, null, null, null, null, null, null, null, null
			)
		);
		fields.put(
			"name",
			new StringFieldDefinition(
				null, null, null, null, null, null, null, null, null, null,
				new StringFieldDefinition.TextUsage(
					null, null, null, null, null, null, null
				),
				null, null
			)
		);

		admin.put("shoes", null, null, false, uriInfo, new IndexDefinition(
			null, null, fields, null, null, null, null
		));

		documents.add("shoes", new DocumentsRequest(List.of(
			Map.of("id", "1", "name", "running sneakers"),
			Map.of("id", "2", "name", "leather trainers")
		)));
		admin.commit("shoes");
	}

	/**
	 * Settings holding one set of equivalent words, applied to every field.
	 */
	private static SearchSettingsDefinition equivalent(String... terms) {
		return new SearchSettingsDefinition(
			null,
			Map.of(
				"merch",
				new SearchSettingsDefinition.QuerySynonyms(
					List.of(new IndexDefinition.Resources.Synonyms.Rule(
						List.of(terms),
						null
					)),
					null,
					null
				)
			),
			null
		);
	}

	private List<Object> names(String text) {
		return names("shoes", text);
	}

	private List<Object> names(String index, String text) {
		var response = search.search(
			index,
			new SearchRequest(
				List.of(new Clause.Text(text, null, null, null, null, null, null, null)),
				null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null
			)
		);

		return response.hits().stream().map(SearchResponse.Hit::id).toList();
	}

	@Test
	public void testSynonymsWidenSearchesWithoutTouchingTheDefinition() {
		shoes();

		var before = (IndexInfo) admin.get("shoes").getEntity();
		assertThat(names("trainers"), contains("2"));

		resource.put("shoes", null, equivalent("trainers", "sneakers"));

		assertThat(names("trainers"), containsInAnyOrder("1", "2"));

		// The documents were never touched, and neither was the definition
		var after = (IndexInfo) admin.get("shoes").getEntity();
		assertThat(after.version(), is(before.version()));
	}

	@Test
	public void testGetAnswersTheSynonymsThatWereStored() {
		shoes();

		resource.put("shoes", null, equivalent("trainers", "sneakers"));

		var info = (SearchSettingsInfo) resource.get("shoes").getEntity();
		var set = info.synonyms().get("merch");

		assertThat(set.rules().get(0).equivalent(), contains("trainers", "sneakers"));
		assertThat(set.fields(), is(nullValue()));
		assertThat(set.boost(), is(nullValue()));
	}

	@Test
	public void testSynonymsOnAnUnknownFieldAreRefused() {
		shoes();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.put(
				"shoes",
				null,
				new SearchSettingsDefinition(
					null,
					Map.of(
						"merch",
						new SearchSettingsDefinition.QuerySynonyms(
							List.of(new IndexDefinition.Resources.Synonyms.Rule(
								List.of("trainers", "sneakers"),
								null
							)),
							List.of("missing"),
							null
						)
					),
					null
				)
			)
		);

		assertThat(e.getErrors().get(0).getCode(), is("index:settings:synonyms:unknown_field"));
	}

	/**
	 * A field nothing is searched as text in would be widened by a set that
	 * could never take effect, which reads as a set that does not work rather
	 * than as settings to fix.
	 */
	@Test
	public void testSynonymsOnAFieldThatIsNotTextAreRefused() {
		shoes();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.put(
				"shoes",
				null,
				new SearchSettingsDefinition(
					null,
					Map.of(
						"merch",
						new SearchSettingsDefinition.QuerySynonyms(
							List.of(new IndexDefinition.Resources.Synonyms.Rule(
								List.of("trainers", "sneakers"),
								null
							)),
							List.of("id"),
							null
						)
					),
					null
				)
			)
		);

		assertThat(e.getErrors().get(0).getCode(), is("index:settings:synonyms:field_not_text"));
	}

	@Test
	public void testSynonymsWithABoostOfNothingAreRefused() {
		shoes();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.put(
				"shoes",
				null,
				new SearchSettingsDefinition(
					null,
					Map.of(
						"merch",
						new SearchSettingsDefinition.QuerySynonyms(
							List.of(new IndexDefinition.Resources.Synonyms.Rule(
								List.of("trainers", "sneakers"),
								null
							)),
							null,
							0f
						)
					),
					null
				)
			)
		);

		assertThat(e.getErrors().get(0).getCode(), is("index:settings:synonyms:invalid_boost"));
	}

	@Test
	public void testSynonymRuleOfNoClearKindIsRefused() {
		shoes();

		var e = assertThrows(
			EngineException.class,
			() -> resource.put(
				"shoes",
				null,
				new SearchSettingsDefinition(
					null,
					Map.of(
						"merch",
						new SearchSettingsDefinition.QuerySynonyms(
							List.of(new IndexDefinition.Resources.Synonyms.Rule(null, null)),
							null,
							null
						)
					),
					null
				)
			)
		);

		assertThat(e.getCode(), is("index:settings:synonyms:invalid_rule"));
	}

	/**
	 * A change to part of the settings reaches into a set by name, which is
	 * what tuning one weight of a merchandising rule looks like.
	 */
	@Test
	public void testPatchChangesOneSynonymSet() {
		shoes();

		resource.put("shoes", null, equivalent("trainers", "sneakers"));

		var info = patch("shoes", null, "synonyms.merch.boost", 0.5f);

		assertThat(info.synonyms().get("merch").boost(), is(0.5f));
		assertThat(
			info.synonyms().get("merch").rules().get(0).equivalent(),
			contains("trainers", "sneakers")
		);

		// And the widening is still in force
		assertThat(names("trainers"), containsInAnyOrder("1", "2"));
	}

	/**
	 * A rule is added to a set the way a signal is added to a ranking, which is
	 * what growing a merchandising set one rule at a time looks like.
	 */
	@Test
	public void testPatchAddsASynonymRule() {
		shoes();

		resource.put("shoes", null, equivalent("trainers", "sneakers"));

		var info = patch(
			"shoes",
			null,
			"synonyms.merch.rules[]",
			Map.of("equivalent", List.of("leather", "suede"))
		);

		var rules = info.synonyms().get("merch").rules();
		assertThat(rules.size(), is(2));
		assertThat(rules.get(1).equivalent(), contains("leather", "suede"));

		assertThat(names("suede"), contains("2"));
	}

	/**
	 * Settings are the state a caller wants, so storing a ranking alone leaves
	 * an index with no synonym sets rather than with the ones it had.
	 */
	@Test
	public void testStoringSettingsWithoutSynonymsClearsThem() {
		shoes();

		resource.put("shoes", null, equivalent("trainers", "sneakers"));
		assertThat(names("trainers"), containsInAnyOrder("1", "2"));

		resource.put("shoes", null, new SearchSettingsDefinition(null, null, null));

		assertThat(names("trainers"), contains("2"));

		var info = (SearchSettingsInfo) resource.get("shoes").getEntity();
		assertThat(info.synonyms(), is(nullValue()));
	}

	/**
	 * A shop whose text forgives one mistake in a word, holding a camera brand
	 * and a common word that sits one mistake from it.
	 */
	private void cameras() {
		var fields = new LinkedHashMap<String, FieldDefinition>();
		fields.put(
			"id",
			new StringFieldDefinition(
				null, true, null, null, null, null, null, null, null, null, null, null, null
			)
		);
		fields.put(
			"name",
			new StringFieldDefinition(
				null, null, null, null, null, null, null, null, null, null,
				new StringFieldDefinition.TextUsage(
					null,
					null,
					null,
					new StringFieldDefinition.TextUsage.TypoTolerance(null, null, null, null),
					null,
					null,
					null
				),
				null, null
			)
		);

		admin.put("cameras", null, null, false, uriInfo, new IndexDefinition(
			null, null, fields, null, null, null, null
		));

		documents.add("cameras", new DocumentsRequest(List.of(
			Map.of("id", "1", "name", "canon camera"),
			Map.of("id", "2", "name", "canyon camera")
		)));
		admin.commit("cameras");
	}

	/**
	 * Settings matching one word as it is spelled, in every field.
	 */
	private static SearchSettingsDefinition excluding(String... words) {
		return new SearchSettingsDefinition(
			null,
			null,
			Map.of(
				"brands",
				new SearchSettingsDefinition.TypoExclusions(List.of(words), null)
			)
		);
	}

	@Test
	public void testExcludedWordsKeepTheirSpellingWithoutTouchingTheDefinition() {
		cameras();

		var before = (IndexInfo) admin.get("cameras").getEntity();
		assertThat(names("cameras", "canon"), containsInAnyOrder("1", "2"));

		resource.put("cameras", null, excluding("canon"));

		assertThat(names("cameras", "canon"), contains("1"));

		// The documents were never touched, and neither was the definition
		var after = (IndexInfo) admin.get("cameras").getEntity();
		assertThat(after.version(), is(before.version()));
	}

	@Test
	public void testGetAnswersTheExcludedWordsThatWereStored() {
		cameras();

		resource.put("cameras", null, excluding("canon"));

		var info = (SearchSettingsInfo) resource.get("cameras").getEntity();
		var exclusions = info.typoExclusions().get("brands");

		assertThat(exclusions.words(), contains("canon"));
		assertThat(exclusions.fields(), is(nullValue()));
	}

	/**
	 * The stored object names what it uses, so a node that has no code for word
	 * lists sets the settings aside instead of searching without them.
	 */
	@Test
	public void testStoredExclusionsNameTheFeatureTheyNeed() {
		cameras();

		resource.put("cameras", null, excluding("canon"));

		assertThat(
			searchSettings.read("cameras").orElseThrow().stored().getRequiredFeaturesList(),
			contains("typo_exclusions")
		);
	}

	@Test
	public void testTypoExclusionsOnAnUnknownFieldAreRefused() {
		cameras();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.put(
				"cameras",
				null,
				new SearchSettingsDefinition(
					null,
					null,
					Map.of(
						"brands",
						new SearchSettingsDefinition.TypoExclusions(
							List.of("canon"),
							List.of("missing")
						)
					)
				)
			)
		);

		assertThat(
			e.getErrors().get(0).getCode(),
			is("index:settings:typo_exclusions:unknown_field")
		);
	}

	/**
	 * A field nothing is searched as text in forgives no mistakes to begin
	 * with, so a list naming it reads as a list that does not work rather than
	 * as settings to fix.
	 */
	@Test
	public void testTypoExclusionsOnAFieldThatIsNotTextAreRefused() {
		cameras();

		var e = assertThrows(
			ValidationException.class,
			() -> resource.put(
				"cameras",
				null,
				new SearchSettingsDefinition(
					null,
					null,
					Map.of(
						"brands",
						new SearchSettingsDefinition.TypoExclusions(
							List.of("canon"),
							List.of("id")
						)
					)
				)
			)
		);

		assertThat(
			e.getErrors().get(0).getCode(),
			is("index:settings:typo_exclusions:field_not_text")
		);
	}

	/**
	 * A word is added to a list the way a rule is added to a synonym set, which
	 * is what a catalogue growing one brand at a time looks like.
	 */
	@Test
	public void testPatchAddsAnExcludedWord() {
		cameras();

		resource.put("cameras", null, excluding("leica"));

		var info = patch("cameras", null, "typoExclusions.brands.words[]", "canon");

		assertThat(info.typoExclusions().get("brands").words(), contains("leica", "canon"));
		assertThat(names("cameras", "canon"), contains("1"));
	}

	/**
	 * Settings are the state a caller wants, so storing settings without a word
	 * list leaves an index with none rather than with the list it had.
	 */
	@Test
	public void testStoringSettingsWithoutTypoExclusionsClearsThem() {
		cameras();

		resource.put("cameras", null, excluding("canon"));
		assertThat(names("cameras", "canon"), contains("1"));

		resource.put("cameras", null, new SearchSettingsDefinition(null, null, null));

		assertThat(names("cameras", "canon"), containsInAnyOrder("1", "2"));

		var info = (SearchSettingsInfo) resource.get("cameras").getEntity();
		assertThat(info.typoExclusions(), is(nullValue()));
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
