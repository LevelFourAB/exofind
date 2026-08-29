package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.BooleanFieldTypeDef;
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
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for searches that answer with the values of an object field only for
 * the documents named by {@link SearchRequest.Hits#when()}, and with the
 * document itself for the rest.
 *
 * The catalogue holds a product marked to answer as its variants and two that
 * are not, one of which has no variants at all. The product with the mark set
 * and nothing to expand into is added only where it is what is being tested,
 * as it is the one shape that answers with no hit.
 */
public class PerDocumentHitsSearchTest extends AbstractIndexTest {
	@Test
	public void testMarkedDocumentsExpandAndTheRestDoNot() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withHits(splitVariants())
				.build()
		);

		/*
		 * The Trail Runner answers with three variants; the other two products
		 * answer with themselves, so one page holds both kinds of hit.
		 */
		assertThat(
			result.hits().collect(hit -> hit.id() + "/" + hit.index()).toList(),
			containsInAnyOrder("1/0", "1/1", "1/2", "2/null", "3/null")
		);

		var expanded = result.hits().detect(hit -> hit.index() != null);
		assertThat(expanded.value(), is(notNullValue()));
		assertThat(expanded.document().get("name"), is("Trail Runner"));

		var itself = result.hits().detect(hit -> "2".equals(hit.id()));
		assertThat(itself.value(), is(nullValue()));
		assertThat(itself.document().get("name"), is("City Sneaker"));
	}

	@Test
	public void testTotalCountsHitsAndDocumentsCountsDocuments() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withHits(splitVariants())
				.withLimit(2)
				.withTotal(SearchRequest.Total.EXACT)
				.build()
		);

		// Three variants of the Trail Runner and two products as themselves
		assertThat(result.total().count(), is(5L));

		// The three products those five hits came from
		assertThat(result.documents(), is(notNullValue()));
		assertThat(result.documents().count(), is(3L));
	}

	@Test
	public void testCountsAreTheSameWhenOnlyFacetsAreAskedFor() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withHits(splitVariants())
				.withFacets(Facet.of("category"))
				.withLimit(0)
				.build()
		);

		assertThat(result.total().count(), is(5L));
		assertThat(result.documents().count(), is(3L));
	}

	@Test
	public void testDocumentsIsOnlyAnsweredWhereItDiffersFromTheTotal()
		throws IOException
	{
		var index = products();

		var documents = index.search(SearchRequest.all());
		assertThat(documents.documents(), is(nullValue()));

		var values = index.search(
			SearchRequest.create().withHits("variants").build()
		);
		assertThat(values.documents(), is(nullValue()));
	}

	@Test
	public void testAMarkedDocumentWithNoValuesAnswersWithNoHit()
		throws IOException
	{
		var index = products();
		addMarkedWithoutVariants(index);

		var result = index.search(
			SearchRequest.create()
				.withHits(splitVariants())
				.build()
		);

		// The Ghost Boot is marked to answer as its variants and has none
		assertThat(
			result.hits().collect(SearchResult.Hit::id).toSet(),
			containsInAnyOrder("1", "2", "3")
		);

		// It still matched, so the documents the facets count include it
		assertThat(result.documents().count(), is(4L));
	}

	@Test
	public void testFacetsCountDocumentsWhateverTheyAnswerAs() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withHits(splitVariants())
				.withFacets(Facet.of("category"))
				.build()
		);

		/*
		 * The Trail Runner answers with three hits and the others with one
		 * each, and every one of them is still one product of its category.
		 */
		assertThat(
			result.facets().get("category").values()
				.collect(value -> value.value() + "=" + value.count())
				.toList(),
			containsInAnyOrder("shoes=1", "sneakers=1", "sandals=1")
		);
	}

	@Test
	public void testFacetsAreCountedSidewaysOfTheirOwnFilter() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withFilters(Query.field("category", Matchers.equalTo("shoes")))
				.withHits(splitVariants())
				.withFacets(Facet.of("category"))
				.build()
		);

		// Only the Trail Runner is left, expanded into its three variants
		assertThat(result.hits().size(), is(3));

		/*
		 * The facet leaves its own filter out of its scope, and counts the
		 * documents that would be left rather than the hits they answer with.
		 */
		assertThat(
			result.facets().get("category").values()
				.collect(value -> value.value() + "=" + value.count())
				.toList(),
			containsInAnyOrder("shoes=1", "sneakers=1", "sandals=1")
		);
	}

	@Test
	public void testEveryHitScoresWhatItsDocumentScored() throws IOException {
		var index = products();

		var query = Query.nested(
			"variants",
			Query.text("canvas").withField("variants.material")
		);

		var documents = index.search(
			SearchRequest.create().withQuery(query).build()
		);

		var result = index.search(
			SearchRequest.create()
				.withQuery(query)
				.withHits(splitVariants())
				.build()
		);

		/*
		 * Were the value's own score added to its document's, every expanded
		 * document would sit above one that answered as itself whatever the
		 * two documents scored - an order decided by how a result is displayed
		 * rather than by how well it matched.
		 */
		for(var hit : result.hits()) {
			assertThat(
				hit.score(),
				is(documents.hits().detect(d -> d.id().equals(hit.id())).score())
			);
		}

		// The Trail Runner is expanded, so there is a value hit to have checked
		assertThat(result.hits().count(hit -> hit.index() != null), is(2));
	}

	@Test
	public void testCursorsWalkThroughHitsOfBothKinds() throws IOException {
		var index = products();

		var walked = Lists.mutable.<String>empty();
		SearchResult page = null;
		do {
			var request = SearchRequest.create()
				.withHits(splitVariants())
				.withLimit(2);

			if(page != null) {
				request = request.withAfter(page.hits().getLast().key());
			}

			page = index.search(request.build());
			page.hits().collect(hit -> hit.id() + "/" + hit.index())
				.forEach(walked::add);
		} while(page.hits().size() == 2);

		// Every hit is reached once, whichever kind it is
		assertThat(walked, containsInAnyOrder("1/0", "1/1", "1/2", "2/null", "3/null"));
	}

	@Test
	public void testOrderingByAFieldIsRefused() throws IOException {
		var index = products();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withHits(splitVariants())
					.withSort(SortBy.field("variants.price"))
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:hits:when_sort_unsupported"));
	}

	@Test
	public void testExplainReadsTheDocumentForOneThatAnswersAsItself()
		throws IOException
	{
		var index = products();

		var request = SearchRequest.create()
			.withQuery(Query.field("category", Matchers.equalTo("sneakers")))
			.withHits(splitVariants())
			.build();

		/*
		 * The City Sneaker is a hit standing for itself, so the position it
		 * would have had among values names nothing and is not read.
		 */
		var explanation = index.explain(request, "2", 0, null);

		assertThat(explanation.matched(), is(true));
	}

	private Index products() throws IOException {
		var index = create("products", definition());

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Trail Runner"),
				new Document.Value("category", "shoes"),
				new Document.Value("split", true),
				new Document.Value("variants", variant("red", "waterproof leather", 15d)),
				new Document.Value("variants", variant("black", "canvas", 25d)),
				new Document.Value("variants", variant("red", "waterproof canvas", 35d))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "City Sneaker"),
				new Document.Value("category", "sneakers"),
				new Document.Value("split", false),
				new Document.Value("variants", variant("red", "canvas", 30d)),
				new Document.Value("variants", variant("blue", "suede", 10d))
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "Plain Sandal"),
				new Document.Value("category", "sandals"),
				new Document.Value("split", false)
			)
		);

		index.commit();
		return index;
	}

	/**
	 * Add the product that is marked to answer as its variants and has none,
	 * which is what a merchant who ticked the flag and filled nothing in
	 * leaves behind.
	 */
	private static void addMarkedWithoutVariants(Index index) throws IOException {
		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("name", "Ghost Boot"),
				new Document.Value("category", "boots"),
				new Document.Value("split", true)
			)
		);

		index.commit();
	}

	/**
	 * Answer with the variants of the products the catalogue marks, and with
	 * the document itself for every other product.
	 */
	private static SearchRequest.Hits splitVariants() {
		return new SearchRequest.Hits(
			"variants",
			null,
			Lists.immutable.of(Query.field("split", Matchers.equalTo(true)))
		);
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				string().setPrimaryKey(true).setFilter(FilterConfig.getDefaultInstance()).build()
			)
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
				"split",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder().setBoolean(
							BooleanFieldTypeDef.getDefaultInstance()
						)
					)
					.setFilter(FilterConfig.getDefaultInstance())
					.build()
			)
			.putFields("variants", variantsField());
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
}
