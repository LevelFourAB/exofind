package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.RankingConfig;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.FieldSort;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.SortKey;
import se.l4.exofind.engine.query.ValueTarget;
import se.l4.exofind.engine.query.matchers.EqualsMatcher;

/**
 * Tests for the ordering by a chain of {@code when} and {@code fallback}, and
 * for the cursors taken under it.
 *
 * <p>The products, seen on list {@code cust} with the store list behind it:
 *
 * <ul>
 * <li>a: 30 on cust, 10 on store - seen at 30
 * <li>b: 20 on store only - seen at 20
 * <li>c: no price at all - missing
 * <li>d: 20 on cust - seen at 20 (a tie with b, from another step)
 * <li>e: 5 and 40 on cust - seen at 5 ascending, 40 descending
 * <li>f: no price at all - missing
 * <li>g: 20 on store, 99 on another list - seen at 20
 * </ul>
 */
public class ChainSortOrderingTest extends AbstractIndexTest {
	private static final List<String> ASCENDING = List.of("e", "b", "d", "g", "a", "c", "f");

	@Test
	public void pagingAscendingOneHitAtATimeVisitsEveryDocumentOnce() throws IOException {
		// Given
		var index = products(false);
		var whole = ids(index.search(request(seen(SortBy.Order.ASCENDING), 100, null, null)));

		// When
		var paged = pageForwards(index, seen(SortBy.Order.ASCENDING), 1);

		// Then
		assertThat(whole, is(ASCENDING));
		assertThat(paged, is(whole));
	}

	@Test
	public void pagingDescendingOneHitAtATimeVisitsEveryDocumentOnce() throws IOException {
		// Given
		var index = products(false);
		var whole = ids(index.search(request(seen(SortBy.Order.DESCENDING), 100, null, null)));

		// When
		var paged = pageForwards(index, seen(SortBy.Order.DESCENDING), 1);

		// Then
		assertThat(whole.size(), is(7));
		assertThat(paged, is(whole));
	}

	@Test
	public void pagingBackwardsOneHitAtATimeFromTheLastHitVisitsEveryDocumentOnce()
		throws IOException
	{
		// Given
		var index = products(false);
		var sort = seen(SortBy.Order.ASCENDING);
		var whole = index.search(request(sort, 100, null, null));
		var last = whole.hits().getLast();

		// When
		var paged = new ArrayList<String>();
		paged.add(last.id().toString());
		var before = last.key();
		for(var i = 0; i < 20; i++) {
			var page = index.search(request(sort, 1, null, before));
			if(page.hits().isEmpty()) {
				break;
			}

			paged.add(0, page.hits().getFirst().id().toString());
			before = page.hits().getFirst().key();
		}

		// Then
		assertThat(paged, is(ids(whole)));
	}

	@Test
	public void pagingAcrossSegmentsOneHitAtATimeVisitsEveryDocumentOnce() throws IOException {
		// Given
		var index = products(true);

		// When
		var paged = pageForwards(index, seen(SortBy.Order.ASCENDING), 1);

		// Then
		assertThat(paged, is(ASCENDING));
	}

	@Test
	public void pagingTwoAtATimeMatchesTheWholeOrder() throws IOException {
		// Given
		var index = products(true);

		// When
		var paged = pageForwards(index, seen(SortBy.Order.DESCENDING), 2);
		var whole = ids(index.search(request(seen(SortBy.Order.DESCENDING), 100, null, null)));

		// Then
		assertThat(paged, is(whole));
	}

	/**
	 * Negative numbers, signed zero, and the largest finite value of a double
	 * come from both steps of the chain. The chain must order them the way a
	 * plain sort over one field orders the same values.
	 */
	@Test
	public void negativeAndSignedZeroValuesOrderLikeAPlainSort() throws IOException {
		// Given
		var index = create("signed", definition());
		index.addDocument(product("n1", null, -10.5));
		index.addDocument(product("n2", -0.0, null));
		index.addDocument(product("n3", null, 0.0));
		index.addDocument(product("n4", -1e300, null));
		index.addDocument(product("n5", null, Double.MAX_VALUE));
		index.addDocument(product("n6", null, -Double.MAX_VALUE));
		index.commit();

		var sort = new FieldSort("cust", SortBy.Order.ASCENDING)
			.withFallback(ValueTarget.of("store"));

		// When
		var ascending = ids(index.search(request(sort, 100, null, null)));
		var paged = pageForwards(index, sort, 1);

		// Then
		assertThat(ascending, contains("n6", "n4", "n1", "n2", "n3", "n5"));
		assertThat(paged, is(ascending));
	}

	/**
	 * The index breaks ties by {@code cust} descending. A chain sort that
	 * starts at {@code cust} still leaves ties - between a document read on
	 * {@code cust} and one read on its fallback - and those must still be
	 * broken by the tie breaker the index declares.
	 */
	@Test
	public void tieBreakerOnTheHeadFieldStillBreaksTiesOfAChain() throws IOException {
		// Given
		var index = create(
			"tied",
			definition().setRanking(
				RankingConfig.newBuilder()
					.addTieBreakers(
						RankingConfig.TieBreaker.newBuilder()
							.setField("cust")
							.setDirection(RankingConfig.TieBreaker.Direction.DIRECTION_ASCENDING)
					)
			)
		);

		// Added in this order, so doc id order puts the store price first
		index.addDocument(product("store-only", null, 20.0));
		index.addDocument(product("customer", 20.0, null));
		index.commit();

		var sort = new FieldSort("cust", SortBy.Order.ASCENDING)
			.withFallback(ValueTarget.of("store"));

		// When
		var result = ids(index.search(request(sort, 100, null, null)));

		// Then: the tie breaker reads cust 20 before a missing cust
		assertThat(result, contains("customer", "store-only"));
	}

