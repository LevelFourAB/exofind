package se.l4.exofind.engine.api.v1alpha1.search;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.api.v1alpha1.search.model.Clause;
import se.l4.exofind.engine.api.v1alpha1.search.model.ExplainResponse;
import se.l4.exofind.engine.api.v1alpha1.search.model.Matcher;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchRequest;
import se.l4.exofind.engine.api.v1alpha1.search.model.SearchResponse;
import se.l4.exofind.engine.api.v1alpha1.search.model.Sort;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.IndexFieldNotFoundException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.settings.InMemorySearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.schema.BooleanFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * Tests for searching over the API - that a request maps into a search, that
 * results come back shaped for the wire and that paging moves through them.
 */
public class SearchResourceTest {
	@TempDir
	Path storageDirectory;

	Indexes indexes;
	SearchSettings searchSettings;
	SearchResource resource;

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

		searchSettings = new SearchSettings(
			new InMemorySearchSettingsStorage(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			Duration.ofSeconds(10),
			Duration.ofMinutes(10)
		);
		resource = new SearchResource(indexes, searchSettings, 10_000);
	}

	@AfterEach
	void cleanup() {
		indexes.close();
	}

	private static FieldDef.Builder string() {
		return string(StringFieldTypeDef.newBuilder());
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(type));
	}

	private static FieldDef.Builder bool() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setBoolean(BooleanFieldTypeDef.getDefaultInstance())
			);
	}

	/**
	 * A small index of books, mirroring the one the engine's search tests
	 * use.
	 */
	private void books() throws IOException {
		var index = indexes.create(
			"books",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.newBuilder()
									.setHighlight(
										StringFieldTypeDef.TextUsageConfig.HighlightConfig
											.getDefaultInstance()
									)
							)
					)
						.setStored(true)
						.setSort(SortConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"category",
					string()
						.setStored(true)
						.setFilter(FilterConfig.getDefaultInstance())
						.setFacet(FacetConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"tags",
					string()
						.setMultiple(true)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"published",
					bool()
						.setFilter(FilterConfig.getDefaultInstance())
						.setFacet(FacetConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"pages",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setInt32(Int32FieldTypeDef.getDefaultInstance())
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.setFacet(FacetConfig.getDefaultInstance())
						.build()
				)
				.build()
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Silent Spring"),
				new Document.Value("category", "non-fiction"),
				new Document.Value("tags", "nature"),
				new Document.Value("tags", "classic"),
				new Document.Value("published", true),
				new Document.Value("pages", 150)
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Spring Cleaning"),
				new Document.Value("category", "fiction"),
				new Document.Value("published", false),
				new Document.Value("pages", 300)
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "The Quiet Sea"),
				new Document.Value("category", "poetry"),
				new Document.Value("published", true),
				new Document.Value("pages", 90)
			)
		);

		index.commit();
	}

	/**
	 * An index whose category is a path through a tree, for counting a level
	 * at a time.
	 */
	private void catalogue() throws IOException {
		var index = indexes.create(
			"catalogue",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"category",
					string(
						StringFieldTypeDef.newBuilder()
							.setHierarchy(
								StringFieldTypeDef.HierarchyConfig.getDefaultInstance()
							)
					)
						.setFilter(FilterConfig.getDefaultInstance())
						.setFacet(FacetConfig.getDefaultInstance())
						.build()
				)
				.build()
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("category", "Men/Shoes/Running")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("category", "Men/Outerwear")
			)
		);

		index.commit();
	}

	/**
	 * An index of many documents with a sortable code, for walking through
	 * pages.
	 *
	 * @param count
	 */
	private void many(int count) throws IOException {
		var index = indexes.create(
			"many",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"code",
					string()
						.setStored(true)
						.setSort(SortConfig.getDefaultInstance())
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
				.build()
		);

		for(var i = 0; i < count; i++) {
			index.addDocument(
				new Document(
					new Document.Value("id", String.valueOf(i)),
					new Document.Value("code", String.format("C-%04d", i))
				)
			);
		}

		index.commit();
	}

	@Test
	public void testFacetsComeBackKeyedByName() throws IOException {
		books();

		var response = resource.search(
			"books",
			new SearchRequest(
				null, null,
				List.of(new SearchRequest.Facet(null, "category", null, null, null, null, null, null)),
				null, null, null, null, null, null, null, null, null, null, null, null, null
			)
		);

		var facet = response.facets().get("category");
		assertThat(facet.totalValues(), is(3));
		assertThat(
			facet.values(),
			containsInAnyOrder(
				new SearchResponse.FacetValue("non-fiction", 1),
				new SearchResponse.FacetValue("fiction", 1),
				new SearchResponse.FacetValue("poetry", 1)
			)
		);
	}

	@Test
	public void testRangeFacetsAnswerBuckets() throws IOException {
		books();

		var response = resource.search(
			"books",
			new SearchRequest(
				null, null,
				List.of(
					new SearchRequest.Facet(
						null, "pages", null, null,
						List.of(
							new SearchRequest.Facet.Range(null, 100),
							new SearchRequest.Facet.Range(100, 200),
							new SearchRequest.Facet.Range(200, null)
						), null, null, null
					)
				),
				null, null, null, null, null, null, null, null, null, null, null, null, null
			)
		);

		var facet = response.facets().get("pages");
		assertThat(facet.values(), is(nullValue()));
		assertThat(facet.totalValues(), is(nullValue()));
		assertThat(
			facet.buckets(),
			contains(
				new SearchResponse.FacetBucket(null, 100, 1),
				new SearchResponse.FacetBucket(100, 200, 1),
				new SearchResponse.FacetBucket(200, null, 1)
			)
		);
	}

	@Test
	public void testTreeFacetsAnswerTheLevelsNested() throws IOException {
		catalogue();

		var response = resource.search(
			"catalogue",
			new SearchRequest(
				null, null,
				List.of(new SearchRequest.Facet(null, "category", null, null, null, "Men", 2, null)),
				null, null, null, null, null, null, null, null, null, null, null, null, null
			)
		);

		var facet = response.facets().get("category");
		assertThat(facet.totalValues(), is(2));
		assertThat(
			facet.values(),
			contains(
				// Both levels hold one, so the tie is broken by the level itself
				new SearchResponse.FacetValue(
					"Outerwear", 1, "Men/Outerwear", List.of(), 0
				),
				new SearchResponse.FacetValue(
					"Shoes",
					1,
					"Men/Shoes",
					List.of(
						new SearchResponse.FacetValue(
							"Running", 1, "Men/Shoes/Running", List.of(), 0
						)
					),
					1
				)
			)
		);
	}

	/**
	 * A value that stands on its own carries neither a path nor levels below
	 * it, so a facet that is not over a tree looks exactly as it did.
	 */
	@Test
	public void testValuesOutsideATreeCarryNoShapeOfOne() throws IOException {
		books();

		var response = resource.search(
			"books",
			new SearchRequest(
				null, null,
				List.of(new SearchRequest.Facet(null, "category", null, null, null, null, null, null)),
				null, null, null, null, null, null, null, null, null, null, null, null, null
			)
		);

		for(var value : response.facets().get("category").values()) {
			assertThat(value.path(), is(nullValue()));
			assertThat(value.values(), is(nullValue()));
			assertThat(value.totalValues(), is(nullValue()));
		}
	}

	@Test
	public void testFacetsAreLeftOutWhenNotAskedFor() throws IOException {
		books();

		var response = resource.search("books", request(null));

		assertThat(response.facets(), is(nullValue()));
	}

	@Test
	public void testFacetCountsAreSidewaysOfTheirOwnFilter() throws IOException {
		books();

		var response = resource.search(
			"books",
			new SearchRequest(
				null,
				List.of(
					new Clause.Field("category", new Matcher.In(List.of("fiction")))
				),
				List.of(
					new SearchRequest.Facet(null, "category", null, null, null, null, null, null),
					new SearchRequest.Facet(null, "published", null, null, null, null, null, null)
				),
				null, null, null, null, null, null, null, null, null, null, null, null, null
			)
		);

		// The hits are narrowed to the ticked category, and the total with them
		assertThat(ids(response), contains("2"));
		assertThat(response.total(), is(new SearchResponse.Total(1, true)));

		// The facet on the filtered field still shows every category
		assertThat(response.facets().get("category").totalValues(), is(3));

		// While the other facet is narrowed by the filter
		assertThat(
			response.facets().get("published").values(),
			contains(new SearchResponse.FacetValue(false, 1))
		);
	}

	@Test
	public void testFacetOnAFieldNotDefinedForItIsRefused() throws IOException {
		books();

		assertThrows(
			se.l4.exofind.engine.index.IndexFieldUsageException.class,
			() -> resource.search(
				"books",
				new SearchRequest(
					null, null,
					List.of(new SearchRequest.Facet(null, "tags", null, null, null, null, null, null)),
					null, null, null, null, null, null, null, null, null, null, null, null, null
				)
			)
		);
	}

	private static SearchRequest request(List<Clause> query) {
		return new SearchRequest(
			query, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
		);
	}

	private static List<Object> ids(SearchResponse response) {
		return response.hits().stream().map(SearchResponse.Hit::id).toList();
	}

	private static List<Object> idsInRange(int from, int to) {
		return java.util.stream.IntStream.range(from, to)
			.<Object>mapToObj(String::valueOf)
			.toList();
	}

	private static Sort byCode() {
		return new Sort.Field("code", null);
	}

	@Test
	public void testSearchWithoutBodyMatchesEverything() throws IOException {
		books();

		var response = resource.search("books", null);

		assertThat(ids(response), containsInAnyOrder("1", "2", "3"));
		assertThat(response.total(), is(new SearchResponse.Total(3, true)));
		assertThat(response.page().offset(), is(0));
		assertThat(response.page().previous(), is(nullValue()));
		assertThat(response.page().next(), is(nullValue()));
		assertThat(response.page().pages(), is(nullValue()));

		// Nothing scored, so no hit carries a score that looks like a value
		assertThat(response.hits().get(0).score(), is(nullValue()));
	}

	@Test
	public void testTimeSpentIsReported() throws IOException {
		books();

		var response = resource.search("books", null);

		/*
		 * Whatever the search spent is what comes back, down to fractions of a
		 * millisecond - one that answers faster than a millisecond reports
		 * what it took rather than nothing at all.
		 */
		assertThat(response.tookMs(), is(greaterThan(0d)));
	}

	@Test
	public void testFilterOnValue() throws IOException {
		books();

		var response = resource.search(
			"books",
			request(List.of(new Clause.Field("category", new Matcher.Equals("fiction"))))
		);

		assertThat(ids(response), contains("2"));
	}

	@Test
	public void testTextSearchScores() throws IOException {
		books();

		var response = resource.search(
			"books",
			request(List.of(new Clause.Text("silent", null, null, null, null, null, null, null)))
		);

		assertThat(ids(response), contains("1"));
		assertThat(response.hits().get(0).score(), is(notNullValue()));
		assertThat(response.hits().get(0).score(), is(greaterThan(0f)));

		// Nothing asked for highlights, so no hit carries the key
		assertThat(response.hits().get(0).highlights(), is(nullValue()));
	}

	@Test
	public void testHighlightsComeBackWhenAskedFor() throws IOException {
		books();

		var fields = new java.util.HashMap<String, SearchRequest.HighlightField>();
		fields.put("name", null);

		var response = resource.search(
			"books",
			new SearchRequest(
				List.of(new Clause.Text("silent", null, null, null, null, null, null, null)),
				null, null, null, null, null,
				new SearchRequest.Highlight(fields),
				null, null, null, null, null, null, null, null, null
			)
		);

		assertThat(ids(response), contains("1"));
		assertThat(
			response.hits().get(0).highlights().get("name"),
			contains("<em>Silent</em> Spring")
		);
	}

	@Test
	public void testHighlightsArePresentEvenWhenNothingMatchedThem() throws IOException {
		books();

		var fields = new java.util.HashMap<String, SearchRequest.HighlightField>();
		fields.put("name", null);

		var response = resource.search(
			"books",
			new SearchRequest(
				List.of(new Clause.Field("category", new Matcher.Equals("non-fiction"))),
				null, null, null, null, null,
				new SearchRequest.Highlight(fields),
				null, null, null, null, null, null, null, null, null
			)
		);

		// Asked for, so the key is there to count on - just with nothing in it
		assertThat(response.hits().get(0).highlights(), is(notNullValue()));
		assertThat(response.hits().get(0).highlights().isEmpty(), is(true));
	}

	/**
	 * A product whose variants are nested values, for asking which of them
	 * matched.
	 */
	private void products() throws IOException {
		var index = indexes.create(
			"products",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("name", string().build())
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"color",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
									.putFields(
										"stock",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setInt32(
													Int32FieldTypeDef.getDefaultInstance()
												)
											)
											.setFilter(FilterConfig.getDefaultInstance())
											.build()
									)
									.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
							)
						)
						.setMultiple(true)
						.build()
				)
				.build()
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Trail Runner"),
				new Document.Value(
					"variants",
					new Document(
						new Document.Value("color", "red"),
						new Document.Value("stock", 3)
					)
				),
				new Document.Value(
					"variants",
					new Document(
						new Document.Value("color", "blue"),
						new Document.Value("stock", 7)
					)
				)
			)
		);

		index.commit();
	}

	@Test
	public void testMatchedValuesComeBackWithEachHit() throws IOException {
		products();

		var response = resource.search(
			"products",
			new SearchRequest(
				List.of(
					new Clause.Nested(
						"variants",
						List.of(
							new Clause.Field("variants.color", new Matcher.Equals("red"))
						),
						null
					)
				),
				null, null, null, null, null, null,
				new SearchRequest.Matched(
					Map.of("variants", new SearchRequest.MatchedField(null, null))
				),
				null, null, null, null, null, null, null, null
			)
		);

		var json = new ObjectMapper()
			.convertValue(response.hits().get(0), new TypeReference<Map<String, Object>>() {});

		assertThat(
			json.get("matched"),
			is(Map.of(
				"variants", Map.of(
					"values", List.of(Map.of("color", "red", "stock", 3)),
					"totalValues", 1
				)
			))
		);
	}

	@Test
	public void testMatchedFieldsCutEachValue() throws IOException {
		products();

		var response = resource.search(
			"products",
			new SearchRequest(
				List.of(
					new Clause.Nested(
						"variants",
						List.of(
							new Clause.Field("variants.color", new Matcher.Equals("red"))
						),
						null
					)
				),
				null, null, null, null, null, null,
				new SearchRequest.Matched(
					Map.of(
						"variants",
						new SearchRequest.MatchedField(null, List.of("variants.color"))
					)
				),
				null, null, null, null, null, null, null, null
			)
		);

		var json = new ObjectMapper()
			.convertValue(response.hits().get(0), new TypeReference<Map<String, Object>>() {});

		assertThat(
			json.get("matched"),
			is(Map.of(
				"variants", Map.of(
					"values", List.of(Map.of("color", "red")),
					"totalValues", 1
				)
			))
		);
	}

	@Test
	public void testMatchedIsLeftOutWhenNotAskedFor() throws IOException {
		products();

		var response = resource.search("products", request(null));

		assertThat(response.hits().get(0).matched(), is(nullValue()));
	}

	private static SearchRequest valueHits(
		List<Clause> query,
		Integer limit,
		String after
	) {
		return new SearchRequest(
			query, null, null, null, null, null, null, null,
			new SearchRequest.Hits("variants", null),
			limit, null, after, null, null, null, null
		);
	}

	@Test
	public void testValueHitsCutToTheFieldsAskedFor() throws IOException {
		products();

		var response = resource.search(
			"products",
			new SearchRequest(
				null, null, null, null, null, null, null, null,
				new SearchRequest.Hits("variants", List.of("variants.color")),
				1, null, null, null, null, null, null
			)
		);

		assertThat(response.hits().size(), is(1));

		var json = new ObjectMapper()
			.convertValue(response.hits().get(0), new TypeReference<Map<String, Object>>() {});

		// The fields that were not asked for are gone, not nulled in place
		assertThat(json.get("value"), is(Map.of("color", "red")));
	}

	@Test
	public void testValueHitsComeBackWithIndexAndValue() throws IOException {
		products();

		var response = resource.search(
			"products",
			valueHits(
				List.of(
					new Clause.Nested(
						"variants",
						List.of(
							new Clause.Field("variants.color", new Matcher.Equals("blue"))
						),
						null
					)
				),
				null,
				null
			)
		);

		assertThat(response.hits().size(), is(1));
		assertThat(response.total(), is(new SearchResponse.Total(1, true)));

		var json = new ObjectMapper()
			.convertValue(response.hits().get(0), new TypeReference<Map<String, Object>>() {});

		assertThat(json.get("id"), is("1"));
		// The blue variant is the second value the document gave the field
		assertThat(json.get("index"), is(1));
		assertThat(json.get("value"), is(Map.of("color", "blue", "stock", 7)));
		assertThat(json.containsKey("score"), is(false));
		assertThat(json.containsKey("matched"), is(false));

		@SuppressWarnings("unchecked")
		var document = (Map<String, Object>) json.get("document");
		assertThat(document.get("name"), is("Trail Runner"));
	}

	@Test
	public void testValueHitsPageWithCursors() throws IOException {
		products();

		var first = resource.search("products", valueHits(null, 1, null));

		assertThat(first.hits().size(), is(1));
		assertThat(first.hits().get(0).index(), is(0));
		assertThat(first.page().next(), is(notNullValue()));

		var second = resource.search(
			"products",
			valueHits(null, 1, first.page().next())
		);

		assertThat(second.hits().size(), is(1));
		assertThat(second.hits().get(0).index(), is(1));
	}

	@Test
	public void testValueHitCursorsNeverResumeAmongDocuments() throws IOException {
		products();

		var values = resource.search("products", valueHits(null, 1, null));

		var e = assertThrows(
			ValidationException.class,
			() -> resource.search(
				"products",
				new SearchRequest(
					null, null, null, null, null, null, null, null, null,
					1, null,
					values.page().next(),
					null, null, null, null
				)
			)
		);

		assertThat(
			e.getErrors().collect(m -> m.getCode()).toList(),
			contains("search:cursor:sort_mismatch")
		);
	}

	@Test
	public void testDocumentShape() throws IOException {
		books();

		var response = resource.search(
			"books",
			request(List.of(new Clause.Field("category", new Matcher.Equals("non-fiction"))))
		);

		var document = documentJson(response.hits().get(0));
		assertThat(document.get("name"), is("Silent Spring"));
		assertThat(document.get("published"), is(true));

		// A field with several values comes back as an array
		assertThat(document.get("tags"), is(List.of("nature", "classic")));
	}

	@Test
	public void testFieldsNarrowTheDocument() throws IOException {
		books();

		var response = resource.search(
			"books",
			new SearchRequest(
				List.of(new Clause.Field("category", new Matcher.Equals("non-fiction"))),
				null, null, null, null,
				List.of("category"),
				null, null, null, null, null, null, null, null, null, null
			)
		);

		var document = documentJson(response.hits().get(0));
		assertThat(document.get("category"), is("non-fiction"));
		assertThat(document.containsKey("name"), is(false));

		// The primary key is always included, as it identifies the result
		assertThat(document.get("id"), is("1"));
	}

	/**
	 * The document of a hit as it goes over the wire, since the response
	 * carries the engine's own type and shapes it only as it is written.
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> documentJson(SearchResponse.Hit hit) {
		var json = new ObjectMapper()
			.convertValue(hit, new TypeReference<Map<String, Object>>() {});

		return (Map<String, Object>) json.get("document");
	}

	@Test
	public void testUnknownFieldIsRefused() throws IOException {
		books();

		assertThrows(
			IndexFieldNotFoundException.class,
			() -> resource.search(
				"books",
				request(List.of(new Clause.Field("missing", new Matcher.Equals("x"))))
			)
		);
	}

	@Test
	public void testCountOnly() throws IOException {
		books();

		var response = resource.search(
			"books",
			new SearchRequest(
				null, null, null, null, null, null, null, null, null, 0, null, null, null, null, null, null
			)
		);

		assertThat(response.hits(), is(empty()));
		assertThat(response.total(), is(new SearchResponse.Total(3, true)));
	}

	@Test
	public void testWalkingForwardsAndBackwardsWithCursors() throws IOException {
		many(25);

		var first = resource.search(
			"many",
			new SearchRequest(
				null, null, null, List.of(byCode()), null, null, null, null, null, 10, null, null, null, null, null,
				null
			)
		);

		assertThat(first.hits().size(), is(10));
		assertThat(first.hits().get(0).id(), is("0"));
		assertThat(first.page().offset(), is(0));
		assertThat(first.page().previous(), is(nullValue()));
		assertThat(first.page().next(), is(notNullValue()));

		var second = resource.search(
			"many",
			new SearchRequest(
				null, null, null, List.of(byCode()), null, null, null, null, null, 10, null,
				first.page().next(),
				null, null, null, null
			)
		);

		// Continuing from a hit does not count what it skips, so no offset
		assertThat(second.page().offset(), is(nullValue()));
		assertThat(ids(second), is(idsInRange(10, 20)));

		var third = resource.search(
			"many",
			new SearchRequest(
				null, null, null, List.of(byCode()), null, null, null, null, null, 10, null,
				second.page().next(),
				null, null, null, null
			)
		);

		assertThat(third.hits().size(), is(5));
		assertThat(ids(third), is(idsInRange(20, 25)));

		// Less than a full window, so there is nothing to continue to
		assertThat(third.page().next(), is(nullValue()));

		var backToFirst = resource.search(
			"many",
			new SearchRequest(
				null, null, null, List.of(byCode()), null, null, null, null, null, 10, null, null,
				second.page().previous(),
				null, null, null
			)
		);

		assertThat(ids(backToFirst), is(ids(first)));
		assertThat(backToFirst.page().next(), is(notNullValue()));
	}

	@Test
	public void testCursorsGoPastTheOffsetCap() throws IOException {
		many(25);

		var shallow = new SearchResource(indexes, searchSettings, 10);

		var first = shallow.search(
			"many",
			new SearchRequest(
				null, null, null, List.of(byCode()), null, null, null, null, null, 10, null, null, null, null, null,
				null
			)
		);

		// The same depth as an offset is refused at
		assertThrows(
			ValidationException.class,
			() -> shallow.search(
				"many",
				new SearchRequest(
					null, null, null, List.of(byCode()), null, null, null, null, null, 10, 10, null, null, null,
					null, null
				)
			)
		);

		var second = shallow.search(
			"many",
			new SearchRequest(
				null, null, null, List.of(byCode()), null, null, null, null, null, 10, null,
				first.page().next(),
				null, null, null, null
			)
		);
		assertThat(ids(second), is(idsInRange(10, 20)));

		var third = shallow.search(
			"many",
			new SearchRequest(
				null, null, null, List.of(byCode()), null, null, null, null, null, 10, null,
				second.page().next(),
				null, null, null, null
			)
		);
		assertThat(ids(third), is(idsInRange(20, 25)));
	}

	@Test
	public void testWalkingWithoutASort() throws IOException {
		many(25);

		var first = resource.search(
			"many",
			new SearchRequest(
				null, null, null, null, null, null, null, null, null, 10, null, null, null, null, null, null
			)
		);

		var second = resource.search(
			"many",
			new SearchRequest(
				null, null, null, null, null, null, null, null, null, 10, null,
				first.page().next(),
				null, null, null, null
			)
		);

		var backToFirst = resource.search(
			"many",
			new SearchRequest(
				null, null, null, null, null, null, null, null, null, 10, null, null,
				second.page().previous(),
				null, null, null
			)
		);

		assertThat(second.hits().size(), is(10));
		assertThat(ids(backToFirst), is(ids(first)));
	}

	@Test
	public void testNextUnderAChangedSortIsRefused() throws IOException {
		many(25);

		var first = resource.search(
			"many",
			new SearchRequest(
				null, null, null, List.of(byCode()), null, null, null, null, null, 10, null, null, null, null, null,
				null
			)
		);

		assertThrows(
			ValidationException.class,
			() -> resource.search(
				"many",
				new SearchRequest(
					null, null, null,
					List.of(new Sort.Field("code", Sort.Order.DESC)),
					null, null, null, null, null, 10, null,
					first.page().next(),
					null, null, null, null
				)
			)
		);
	}

	@Test
	public void testNumberedPages() throws IOException {
		many(25);

		var response = resource.search(
			"many",
			new SearchRequest(
				null, null, null, List.of(byCode()), null, null, null, null, null, 5, 10, null, null,
				new SearchRequest.Pages(null),
				null, null
			)
		);

		// Pages imply an exact total, so the count can be trusted
		assertThat(response.total(), is(new SearchResponse.Total(25, true)));

		var pages = response.page().pages();
		assertThat(pages.count(), is(5L));
		assertThat(pages.previous().number(), is(2L));
		assertThat(pages.next().number(), is(4L));

		// Five pages fit in the window, so they are all at the start
		assertThat(
			pages.start().stream().map(SearchResponse.PageRef::number).toList(),
			contains(1L, 2L, 3L, 4L, 5L)
		);
		assertThat(pages.middle(), is(empty()));
		assertThat(pages.end(), is(empty()));

		assertThat(pages.start().get(2).current(), is(true));
		assertThat(pages.start().get(0).current(), is(nullValue()));

		// A page's cursor lands on that page
		var fourth = resource.search(
			"many",
			new SearchRequest(
				null, null, null, List.of(byCode()), null, null, null, null, null, 5, null,
				pages.next().cursor(),
				null, null, null, null
			)
		);

		assertThat(fourth.page().offset(), is(15));
		assertThat(fourth.hits().get(0).id(), is("15"));
	}

	@Test
	public void testPagesAreWindowedAroundTheCurrentOne() throws IOException {
		many(26);

		var response = resource.search(
			"many",
			new SearchRequest(
				null, null, null, List.of(byCode()), null, null, null, null, null, 2, 12, null, null,
				new SearchRequest.Pages(5),
				null, null
			)
		);

		var pages = response.page().pages();
		assertThat(pages.count(), is(13L));

		// 1 … 6 [7] 8 … 13
		assertThat(
			pages.start().stream().map(SearchResponse.PageRef::number).toList(),
			contains(1L)
		);
		assertThat(
			pages.middle().stream().map(SearchResponse.PageRef::number).toList(),
			contains(6L, 7L, 8L)
		);
		assertThat(
			pages.end().stream().map(SearchResponse.PageRef::number).toList(),
			contains(13L)
		);
		assertThat(pages.middle().get(1).current(), is(true));
	}

	@Test
	public void testPagesPastTheCapAreNeverOffered() throws IOException {
		many(25);

		var shallow = new SearchResource(indexes, searchSettings, 10);

		var response = shallow.search(
			"many",
			new SearchRequest(
				null, null, null, List.of(byCode()), null, null, null, null, null, 5, null, null, null,
				new SearchRequest.Pages(null),
				null, null
			)
		);

		var pages = response.page().pages();
		assertThat(pages.count(), is(5L));

		// Only the first two pages fit under the cap of ten results
		assertThat(
			pages.start().stream().map(SearchResponse.PageRef::number).toList(),
			contains(1L, 2L)
		);
		assertThat(pages.end(), is(nullValue()));
		assertThat(pages.next().number(), is(2L));
	}

	@Test
	public void testExactTotalOverManyDocuments() throws IOException {
		many(1200);

		var response = resource.search(
			"many",
			new SearchRequest(
				null, null, null, null, null, null, null, null, null, 10, null, null, null, null,
				SearchRequest.Total.EXACT,
				null
			)
		);

		assertThat(response.total(), is(new SearchResponse.Total(1200, true)));
	}

	@Test
	public void testExplainNamesTheClauseAndTheFieldOfTheRequest() throws IOException {
		books();

		var response = resource.explain(
			"books",
			"1",
			0,
			new SearchRequest(
				List.of(new Clause.Text("silent", null, null, null, null, null, null, null)),
				null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null
			)
		);

		assertThat(response.matched(), is(true));
		assertThat(response.score(), is(greaterThan(0f)));

		var clause = stepAt(response.detail(), "query[0]");
		assertThat(clause, is(notNullValue()));
		assertThat(clause.clauseType(), is("text"));
		assertThat(clause.field(), is("name"));
		assertThat(clause.usage(), is("matching"));
	}

	@Test
	public void testExplainAnswersForADocumentTheSearchDoesNotMatch() throws IOException {
		books();

		var response = resource.explain(
			"books",
			"1",
			0,
			new SearchRequest(
				List.of(
					new Clause.Field("category", new Matcher.Equals("poetry"))
				),
				null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null
			)
		);

		assertThat(response.matched(), is(false));
		assertThat(response.score(), is(0f));

		var clause = stepAt(response.detail(), "query[0]");
		assertThat(clause, is(notNullValue()));
		assertThat(clause.matched(), is(false));
	}

	private static ExplainResponse.Detail stepAt(ExplainResponse.Detail detail, String clause) {
		if(clause.equals(detail.clause())) {
			return detail;
		}

		for(var child : detail.children()) {
			var found = stepAt(child, clause);
			if(found != null) {
				return found;
			}
		}

		return null;
	}
}
