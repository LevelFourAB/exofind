package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
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
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.FieldSort;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.ValueTarget;
import se.l4.exofind.engine.query.matchers.EqualsMatcher;
import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Tests for ordering and counting by the value a chain of {@code when} and
 * {@code fallback} reads for each document - the price a customer sees, on
 * their own list or else on the store's list.
 *
 * <p>The products, and the price each one is seen at on list {@code cust-17}
 * with the store's list behind it:
 *
 * <ul>
 * <li>1: 89 on the customer's list, 129 on the store's - seen at 89
 * <li>2: 79 on the store's list only, and a sale price of 75 - seen at 79
 * <li>3: 149 on the customer's list, 99 on the store's - seen at 149
 * <li>4: 50 and 70 on the customer's list, 60 on the store's - seen at 50 or
 *   70, whichever end an ordering asks for
 * <li>5: no list price, and a sale price of 10 - seen at no price at all
 * </ul>
 */
public class FallbackValueSearchTest extends AbstractIndexTest {
	@Test
	public void testOrderingReadsTheCustomerPriceAndElseTheStorePrice() throws IOException {
		var index = pricelists();

		var result = index.search(
			SearchRequest.create()
				.withSort(seenPrice())
				.build()
		);

		// Product 5 holds no price on either list, and files last
		assertThat(ids(result), contains("4", "2", "1", "3", "5"));
	}