	/**
	 * A product replaced with new prices leaves its old block of values
	 * deleted but still in the segment. The chain must read the new block
	 * only.
	 */
	@Test
	public void replacedDocumentIsOrderedByItsNewValuesOnly() throws IOException {
		// Given
		var index = products(false);

		// a was seen at 30; now only a store price of 1
		index.addDocument(nested("a", priced("store", 1.0)));
		// e was seen at 5; now 500 on cust
		index.addDocument(nested("e", priced("cust", 500.0)));
		index.commit();

		// When
		var result = ids(index.search(request(seen(SortBy.Order.ASCENDING), 100, null, null)));
		var paged = pageForwards(index, seen(SortBy.Order.ASCENDING), 1);

		// Then
		assertThat(result, contains("a", "b", "d", "g", "e", "c", "f"));
		assertThat(paged, is(result));
	}

	/**
	 * An index with no object fields at all, so no Lucene document is a
	 * nested value.
	 */
	@Test
	public void chainOnAnIndexWithoutObjectFieldsOrdersEveryDocument() throws IOException {
		// Given
		var index = create(
			"flat",
			IndexDef.newBuilder()
				.putFields(
					"id",
					string().setPrimaryKey(true).setFilter(FilterConfig.getDefaultInstance()).build()
				)
				.putFields("cust", price().build())
				.putFields("store", price().build())
		);
		index.addDocument(product("x", null, 3.0));
		index.addDocument(product("y", 2.0, 1.0));
		index.addDocument(product("z", null, null));
		index.addDocument(product("w", 1.5, null));
		index.commit();

		var sort = new FieldSort("cust", SortBy.Order.ASCENDING)
			.withFallback(ValueTarget.of("store"));

		// When
		var result = ids(index.search(request(sort, 100, null, null)));

		// Then
		assertThat(result, contains("w", "y", "x", "z"));
	}

	private static List<String> pageForwards(Index index, SortBy sort, int limit)
		throws IOException
	{
		var seen = new ArrayList<String>();
		SortKey after = null;
		for(var i = 0; i < 50; i++) {
			var page = index.search(request(sort, limit, after, null));
			if(page.hits().isEmpty()) {
				break;
			}

			seen.addAll(ids(page));
			after = page.hits().getLast().key();
		}

		return seen;
	}

	private static SearchRequest request(SortBy sort, int limit, SortKey after, SortKey before) {
		var builder = SearchRequest.create()
			.withSort(sort)
			.withLimit(limit);

		if(after != null) {
			builder = builder.withAfter(after);
		}

		if(before != null) {
			builder = builder.withBefore(before);
		}

		return builder.build();
	}

	private static FieldSort seen(SortBy.Order order) {
		var sort = new FieldSort("prices.amount", order)
			.withWhen(listIs("cust"))
			.withFallback(ValueTarget.of("prices.amount").withWhen(listIs("store")));
		return sort;
	}

	private static Query listIs(String list) {
		return Query.field("prices.list", new EqualsMatcher(list));
	}

	private Index products(boolean split) throws IOException {
		var index = create(split ? "split" : "products", definition());

		var products = List.of(
			nested("a", priced("cust", 30.0), priced("store", 10.0)),
			nested("b", priced("store", 20.0)),
			nested("c"),
			nested("d", priced("cust", 20.0)),
			nested("e", priced("cust", 5.0), priced("cust", 40.0)),
			nested("f"),
			nested("g", priced("store", 20.0), priced("other", 99.0))
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

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				string().setPrimaryKey(true).setFilter(FilterConfig.getDefaultInstance()).build()
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
			.putFields("cust", price().build())
			.putFields("store", price().build())
			.putFields("stock", count().build())
			.putFields("reserve", count().build());
	}

	private static Document nested(String id, Document... prices) {
		var values = new ArrayList<Document.Value>();
		values.add(new Document.Value("id", id));
		for(var price : prices) {
			values.add(new Document.Value("prices", price));
		}

		return new Document(values.toArray(new Document.Value[0]));
	}

	private static Document product(String id, Double cust, Double store) {
		var values = new ArrayList<Document.Value>();
		values.add(new Document.Value("id", id));
		if(cust != null) {
			values.add(new Document.Value("cust", cust));
		}

		if(store != null) {
			values.add(new Document.Value("store", store));
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
			.setType(FieldTypeDef.newBuilder().setDouble(DoubleFieldTypeDef.getDefaultInstance()))
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
		return FieldDef.newBuilder()
			.setType(FieldTypeDef.newBuilder().setString(StringFieldTypeDef.newBuilder()));
	}

	private static List<String> ids(SearchResult result) {
		return Lists.mutable.withAll(result.hits().collect(hit -> hit.id().toString()));
	}
}
