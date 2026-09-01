package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.GeoPointFieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.NestedQuery;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for what the values inside an object field answer for beyond matching -
 * ranking the document holding them, ordering it and being counted.
 *
 * The catalogue below is the shape these questions come from: a product with
 * several variants, where the variant that answered the search is not the same
 * as the cheapest variant, and where one product holds two variants of the same
 * colour.
 */
public class NestedValueSearchTest extends AbstractIndexTest {
	@Test
	public void testTextInsideNestedHoldsInsideOneValue() throws IOException {
		var index = products();

		/*
		 * The Trail Runner is waterproof in one variant and canvas in another,
		 * so no single variant is both even though the product holds both words.
		 */
		var across = search(
			index,
			Query.nested("variants", Query.text(words("waterproof canvas")))
		);

		assertThat(ids(across), is(empty()));

		var inside = search(
			index,
			Query.nested("variants", Query.text(words("waterproof leather")))
		);

		assertThat(ids(inside), containsInAnyOrder("1", "4"));
	}

	@Test
	public void testTextInsideNestedCoversTheFieldsOfThePath() throws IOException {
		var index = products();

		/*
		 * `name` is a field of the product and `material` one of a variant, so
		 * a text clause inside the nested clause covers the second and never
		 * the first.
		 */
		var result = search(index, Query.nested("variants", Query.text(words("runner"))));

		assertThat(ids(result), is(empty()));
	}

	@Test
	public void testTextInsideNestedRanksTheDocument() throws IOException {
		var index = products();

		var result = search(
			index,
			Query.nested("variants", Query.text(words("waterproof")))
		);

		assertThat(ids(result), containsInAnyOrder("1", "4"));
		assertThat(scoreOf(result, "4"), is(greaterThan(0f)));
	}

	@Test
	public void testScoreOfTheValuesIsRolledUpTheWayTheClauseSays() throws IOException {
		var index = products();

		/*
		 * The Ridge Boot is waterproof in both of its variants, so adding what
		 * they scored lifts it above what its best variant scored on its own.
		 */
		var best = search(
			index,
			Query.nested("variants", Query.text(words("waterproof")))
		);

		var together = search(
			index,
			Query.nested("variants", Query.text(words("waterproof")))
				.withScore(NestedQuery.Score.TOTAL)
		);

		assertThat(scoreOf(together, "4"), is(greaterThan(scoreOf(best, "4"))));

		// One variant answered for the Trail Runner either way
		assertThat(scoreOf(together, "1"), is(scoreOf(best, "1")));
	}

	@Test
	public void testNestedWithoutAnythingThatRanksLeavesTheScoreAlone() throws IOException {
		var index = products();

		var result = search(
			index,
			Query.nested("variants", Query.field("variants.color", Matchers.equalTo("red")))
		);

		assertThat(ids(result), containsInAnyOrder("1", "2", "4"));
		assertThat(scoreOf(result, "1"), is(0f));
	}