	@Test
	public void testOrderingDescendingReadsTheHighestValueOfTheStep() throws IOException {
		var index = pricelists();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.nested("prices"))
				.withSort(seenPrice().descending())
				.build()
		);

		// Product 4 stands for 70 on its own list, not 60 on the store's
		assertThat(ids(result), contains("3", "1", "2", "4"));
	}

	/**
	 * The chain says which values are read, so a {@code nested} clause of the
	 * search narrows the documents and nothing else. Ordered by the store
	 * prices it matched, the list would read 4, 2, 3, 1.
	 */
	@Test
	public void testNestedClausesOfTheSearchDoNotNarrowTheValues() throws IOException {
		var index = pricelists();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.nested("prices", listIs("store")))
				.withSort(seenPrice())
				.build()
		);

		assertThat(ids(result), contains("4", "2", "1", "3"));
	}

	@Test
	public void testWhenWithoutFallbackLeavesTheOthersMissing() throws IOException {
		var index = pricelists();

		var result = index.search(
			SearchRequest.create()
				.withSort(new FieldSort("prices.amount", null).withWhen(listIs("cust-17")))
				.build()
		);

		assertThat(ids(result), contains("4", "1", "3", "2", "5"));
	}

	@Test
	public void testFallbackToAFieldOfTheIndex() throws IOException {
		var index = pricelists();

		var result = index.search(
			SearchRequest.create()
				.withSort(
					new FieldSort("prices.amount", null)
						.withWhen(listIs("cust-17"))
						.withFallback(ValueTarget.of("sale_price"))
				)
				.build()
		);

		assertThat(ids(result), contains("5", "4", "2", "1", "3"));
	}

	@Test
	public void testWhenOfAFieldOfTheIndexHoldsForTheDocument() throws IOException {
		var index = pricelists();

		var result = index.search(
			SearchRequest.create()
				.withSort(
					new FieldSort("prices.amount", null)
						.withWhen(listIs("cust-17"))
						.withFallback(
							ValueTarget.of("sale_price")
								.withWhen(Query.field("id", new EqualsMatcher("5")))
						)
				)
				.build()
		);

		// The sale price of product 2 is not read, as its clause does not hold
		assertThat(ids(result), contains("5", "4", "1", "3", "2"));
	}

	@Test
	public void testFallbackOfAFallbackIsReadBeforeTheNextFallback() throws IOException {
		var index = pricelists();

		var result = index.search(
			SearchRequest.create()
				.withSort(
					new FieldSort("prices.amount", null)
						.withWhen(listIs("wholesale"))
						.withFallback(
							onList("cust-17").withFallback(ValueTarget.of("sale_price")),
							onList("store")
						)
				)
				.build()
		);

		/*
		 * Nothing is on the wholesale list. Product 2 has no customer price
		 * and is read at its sale price of 75 rather than its store price.
		 */
		assertThat(ids(result), contains("5", "4", "2", "1", "3"));
	}

	@Test
	public void testPagingThroughAnOrderingByAChain() throws IOException {
		var index = pricelists();

		var first = index.search(
			SearchRequest.create()
				.withSort(seenPrice())
				.withLimit(2)
				.build()
		);

		assertThat(ids(first), contains("4", "2"));

		var next = index.search(
			SearchRequest.create()
				.withSort(seenPrice())
				.withLimit(2)
				.withAfter(first.hits().getLast().key())
				.build()
		);

		assertThat(ids(next), contains("1", "3"));

		var back = index.search(
			SearchRequest.create()
				.withSort(seenPrice())
				.withLimit(2)
				.withBefore(next.hits().getFirst().key())
				.build()
		);

		assertThat(ids(back), contains("4", "2"));
	}

	@Test
	public void testOrderingAndCountingAcrossSegments() throws IOException {
		var index = pricelists(true);

		var result = index.search(
			SearchRequest.create()
				.withSort(seenPrice())
				.addFacet(seenPriceBuckets())
				.build()
		);

		assertThat(ids(result), contains("4", "2", "1", "3", "5"));
		assertThat(
			result.facets().get("prices.amount").buckets(),
			contains(
				new SearchResult.Facet.Bucket(0d, 80d, 2),
				new SearchResult.Facet.Bucket(80d, 100d, 1),
				new SearchResult.Facet.Bucket(100d, null, 1)
			)
		);
	}

	/**
	 * A chain of whole numbers on fields of the index, in an index that also
	 * holds nested values between its documents.
	 */
	@Test
	public void testOrderingByAChainOfWholeNumbers() throws IOException {
		var index = pricelists();

		var result = index.search(
			SearchRequest.create()
				.withSort(new FieldSort("stock", null).withFallback(ValueTarget.of("reserve")))
				.build()
		);

		// Stock 5 and 2, and else the reserve of 7, 4 and 9
		assertThat(ids(result), contains("3", "4", "1", "2", "5"));
	}

	@Test
	public void testFacetCountsTheCustomerPriceAndElseTheStorePrice() throws IOException {
		var index = pricelists();

		var result = index.search(
			SearchRequest.create()
				.addFacet(seenPriceBuckets())
				.build()
		);

		/*
		 * Counted by every price, product 1 and 3 would each be in two
		 * buckets. Counted by the price they are seen at, each product is in
		 * the bucket of that price, and product 5 is in none.
		 */
		assertThat(
			result.facets().get("prices.amount").buckets(),
			contains(
				new SearchResult.Facet.Bucket(0d, 80d, 2),
				new SearchResult.Facet.Bucket(80d, 100d, 1),
				new SearchResult.Facet.Bucket(100d, null, 1)
			)
		);
	}

	@Test
	public void testFacetIsNotNarrowedByNestedClausesOfTheSearch() throws IOException {
		var index = pricelists();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.nested("prices", listIs("store")))
				.addFacet(seenPriceBuckets())
				.build()
		);

		assertThat(
			result.facets().get("prices.amount").buckets(),
			contains(
				new SearchResult.Facet.Bucket(0d, 80d, 2),
				new SearchResult.Facet.Bucket(80d, 100d, 1),
				new SearchResult.Facet.Bucket(100d, null, 1)
			)
		);
	}

	/**
	 * A facet answered before is kept by what it counts, so a facet reading
	 * another chain over the same search must not be answered from it.
	 */
	@Test
	public void testFacetsOverDifferentChainsAreKeptApart() throws IOException {
		var index = pricelists();

		var seen = index.search(
			SearchRequest.create()
				.addFacet(seenPriceBuckets())
				.build()
		);
		var customerOnly = index.search(
			SearchRequest.create()
				.addFacet(
					Facet.of("prices.amount")
						.withRanges(buckets())
						.withWhen(listIs("cust-17"))
				)
				.build()
		);

		assertThat(
			seen.facets().get("prices.amount").buckets().get(0),
			is(new SearchResult.Facet.Bucket(0d, 80d, 2))
		);
		assertThat(
			customerOnly.facets().get("prices.amount").buckets(),
			contains(
				new SearchResult.Facet.Bucket(0d, 80d, 1),
				new SearchResult.Facet.Bucket(80d, 100d, 1),
				new SearchResult.Facet.Bucket(100d, null, 1)
			)
		);
	}

	/**
	 * The filter a number in the search box is read as, the order and the
	 * counts all read the same price, so the page, its order and its counts
	 * agree.
	 */
	@Test
	public void testReadingOrderingAndCountingAgree() throws IOException {
		var index = pricelists();

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.text(
						TextMatcher.of("rain under 100").withMatch(TextMatcher.Match.USER)
					).withTargets(onList("cust-17").withFallback(onList("store")))
				)
				.withSort(seenPrice())
				.addFacet(seenPriceBuckets())
				.build()
		);

		assertThat(ids(result), contains("4", "2", "1"));
		assertThat(
			result.facets().get("prices.amount").buckets(),
			contains(
				new SearchResult.Facet.Bucket(0d, 80d, 2),
				new SearchResult.Facet.Bucket(80d, 100d, 1),
				new SearchResult.Facet.Bucket(100d, null, 0)
			)
		);
	}

	/**
	 * Where the hits are values, each value counts into the bucket of the
	 * price its product is seen at.
	 */
	@Test
	public void testFacetOverValueHitsCountsEachValueByItsDocument() throws IOException {
		var index = pricelists();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.nested("prices", listIs("store")))
				.withHits("prices")
				.addFacet(seenPriceBuckets())
				.build()
		);

		assertThat(result.hits().size(), is(4));
		assertThat(
			result.facets().get("prices.amount").buckets(),
			contains(
				new SearchResult.Facet.Bucket(0d, 80d, 2),
				new SearchResult.Facet.Bucket(80d, 100d, 1),
				new SearchResult.Facet.Bucket(100d, null, 1)
			)
		);
	}

	@Test
	public void testOrderingValueHitsByAChainIsRefused() throws IOException {
		var index = pricelists();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withHits("prices")
					.withSort(seenPrice())
					.build()
			)
		);

		assertThat(e.getCode(), is("search:hits:sort_fallback_unsupported"));
	}

	@Test
	public void testOrderingAChainOfTextIsRefused() throws IOException {
		var index = pricelists();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withSort(
						new FieldSort("name", null).withFallback(ValueTarget.of("sale_price"))
					)
					.build()
			)
		);

		assertThat(e.getCode(), is("search:sort:type_unsupported"));
	}

	@Test
	public void testOrderingByAFallbackOfAnotherTypeIsRefused() throws IOException {
		var index = pricelists();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withSort(seenPrice().withFallback(ValueTarget.of("stock")))
					.build()
			)
		);

		assertThat(e.getCode(), is("search:sort:fallback_type_mismatch"));
	}

	@Test
	public void testOrderingByAFallbackThatIsNotSortedIsRefused() throws IOException {
		var index = pricelists();

		assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create()
					.withSort(seenPrice().withFallback(ValueTarget.of("weight")))
					.build()
			)
		);
	}

	@Test
	public void testWhenNamingAFieldOutsideTheListIsRefused() throws IOException {
		var index = pricelists();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withSort(
						new FieldSort("prices.amount", null)
							.withWhen(Query.field("id", new EqualsMatcher("1")))
					)
					.build()
			)
		);

		assertThat(e.getCode(), is("search:nested:field_not_inside"));
	}

	@Test
	public void testFacetPerValueWithAChainIsRefused() throws IOException {
		var index = pricelists();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.addFacet(Facet.of("prices.amount").withWhen(listIs("cust-17")))
					.build()
			)
		);

		assertThat(e.getCode(), is("search:facet:fallback_unsupported"));
	}

	@Test
	public void testFacetByAFallbackOfAnotherTypeIsRefused() throws IOException {
		var index = pricelists();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.addFacet(
						Facet.of("prices.amount")
							.withRanges(buckets())
							.withFallback(ValueTarget.of("stock"))
					)
					.build()
			)
		);

		assertThat(e.getCode(), is("search:facet:fallback_type_mismatch"));
	}

	/**
	 * Order by the price on the customer's list, and by the price on the
	 * store's list where a product has none on the customer's.
	 */
	private static FieldSort seenPrice() {
		return new FieldSort("prices.amount", SortBy.Order.ASCENDING)
			.withWhen(listIs("cust-17"))
			.withFallback(onList("store"));
	}

	private static Facet seenPriceBuckets() {
		return Facet.of("prices.amount")
			.withRanges(buckets())
			.withWhen(listIs("cust-17"))
			.withFallback(onList("store"));
	}

	private static List<Facet.Range> buckets() {
		return List.of(
			new Facet.Range(0d, 80d),
			new Facet.Range(80d, 100d),
			new Facet.Range(100d, null)
		);
	}

	private static Query listIs(String list) {
		return Query.field("prices.list", new EqualsMatcher(list));
	}

	private static ValueTarget onList(String list) {
		return ValueTarget.of("prices.amount").withWhen(listIs(list));
	}

	private Index pricelists() throws IOException {
		return pricelists(false);
	}

	/**
	 * @param split
	 *   whether to commit after every document, so the documents sit in
	 *   segments of their own
	 */
	private Index pricelists(boolean split) throws IOException {
		var index = create(
			"pricelists",
			IndexDef.newBuilder()
				.putFields(
					"id",
					string().setPrimaryKey(true).setFilter(FilterConfig.getDefaultInstance()).build()
				)
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(StringFieldTypeDef.TextUsageConfig.getDefaultInstance())
					)
						.setSort(SortConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"prices",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.putFields(
										"list",
										string().setFilter(FilterConfig.getDefaultInstance()).build()
									)
									.putFields("amount", price().build())
									.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
							)
						)
						.setMultiple(true)
						.build()
				)
				.putFields("sale_price", price().build())
				.putFields(
					"weight",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder().setDouble(DoubleFieldTypeDef.getDefaultInstance())
						)
						.setFilter(FilterConfig.getDefaultInstance())
						.build()
				)
				.putFields("stock", count().build())
				.putFields("reserve", count().build())
		);

		var products = List.of(
			product("1", null, 5, 1, priced("cust-17", 89.0), priced("store", 129.0)),
			product("2", 75.0, null, 7, priced("store", 79.0)),
			product("3", null, 2, 1, priced("cust-17", 149.0), priced("store", 99.0)),
			product(
				"4",
				null,
				null,
				4,
				priced("cust-17", 50.0),
				priced("store", 60.0),
				priced("cust-17", 70.0)
			),
			product("5", 10.0, null, 9)
		);

		for(var product : products) {
			index.addDocument(product);

			if(split) {
				index.commit();
			}
		}

		index.commit();
		return index;
	}

	private static Document product(
		String id,
		Double salePrice,
		Integer stock,
		int reserve,
		Document... prices
	) {
		var values = new java.util.ArrayList<Document.Value>();
		values.add(new Document.Value("id", id));
		values.add(new Document.Value("name", "Rain product " + id));
		values.add(new Document.Value("weight", 1.0));
		values.add(new Document.Value("reserve", reserve));
		if(salePrice != null) {
			values.add(new Document.Value("sale_price", salePrice));
		}

		if(stock != null) {
			values.add(new Document.Value("stock", stock));
		}

		for(var price : prices) {
			values.add(new Document.Value("prices", price));
		}

		return new Document(values.toArray(new Document.Value[0]));
	}

	private static Document priced(String list, double amount) {
		return new Document(
			new Document.Value("list", list),
			new Document.Value("amount", amount)
		);
	}

	private static FieldDef.Builder price() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setDouble(DoubleFieldTypeDef.newBuilder().setUnit("SEK"))
			)
			.setFilter(FilterConfig.getDefaultInstance())
			.setSort(SortConfig.getDefaultInstance())
			.setFacet(FacetConfig.getDefaultInstance());
	}

	private static FieldDef.Builder count() {
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setInt32(Int32FieldTypeDef.getDefaultInstance()))
			.setSort(SortConfig.getDefaultInstance())
			.setFacet(FacetConfig.getDefaultInstance());
	}

	private static FieldDef.Builder string() {
		return string(StringFieldTypeDef.newBuilder());
	}

	private static FieldDef.Builder string(StringFieldTypeDef.Builder type) {
		return FieldDef.newBuilder().setType(FieldTypeDef.newBuilder().setString(type));
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
