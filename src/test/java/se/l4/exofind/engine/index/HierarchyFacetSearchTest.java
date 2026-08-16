package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.FieldQuery;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for counting a field whose values are paths through a tree - what each
 * level counts, how far down a facet reaches, how narrowing to a subtree leaves
 * the levels beside it countable, and what a field holding no paths is refused
 * with.
 */
public class HierarchyFacetSearchTest extends AbstractIndexTest {
	@Test
	public void testCountsTheTopLevel() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("category"))
				.build()
		);

		var facet = result.facets().get("category");
		assertThat(facet.totalValues(), is(2));
		assertThat(
			facet.values(),
			contains(
				value("Men", "Men", 4),
				value("Women", "Women", 2)
			)
		);
	}

	@Test
	public void testALevelCountsEverythingFiledBelowIt() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("category").withPath("Men"))
				.build()
		);

		assertThat(
			result.facets().get("category").values(),
			contains(
				value("Shoes", "Men/Shoes", 3),
				value("Outerwear", "Men/Outerwear", 1)
			)
		);
	}

	@Test
	public void testDepthCountsTheLevelsBelow() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("category").withPath("Men").withDepth(2))
				.build()
		);

		var values = result.facets().get("category").values();
		assertThat(values.size(), is(2));
		assertLevel(values.get(0), "Shoes", "Men/Shoes", 3);
		assertLevel(values.get(1), "Outerwear", "Men/Outerwear", 1);

		var shoes = values.get(0);
		assertThat(shoes.totalChildren(), is(2));
		assertThat(
			shoes.children(),
			containsInAnyOrder(
				value("Running", "Men/Shoes/Running", 1),
				value("Casual", "Men/Shoes/Casual", 2)
			)
		);

		// The level the counting stopped at knows nothing below it
		assertThat(values.get(1).children().size(), is(0));
	}

	@Test
	public void testDepthOneAnswersNoChildren() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("category"))
				.build()
		);

		for(var value : result.facets().get("category").values()) {
			assertThat(value.children().size(), is(0));
			assertThat(value.totalChildren(), is(0));
		}
	}

	@Test
	public void testADocumentIsCountedOnceHoweverDeepItSits() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("category").withPath("Women"))
				.build()
		);

		assertThat(
			result.facets().get("category").values(),
			containsInAnyOrder(
				value("Shoes", "Women/Shoes", 1),
				value("Knitwear", "Women/Knitwear", 1)
			)
		);
	}

	@Test
	public void testADocumentFiledInTwoTreesCountsInBoth() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("id", Matchers.equalTo("5")))
				.addFacet(Facet.of("category").withDepth(2))
				.build()
		);

		var values = result.facets().get("category").values().toSortedListBy(
			level -> (String) level.value()
		);
		assertThat(values.size(), is(2));
		assertLevel(values.get(0), "Men", "Men", 1);
		assertLevel(values.get(1), "Women", "Women", 1);

		// Filed under one level of each tree, so each answers the one below it
		assertThat(
			values.get(0).children(),
			contains(value("Shoes", "Men/Shoes", 1))
		);
		assertThat(
			values.get(1).children(),
			contains(value("Shoes", "Women/Shoes", 1))
		);
	}

	@Test
	public void testLimitHoldsPerLevel() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("category").withDepth(2).withLimit(1))
				.build()
		);

		var facet = result.facets().get("category");
		assertThat(facet.totalValues(), is(2));
		assertThat(facet.values().size(), is(1));

		var men = facet.values().get(0);
		assertThat(men.value(), is("Men"));
		assertThat(men.totalChildren(), is(2));
		assertThat(men.children(), contains(value("Shoes", "Men/Shoes", 3)));
	}

	@Test
	public void testOrderByValueIsAscendingPerLevel() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.addFacet(
					Facet.of("category")
						.withPath("Men")
						.withOrder(Facet.Order.VALUE)
				)
				.build()
		);

		assertThat(
			result.facets().get("category").values(),
			contains(
				value("Outerwear", "Men/Outerwear", 1),
				value("Shoes", "Men/Shoes", 3)
			)
		);
	}

	@Test
	public void testQueryNarrowsTheCounts() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("category", Matchers.under("Men/Shoes")))
				.addFacet(Facet.of("category"))
				.build()
		);

		assertThat(
			result.facets().get("category").values(),
			contains(
				value("Men", "Men", 3),
				value("Women", "Women", 1)
			)
		);
	}

	@Test
	public void testDrillingIntoALevelKeepsItsSiblingsCountable() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.addFilter(new FieldQuery("category", Matchers.under("Men/Shoes")))
				.addFacet(Facet.of("category").withPath("Men"))
				.build()
		);

		assertThat(result.total().count(), is(3L));
		assertThat(
			result.facets().get("category").values(),
			contains(
				value("Shoes", "Men/Shoes", 3),
				value("Outerwear", "Men/Outerwear", 1)
			)
		);
	}

	@Test
	public void testUnderFindsEverythingBelowALevel() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("category", Matchers.under("Men")))
				.build()
		);

		assertThat(result.total().count(), is(4L));
	}

	@Test
	public void testUnderMatchesALevelWhole() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("category", Matchers.under("Men/Sho")))
				.build()
		);

		assertThat(result.total().count(), is(0L));
	}

	@Test
	public void testUnderIgnoresCaseTheWayFilteringDoes() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("category", Matchers.under("men/shoes")))
				.build()
		);

		assertThat(result.total().count(), is(3L));
	}

	@Test
	public void testCountingTheChildrenOfAPathIgnoresCaseTheSameWay() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("category").withPath("men"))
				.build()
		);

		assertThat(
			result.facets().get("category").values(),
			contains(
				value("Shoes", "Men/Shoes", 3),
				value("Outerwear", "Men/Outerwear", 1)
			)
		);
	}

	@Test
	public void testAPathNobodyIsFiledUnderCountsNothing() throws IOException {
		var index = catalogue();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("category").withPath("Men/Outerwear"))
				.build()
		);

		var facet = result.facets().get("category");
		assertThat(facet.values().size(), is(0));
		assertThat(facet.totalValues(), is(0));
	}

	@Test
	public void testAFieldHoldingNoPathsRefusesAPath() throws IOException {
		var index = catalogue();

		assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create()
					.addFacet(Facet.of("brand").withPath("Acme"))
					.build()
			)
		);
	}

	@Test
	public void testAFieldHoldingNoPathsRefusesUnder() throws IOException {
		var index = catalogue();

		assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create()
					.withQuery(Query.field("brand", Matchers.under("Acme")))
					.build()
			)
		);
	}

	@Test
	public void testAFieldHoldingPathsIsStillCountedWholeWhenNotFaceted() throws IOException {
		var index = catalogue();

		assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create()
					.addFacet(Facet.of("shelf"))
					.build()
			)
		);
	}

	@Test
	public void testASeparatorOfItsOwnTakesThePathApart() throws IOException {
		var index = create(
			"departments",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"place",
					faceted(
						string(
							StringFieldTypeDef.newBuilder()
								.setHierarchy(
									StringFieldTypeDef.HierarchyConfig.newBuilder()
										.setSeparator(" > ")
								)
						)
					).build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("place", "Store > Floor 2 > Toys")
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("place").withPath("Store").withDepth(2))
				.build()
		);

		var values = result.facets().get("place").values();
		assertThat(values.size(), is(1));
		assertLevel(values.get(0), "Floor 2", "Store > Floor 2", 1);
		assertThat(
			values.get(0).children(),
			contains(value("Toys", "Store > Floor 2 > Toys", 1))
		);
	}

	/**
	 * Check one level, leaving whatever it holds below it to the test that
	 * cares about it.
	 */
	private static void assertLevel(
		SearchResult.Facet.Value level,
		String label,
		String path,
		long count
	) {
		assertThat(level.value(), is(label));
		assertThat(level.path(), is(path));
		assertThat(level.count(), is(count));
	}

	private static SearchResult.Facet.Value value(String label, String path, long count) {
		return new SearchResult.Facet.Value(label, count, path, null, 0);
	}

	/**
	 * An index of products filed in a category tree, one of them in two places
	 * at once.
	 */
	private Index catalogue() throws IOException {
		var index = create(
			"catalogue",
			IndexDef.newBuilder()
				.putFields("id", filtered(string()).build())
				.putFields("category", faceted(hierarchical()).setMultiple(true).build())
				.putFields("brand", faceted(string()).build())
				.putFields("shelf", filtered(hierarchical()).build())
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("category", "Men/Shoes/Running"),
				new Document.Value("brand", "Acme"),
				new Document.Value("shelf", "A/1")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("category", "Men/Shoes/Casual"),
				new Document.Value("brand", "Acme")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("category", "Men/Outerwear"),
				new Document.Value("brand", "Nimbus")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("category", "Women/Knitwear"),
				new Document.Value("brand", "Nimbus")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "5"),
				new Document.Value("category", "Women/Shoes/Sandals"),
				new Document.Value("category", "Men/Shoes/Casual"),
				new Document.Value("brand", "Acme")
			)
		);

		index.commit();
		return index;
	}

	private static FieldDef.Builder filtered(FieldDef.Builder builder) {
		return builder.setFilter(FilterConfig.getDefaultInstance());
	}

	private static FieldDef.Builder faceted(FieldDef.Builder builder) {
		return builder
			.setFilter(FilterConfig.getDefaultInstance())
			.setFacet(FacetConfig.getDefaultInstance());
	}

	private static FieldDef.Builder hierarchical() {
		return string(
			StringFieldTypeDef.newBuilder()
				.setHierarchy(StringFieldTypeDef.HierarchyConfig.getDefaultInstance())
		);
	}

	private static FieldDef.Builder string() {
		return string(StringFieldTypeDef.newBuilder());
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(type));
	}
}