	@Test
	public void testNestedInsideNestedIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> search(
				index,
				Query.nested("variants", Query.nested("variants"))
			)
		);

		assertThat(e.getCode(), is("index:query:nested:unsupported_clause"));
	}

	@Test
	public void testOrderingReadsTheValueThatMatched() throws IOException {
		var index = products();

		/*
		 * The City Sneaker has a red variant at 30 and a blue one at 10, so a
		 * search for red products ordered by price puts it at 30 - ordering it
		 * by the blue variant it was not found by would file it first.
		 */
		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.withSort(SortBy.field("variants.price"))
				.build()
		);

		assertThat(ids(result), contains("1", "2", "4"));
	}

	@Test
	public void testOrderingDescendingReadsTheHighestValueThatMatched() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.withSort(SortBy.field("variants.price", SortBy.Order.DESCENDING))
				.build()
		);

		// The Ridge Boot is red at 40 and at 60, and stands for its highest
		assertThat(ids(result), contains("4", "2", "1"));
	}

	@Test
	public void testOrderingBySomethingTheSearchNeverAskedAboutReadsEveryValue()
		throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.field("variants.price"))
				.build()
		);

		// The Plain Sandal holds no variant at all, and files last
		assertThat(ids(result), contains("2", "1", "4", "3"));
	}

	@Test
	public void testPagingThroughAnOrderingByAValue() throws IOException {
		var index = products();

		var first = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.withSort(SortBy.field("variants.price"))
				.withLimit(1)
				.build()
		);

		assertThat(ids(first), contains("1"));

		var next = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.withSort(SortBy.field("variants.price"))
				.withLimit(1)
				.withAfter(first.hits().getLast().key())
				.build()
		);

		assertThat(ids(next), contains("2"));

		var back = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.withSort(SortBy.field("variants.price"))
				.withLimit(1)
				.withBefore(next.hits().getFirst().key())
				.build()
		);

		assertThat(ids(back), contains("1"));
	}

	@Test
	public void testOrderingByADistanceInsideAnObjectIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withSort(SortBy.distance("variants.pickup", 59.3, 18.1))
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:nested:sort_unsupported"));
	}

	@Test
	public void testCountingValuesCountsEachDocumentOnce() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("variants.color"))
				.build()
		);

		// The Ridge Boot holds two red variants and is one red product
		var facet = result.facets().get("variants.color");
		assertThat(facet.totalValues(), is(3));
		assertThat(
			facet.values(),
			contains(
				new SearchResult.Facet.Value("red", 3),
				new SearchResult.Facet.Value("black", 1),
				new SearchResult.Facet.Value("blue", 1)
			)
		);
	}

	@Test
	public void testCountingValuesCountsWhatTheSearchMatched() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.price", Matchers.lessThan(20d))
					)
				)
				.addFacet(Facet.of("variants.color").withOrder(Facet.Order.VALUE))
				.build()
		);

		/*
		 * Both products hold a variant under 20, and the colours counted are
		 * the ones those variants have rather than every colour of the two.
		 */
		assertThat(
			result.facets().get("variants.color").values(),
			contains(
				new SearchResult.Facet.Value("blue", 1),
				new SearchResult.Facet.Value("red", 1)
			)
		);
	}

	@Test
	public void testNestedFilterOnTheFacetsOwnFieldIsLeftOut() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFilter(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.addFacet(Facet.of("variants.color"))
				.build()
		);

		// The hits are narrowed to the products with a red variant
		assertThat(result.total().count(), is(3L));

		// While the colour counts still show what the other colours would hold
		assertThat(
			result.facets().get("variants.color").values(),
			contains(
				new SearchResult.Facet.Value("red", 3),
				new SearchResult.Facet.Value("black", 1),
				new SearchResult.Facet.Value("blue", 1)
			)
		);
	}

	@Test
	public void testAnotherNestedFilterStillNarrowsTheCounts() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFilter(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.addFilter(
					Query.nested(
						"variants",
						Query.field("variants.price", Matchers.atMost(20d))
					)
				)
				.addFacet(Facet.of("variants.color").withOrder(Facet.Order.VALUE))
				.build()
		);

		/*
		 * The colour facet leaves out its own entry and keeps the price entry,
		 * so the colours counted are those of variants at most 20 - the Ridge
		 * Boot holds no such variant and takes no part.
		 */
		assertThat(
			result.facets().get("variants.color").values(),
			contains(
				new SearchResult.Facet.Value("blue", 1),
				new SearchResult.Facet.Value("red", 1)
			)
		);
	}

	@Test
	public void testFusedNestedFilterIsLeftOutByTheObjectPath() throws IOException {
		var index = products();

		/*
		 * Colour and price fused into one nested clause hold inside the same
		 * variant, so the entry is one condition on `variants` - the colour
		 * facet's default exclusion of `variants.color` does not cover it.
		 */
		var fused = Query.nested(
			"variants",
			Query.field("variants.color", Matchers.equalTo("red")),
			Query.field("variants.price", Matchers.atLeast(40d))
		);

		var narrowed = index.search(
			SearchRequest.create()
				.addFilter(fused)
				.addFacet(Facet.of("variants.color"))
				.build()
		);

		assertThat(
			narrowed.facets().get("variants.color").values(),
			contains(new SearchResult.Facet.Value("red", 1))
		);

		// Naming the object path is what leaves the fused entry out whole
		var sideways = index.search(
			SearchRequest.create()
				.addFilter(fused)
				.addFacet(Facet.of("variants.color").withExcludeFilters("variants"))
				.build()
		);

		assertThat(
			sideways.facets().get("variants.color").values(),
			contains(
				new SearchResult.Facet.Value("red", 3),
				new SearchResult.Facet.Value("black", 1),
				new SearchResult.Facet.Value("blue", 1)
			)
		);
	}

	@Test
	public void testEmptyExcludeFiltersCountsInsideTheNestedFilter() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFilter(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.addFacet(Facet.of("variants.color").withExcludeFilters())
				.build()
		);

		// Nothing is left out, so only the ticked colour is counted
		assertThat(
			result.facets().get("variants.color").values(),
			contains(new SearchResult.Facet.Value("red", 3))
		);
	}

	@Test
	public void testCountingValuesAgainAnswersTheSameUntilACommit() throws IOException {
		var index = products();

		var unfiltered = SearchRequest.create()
			.addFacet(Facet.of("variants.color"))
			.build();

		/*
		 * Nothing narrows the first search, so its counts are kept for the
		 * reader and the searches after it answer from what was kept - the
		 * repeat outright, and the sideways search because leaving out the
		 * filter on the facet's own field puts it in the same whole-index
		 * scope.
		 */
		var first = index.search(unfiltered);
		var again = index.search(unfiltered);

		assertThat(
			again.facets().get("variants.color").values(),
			is(first.facets().get("variants.color").values())
		);
		assertThat(again.total().count(), is(first.total().count()));

		var sideways = index.search(
			SearchRequest.create()
				.addFilter(
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.addFacet(Facet.of("variants.color"))
				.build()
		);

		assertThat(
			sideways.facets().get("variants.color").values(),
			is(first.facets().get("variants.color").values())
		);

		// A commit replaces the reader, and the counts follow the index
		index.addDocument(
			new Document(
				new Document.Value("id", "5"),
				new Document.Value("name", "River Wader"),
				new Document.Value("category", "boots"),
				new Document.Value("variants", variant("green", "rubber", 55d))
			)
		);
		index.commit();

		var after = index.search(unfiltered);
		assertThat(
			after.facets().get("variants.color").values(),
			contains(
				new SearchResult.Facet.Value("red", 3),
				new SearchResult.Facet.Value("black", 1),
				new SearchResult.Facet.Value("blue", 1),
				new SearchResult.Facet.Value("green", 1)
			)
		);
		assertThat(after.total().count(), is(5L));
	}

	@Test
	public void testCountingValuesReadsEveryValueOfOne() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("variants.sizes").withOrder(Facet.Order.VALUE))
				.build()
		);

		assertThat(
			result.facets().get("variants.sizes").values(),
			contains(
				new SearchResult.Facet.Value("L", 1),
				new SearchResult.Facet.Value("M", 2),
				new SearchResult.Facet.Value("S", 1),
				new SearchResult.Facet.Value("XL", 1)
			)
		);
	}

	@Test
	public void testCountingValuesIntoBucketsCountsEachDocumentOnce() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(
					Facet.of("variants.price").withRanges(
						new Facet.Range(null, 20d),
						new Facet.Range(20d, 100d)
					)
				)
				.build()
		);

		/*
		 * The Ridge Boot holds two variants in the second bucket, and is one
		 * product priced there.
		 */
		assertThat(
			result.facets().get("variants.price").buckets(),
			contains(
				new SearchResult.Facet.Bucket(null, 20d, 2),
				new SearchResult.Facet.Bucket(20d, 100d, 3)
			)
		);
	}

	@Test
	public void testCountingValuesOfAFieldNotDefinedForItIsRefused() throws IOException {
		var index = products();

		assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create()
					.addFacet(Facet.of("variants.material"))
					.build()
			)
		);
	}

	private static TextMatcher words(String text) {
		return TextMatcher.of(text).withPrefix(TextMatcher.Prefix.OFF);
	}

	private Index products() throws IOException {
		var index = create(
			"products",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
					).build()
				)
				.putFields(
					"category",
					string().setFilter(FilterConfig.getDefaultInstance()).build()
				)
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
											.setFacet(FacetConfig.getDefaultInstance())
											.setRequired(true)
											.build()
									)
									.putFields(
										"material",
										string(
											StringFieldTypeDef.newBuilder().setMatching(
												StringFieldTypeDef.TextUsageConfig
													.getDefaultInstance()
											)
										).build()
									)
									.putFields(
										"price",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setDouble(
													DoubleFieldTypeDef.getDefaultInstance()
												)
											)
											.setFilter(FilterConfig.getDefaultInstance())
											.setFacet(FacetConfig.getDefaultInstance())
											.setSort(SortConfig.getDefaultInstance())
											.build()
									)
									.putFields(
										"sizes",
										string()
											.setFilter(FilterConfig.getDefaultInstance())
											.setFacet(FacetConfig.getDefaultInstance())
											.setMultiple(true)
											.build()
									)
									.putFields(
										"pickup",
										FieldDef.newBuilder()
											.setType(
												FieldTypeDef.newBuilder().setGeoPoint(
													GeoPointFieldTypeDef.getDefaultInstance()
												)
											)
											.setSort(SortConfig.getDefaultInstance())
											.build()
									)
									.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
							)
						)
						.setMultiple(true)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Trail Runner"),
				new Document.Value("category", "shoes"),
				new Document.Value("variants", variant("red", "waterproof leather", 15d, "S", "M")),
				new Document.Value("variants", variant("black", "canvas", 25d, "XL"))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "City Sneaker"),
				new Document.Value("category", "sneakers"),
				new Document.Value("variants", variant("red", "canvas", 30d)),
				new Document.Value("variants", variant("blue", "suede", 10d))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "Plain Sandal"),
				new Document.Value("category", "sandals")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("name", "Ridge Boot"),
				new Document.Value("category", "boots"),
				new Document.Value("variants", variant("red", "waterproof suede", 40d, "M")),
				new Document.Value("variants", variant("red", "waterproof leather", 60d, "L"))
			)
		);

		index.commit();
		return index;
	}

	private static Document variant(String color, String material, double price, String... sizes) {
		var values = new java.util.ArrayList<Document.Value>();
		values.add(new Document.Value("color", color));
		values.add(new Document.Value("material", material));
		values.add(new Document.Value("price", price));
		for(var size : sizes) {
			values.add(new Document.Value("sizes", size));
		}

		return new Document(values.toArray(new Document.Value[0]));
	}

	private static FieldDef.Builder string() {
		return string(StringFieldTypeDef.newBuilder());
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder().setType(FieldTypeDef.newBuilder().setString(type));
	}

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}

	private static float scoreOf(SearchResult result, String id) {
		return result.hits()
			.detect(hit -> id.equals(hit.id()))
			.score();
	}
}
