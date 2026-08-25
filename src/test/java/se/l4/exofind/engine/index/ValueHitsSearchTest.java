package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SaturationSignal;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for searches whose hits are the matched values of an object field,
 * asked for with {@link SearchRequest.Hits}.
 *
 * The catalogue interleaves the variants of one product with the values of a
 * second object field, because the position a hit reports counts the values of
 * its own field - a badge sitting between two variants must not shift which
 * variant a hit stands for.
 */
public class ValueHitsSearchTest extends AbstractIndexTest {
	@Test
	public void testEachMatchedValueIsAHit() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withHits("variants")
				.build()
		);

		assertThat(result.hits().size(), is(3));
		assertThat(result.total().count(), is(3L));

		/*
		 * The Trail Runner is red in its first and third variant, with badges
		 * written between them - the positions reported count variants alone.
		 */
		var trailRunner = result.hits().select(hit -> "1".equals(hit.id()));
		assertThat(trailRunner.collect(SearchResult.Hit::index), contains(0, 2));
		assertThat(
			trailRunner.collect(hit -> hit.value().get("price")),
			contains(15d, 35d)
		);

		var sneaker = result.hits().detect(hit -> "2".equals(hit.id()));
		assertThat(sneaker.index(), is(0));
		assertThat(sneaker.value().get("color"), is("red"));

		// The document of each hit is the product holding the value
		assertThat(trailRunner.getFirst().document().get("name"), is("Trail Runner"));
	}

	@Test
	public void testEveryValueMatchesWhenNothingAskedOfThem() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withHits("variants")
				.build()
		);

		// Three variants of one product and two of another; the Plain Sandal has none
		assertThat(result.total().count(), is(5L));
		assertThat(result.hits().size(), is(5));
	}

	@Test
	public void testOuterClausesConstrainTheDocument() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.field("category", Matchers.equalTo("shoes")),
					Query.nested(
						"variants",
						Query.field("variants.color", Matchers.equalTo("red"))
					)
				)
				.withHits("variants")
				.build()
		);

		assertThat(
			result.hits().collect(SearchResult.Hit::id).toList(),
			contains("1", "1")
		);
	}

	@Test
	public void testTotalCountsValuesPastTheLimit() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withHits("variants")
				.withLimit(1)
				.withTotal(SearchRequest.Total.EXACT)
				.build()
		);

		assertThat(result.hits().size(), is(1));
		assertThat(result.total(), is(new SearchResult.Total(3, true)));
	}

	@Test
	public void testLimitZeroCountsValues() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withHits("variants")
				.withLimit(0)
				.build()
		);

		assertThat(result.hits().isEmpty(), is(true));
		assertThat(result.total(), is(new SearchResult.Total(5, true)));
	}

	@Test
	public void testClausesOnThePathRankTheValues() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.text(
							words("waterproof canvas").withMatch(TextMatcher.Match.ANY)
						)
					)
				)
				.withHits("variants")
				.build()
		);

		/*
		 * The Trail Runner's third variant holds both words, so it outranks
		 * every variant holding one - including the ones the same document
		 * gave earlier.
		 */
		var best = result.hits().getFirst();
		assertThat(best.id(), is("1"));
		assertThat(best.index(), is(2));
		assertThat(best.score(), is(greaterThan(result.hits().get(1).score())));
	}

	@Test
	public void testTheScoreOfTheDocumentReachesItsValues() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text(words("trail")))
				.withHits("variants")
				.build()
		);

		// Nothing was asked of the values, so every variant of the match is a hit
		assertThat(result.hits().size(), is(3));
		for(var hit : result.hits()) {
			assertThat(hit.id(), is("1"));
			assertThat(hit.score(), is(greaterThan(0f)));
		}
	}

	@Test
	public void testSignalsRankValueHitsByTheirDocument() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withHits("variants")
				.withSignals(new SaturationSignal("popularity", 10, 1))
				.build()
		);

		/*
		 * The City Sneaker far outsells the Trail Runner, so its variants come
		 * first however the blocks are laid out on disk.
		 */
		assertThat(
			result.hits().collect(SearchResult.Hit::id).toList(),
			contains("2", "2", "1", "1", "1")
		);
		assertThat(result.hits().getFirst().score(), is(greaterThan(0f)));
	}

	@Test
	public void testSortByAFieldInsideThePath() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withHits("variants")
				.withSort(SortBy.field("variants.price"))
				.build()
		);

		assertThat(
			result.hits().collect(hit -> hit.value().get("price")).toList(),
			contains(15d, 30d, 35d)
		);

		var descending = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withHits("variants")
				.withSort(SortBy.field("variants.price", SortBy.Order.DESCENDING))
				.build()
		);

		assertThat(
			descending.hits().collect(hit -> hit.value().get("price")).toList(),
			contains(35d, 30d, 15d)
		);
	}

	@Test
	public void testPagingForwardsAndBackwardsThroughValues() throws IOException {
		var index = products();

		var first = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withHits("variants")
				.withSort(SortBy.field("variants.price"))
				.withLimit(1)
				.build()
		);

		assertThat(first.hits().getFirst().value().get("price"), is(15d));

		var next = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withHits("variants")
				.withSort(SortBy.field("variants.price"))
				.withLimit(1)
				.withAfter(first.hits().getLast().key())
				.build()
		);

		assertThat(next.hits().getFirst().value().get("price"), is(30d));

		var back = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withHits("variants")
				.withSort(SortBy.field("variants.price"))
				.withLimit(1)
				.withBefore(next.hits().getFirst().key())
				.build()
		);

		assertThat(back.hits().getFirst().value().get("price"), is(15d));
	}

	@Test
	public void testPagingWithoutASort() throws IOException {
		var index = products();

		var first = index.search(
			SearchRequest.create()
				.withHits("variants")
				.withLimit(2)
				.build()
		);

		var rest = index.search(
			SearchRequest.create()
				.withHits("variants")
				.withLimit(10)
				.withAfter(first.hits().getLast().key())
				.build()
		);

		assertThat(rest.hits().size(), is(3));
	}

	@Test
	public void testOffsetSkipsValues() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withHits("variants")
				.withSort(SortBy.field("variants.price"))
				.withOffset(1)
				.build()
		);

		assertThat(
			result.hits().collect(hit -> hit.value().get("price")).toList(),
			contains(30d, 35d)
		);
	}

	@Test
	public void testFacetOnThePathCountsValues() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withHits("variants")
				.addFacet(Facet.of("variants.color"))
				.build()
		);

		/*
		 * Two red variants of one product and one of another are three red
		 * hits - not the two red products a document search would count.
		 */
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
	public void testFacetOnThePathCountsWhatMatched() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.nested(
						"variants",
						Query.field("variants.price", Matchers.lessThan(20d))
					)
				)
				.withHits("variants")
				.addFacet(Facet.of("variants.color").withOrder(Facet.Order.VALUE))
				.build()
		);

		assertThat(
			result.facets().get("variants.color").values(),
			contains(
				new SearchResult.Facet.Value("blue", 1),
				new SearchResult.Facet.Value("red", 1)
			)
		);
	}

	@Test
	public void testFacetOnAFieldOfTheIndexCountsValueHits() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withHits("variants")
				.addFacet(Facet.of("category"))
				.build()
		);

		// Two matching variants are shoes, one is a sneaker
		assertThat(
			result.facets().get("category").values(),
			contains(
				new SearchResult.Facet.Value("shoes", 2),
				new SearchResult.Facet.Value("sneakers", 1)
			)
		);
	}

	@Test
	public void testRangeFacetOnAFieldOfTheIndexCountsValueHits() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withHits("variants")
				.addFacet(
					Facet.of("popularity").withRanges(
						new Facet.Range(null, 20d),
						new Facet.Range(20d, null)
					)
				)
				.build()
		);

		assertThat(
			result.facets().get("popularity").buckets(),
			contains(
				new SearchResult.Facet.Bucket(null, 20d, 2),
				new SearchResult.Facet.Bucket(20d, null, 1)
			)
		);
	}

	@Test
	public void testFacetCountsSidewaysOfItsOwnFilter() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withFilters(Query.field("category", Matchers.equalTo("shoes")))
				.withHits("variants")
				.addFacet(Facet.of("category"))
				.build()
		);

		// The hits honour the filter, the counts leave it out
		assertThat(result.total().count(), is(2L));
		assertThat(
			result.facets().get("category").values(),
			contains(
				new SearchResult.Facet.Value("shoes", 2),
				new SearchResult.Facet.Value("sneakers", 1)
			)
		);
	}

	@Test
	public void testFacetOnAnotherPathIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withHits("variants")
					.addFacet(Facet.of("badges.label"))
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:hits:facet_unsupported"));
	}

	@Test
	public void testSortByAFieldOfTheIndexIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withHits("variants")
					.withSort(SortBy.field("popularity"))
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:hits:sort_unsupported"));
	}

	@Test
	public void testSortByAFieldInAnotherPathIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withHits("variants")
					.withSort(SortBy.field("badges.label"))
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:nested:not_in_path"));
	}

	@Test
	public void testHitsOnAFieldThatIsNotAnObjectIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withHits("name")
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:hits:not_object"));
	}

	@Test
	public void testHitsOnAFlattenedObjectIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withHits("specs")
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:hits:not_object"));
	}

	@Test
	public void testHitsOnAFieldInsideAnObjectIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withHits("variants.color")
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:hits:not_object"));
	}

	@Test
	public void testHitsOnAnUnknownFieldIsRefused() throws IOException {
		var index = products();

		assertThrows(
			IndexFieldNotFoundException.class,
			() -> index.search(
				SearchRequest.create()
					.withHits("colour")
					.build()
			)
		);
	}

	@Test
	public void testHitsCanNotAlsoAskForMatched() {
		assertThrows(
			IllegalArgumentException.class,
			() -> SearchRequest.create()
				.withHits("variants")
				.addMatched("variants")
				.build()
		);
	}

	@Test
	public void testHitsCanNotAlsoHighlight() {
		assertThrows(
			IllegalArgumentException.class,
			() -> SearchRequest.create()
				.withHits("variants")
				.addHighlight("name")
				.build()
		);
	}

	@Test
	public void testRelaxLetsGoOfWordsInValueMode() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.text(words("trail zebra")))
				.withHits("variants")
				.build()
		);

		assertThat(result.relaxed(), is(notNullValue()));
		assertThat(
			result.relaxed().dropped().collect(SearchResult.Relaxed.Dropped::word),
			contains("zebra")
		);

		// The hits of the rescued search are still values
		assertThat(result.hits().size(), is(3));
		assertThat(result.hits().getFirst().index(), is(notNullValue()));
	}

	@Test
	public void testWithoutSourceTheValueIsLeftOut() throws IOException {
		var index = productsWithoutSource();

		var result = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withHits("variants")
				.build()
		);

		assertThat(result.hits().size(), is(3));

		var hit = result.hits().detect(h -> "2".equals(h.id()));
		assertThat(hit.value(), is(nullValue()));
		assertThat(hit.index(), is(0));
	}

	@Test
	public void testFieldsCutTheDocumentAndNotTheValue() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withHits("variants")
				.withFields("name")
				.build()
		);

		var hit = result.hits().getFirst();
		assertThat(hit.document().get("name"), is(notNullValue()));
		assertThat(hit.document().get("category"), is(nullValue()));
		assertThat(hit.document().get("variants"), is(nullValue()));

		// The primary key survives, as it is what the hit is identified by
		assertThat(hit.id(), is(notNullValue()));
		assertThat(hit.value().get("color"), is("red"));
	}

	@Test
	public void testHitsFieldsCutValuesToWhatWasAsked() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withHits(new SearchRequest.Hits(
					"variants",
					Sets.immutable.of("variants.price")
				))
				.build()
		);

		var trailRunner = result.hits().select(hit -> "1".equals(hit.id()));
		assertThat(
			trailRunner.collect(hit -> hit.value().get("price")),
			contains(15d, 35d)
		);
		// The fields that were not asked for are gone, not nulled in place
		assertThat(trailRunner.getFirst().value().get("color"), is(nullValue()));

		// What the hit knows about itself is untouched by the cut
		assertThat(trailRunner.collect(SearchResult.Hit::index), contains(0, 2));
		assertThat(result.total().count(), is(3L));
	}

	@Test
	public void testHitsFieldOutsideThePathIsRefused() throws IOException {
		var index = products();

		assertThrows(
			IndexFieldNotFoundException.class,
			() -> index.search(
				SearchRequest.create()
					.withHits(new SearchRequest.Hits(
						"variants",
						Sets.immutable.of("badges.label")
					))
					.build()
			)
		);
	}

	@Test
	public void testHitsFieldUnknownInsideThePathIsRefused() throws IOException {
		var index = products();

		assertThrows(
			IndexFieldNotFoundException.class,
			() -> index.search(
				SearchRequest.create()
					.withHits(new SearchRequest.Hits(
						"variants",
						Sets.immutable.of("variants.colour")
					))
					.build()
			)
		);
	}

	@Test
	public void testHitsFieldsWithoutSourceAreRefused() throws IOException {
		var index = productsWithoutSource();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withHits(new SearchRequest.Hits(
						"variants",
						Sets.immutable.of("variants.color")
					))
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:source_not_kept"));
	}

	@Test
	public void testHitsAnswerAcrossSegments() throws IOException {
		var index = create("segments", definition());

		// A commit per document, so the walk crosses segments
		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Trail Runner"),
				new Document.Value("category", "shoes"),
				new Document.Value("popularity", 10d),
				new Document.Value("variants", variant("red", "leather", 15d)),
				new Document.Value("variants", variant("blue", "canvas", 25d))
			)
		);
		index.commit();

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "City Sneaker"),
				new Document.Value("category", "sneakers"),
				new Document.Value("popularity", 50d),
				new Document.Value("variants", variant("black", "suede", 30d)),
				new Document.Value("variants", variant("red", "canvas", 10d))
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.withQuery(red())
				.withHits("variants")
				.withSort(SortBy.field("variants.price"))
				.build()
		);

		assertThat(
			result.hits().collect(SearchResult.Hit::id).toList(),
			contains("2", "1")
		);
		assertThat(
			result.hits().collect(SearchResult.Hit::index).toList(),
			contains(1, 0)
		);
	}

	private static Query red() {
		return Query.nested(
			"variants",
			Query.field("variants.color", Matchers.equalTo("red"))
		);
	}

	private static TextMatcher words(String text) {
		return TextMatcher.of(text).withPrefix(TextMatcher.Prefix.OFF);
	}

	private Index products() throws IOException {
		var index = create("products", definition());
		addProducts(index);
		return index;
	}

	private Index productsWithoutSource() throws IOException {
		var index = create(
			"bare",
			definition().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
		);
		addProducts(index);
		return index;
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
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
				string()
					.setFilter(FilterConfig.getDefaultInstance())
					.setFacet(FacetConfig.getDefaultInstance())
					.build()
			)
			.putFields(
				"popularity",
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
			.putFields("variants", variantsField())
			.putFields("badges", badgesField(ObjectFieldTypeDef.Mode.MODE_NESTED))
			.putFields("specs", badgesField(ObjectFieldTypeDef.Mode.MODE_FLATTENED));
	}

	private static FieldDef variantsField() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setObject(
					ObjectFieldTypeDef.newBuilder()
						.putFields(
							"color",
							string()
								.setFilter(FilterConfig.getDefaultInstance())
								.setFacet(FacetConfig.getDefaultInstance())
								.build()
						)
						.putFields(
							"material",
							string(
								StringFieldTypeDef.newBuilder().setMatching(
									StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
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
								.setSort(SortConfig.getDefaultInstance())
								.build()
						)
						.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
				)
			)
			.setMultiple(true)
			.build();
	}

	private static FieldDef badgesField(ObjectFieldTypeDef.Mode mode) {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setObject(
					ObjectFieldTypeDef.newBuilder()
						.putFields(
							"label",
							string()
								.setFilter(FilterConfig.getDefaultInstance())
								.setFacet(FacetConfig.getDefaultInstance())
								.build()
						)
						.setMode(mode)
				)
			)
			.setMultiple(true)
			.build();
	}

	private static void addProducts(Index index) throws IOException {
		/*
		 * The badges sit between the variants, so a variant's position among
		 * the variants differs from its position in the block.
		 */
		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Trail Runner"),
				new Document.Value("category", "shoes"),
				new Document.Value("popularity", 10d),
				new Document.Value("variants", variant("red", "waterproof leather", 15d)),
				new Document.Value("badges", badge("eco")),
				new Document.Value("variants", variant("black", "canvas", 25d)),
				new Document.Value("badges", badge("award")),
				new Document.Value("variants", variant("red", "waterproof canvas", 35d))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "City Sneaker"),
				new Document.Value("category", "sneakers"),
				new Document.Value("popularity", 50d),
				new Document.Value("variants", variant("red", "canvas", 30d)),
				new Document.Value("variants", variant("blue", "suede", 10d))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "Plain Sandal"),
				new Document.Value("category", "sandals"),
				new Document.Value("popularity", 5d)
			)
		);

		index.commit();
	}

	private static FieldDef.Builder string() {
		return string(StringFieldTypeDef.newBuilder());
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder().setType(FieldTypeDef.newBuilder().setString(type));
	}

	private static Document variant(String color, String material, double price) {
		return new Document(
			new Document.Value("color", color),
			new Document.Value("material", material),
			new Document.Value("price", price)
		);
	}

	private static Document badge(String label) {
		return new Document(new Document.Value("label", label));
	}
}
