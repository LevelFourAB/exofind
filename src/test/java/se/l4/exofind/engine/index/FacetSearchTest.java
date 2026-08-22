package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.schema.BooleanFieldTypeDef;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FacetConfig;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int32FieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.schema.TimestampFieldTypeDef;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.FieldQuery;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for counting the matches of a search per value of a field and into
 * range buckets - what the counts hold for each type, how filters are left
 * out of the counts on their own field, and how a search that cannot be
 * counted is refused.
 */
public class FacetSearchTest extends AbstractIndexTest {
	@Test
	public void testCountsEverythingWithoutAQuery() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("category"))
				.build()
		);

		var facet = result.facets().get("category");
		assertThat(facet.totalValues(), is(2));
		assertThat(
			facet.values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("shoes", 2),
				new SearchResult.Facet.Value("clothes", 2)
			)
		);
	}

	@Test
	public void testCountsWithoutAQueryFollowACommit() throws IOException {
		var index = products();

		var request = SearchRequest.create()
			.addFacet(Facet.of("category"))
			.build();

		// The first search is what a later one may be answered from
		index.search(request);

		index.addDocument(
			new Document(
				new Document.Value("id", "5"),
				new Document.Value("name", "Sandal"),
				new Document.Value("category", "shoes")
			)
		);
		index.commit();

		var result = index.search(request);
		assertThat(result.total().count(), is(5L));
		assertThat(
			result.facets().get("category").values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("shoes", 3),
				new SearchResult.Facet.Value("clothes", 2)
			)
		);
	}

	@Test
	public void testOrderByValueIsAscending() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("category").withOrder(Facet.Order.VALUE))
				.build()
		);

		assertThat(
			result.facets().get("category").values(),
			contains(
				new SearchResult.Facet.Value("clothes", 2),
				new SearchResult.Facet.Value("shoes", 2)
			)
		);
	}

	@Test
	public void testLimitCutsValuesButNotTotalValues() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("tags").withLimit(1))
				.build()
		);

		var facet = result.facets().get("tags");
		assertThat(facet.values(), contains(new SearchResult.Facet.Value("sale", 2)));
		assertThat(facet.totalValues(), is(2));
	}

	@Test
	public void testEveryValueOfADocumentIsCounted() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("tags"))
				.build()
		);

		assertThat(
			result.facets().get("tags").values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("sale", 2),
				new SearchResult.Facet.Value("outdoor", 1)
			)
		);
	}

	@Test
	public void testQueryNarrowsCounts() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("published", Matchers.equalTo(true)))
				.addFacet(Facet.of("category"))
				.build()
		);

		assertThat(
			result.facets().get("category").values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("shoes", 2),
				new SearchResult.Facet.Value("clothes", 1)
			)
		);
	}

	@Test
	public void testFilterOnAnotherFieldNarrowsCounts() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFilter(new FieldQuery("category", Matchers.equalTo("shoes")))
				.addFacet(Facet.of("published"))
				.build()
		);

		var facet = result.facets().get("published");
		assertThat(facet.values(), contains(new SearchResult.Facet.Value(true, 2)));
		assertThat(facet.totalValues(), is(1));
	}

	@Test
	public void testFilterOnTheFacetsOwnFieldIsLeftOut() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFilter(new FieldQuery("category", Matchers.equalTo("shoes")))
				.addFacet(Facet.of("category"))
				.addFacet(Facet.of("published"))
				.build()
		);

		// The hits are narrowed, and so is every other facet
		assertThat(result.total().count(), is(2L));
		assertThat(
			result.facets().get("published").values(),
			contains(new SearchResult.Facet.Value(true, 2))
		);

		// The category counts still show what the other categories would hold
		assertThat(
			result.facets().get("category").values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("shoes", 2),
				new SearchResult.Facet.Value("clothes", 2)
			)
		);
	}

	@Test
	public void testEveryFilterOnTheFieldIsLeftOut() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFilter(new FieldQuery("category", Matchers.equalTo("shoes")))
				.addFilter(new FieldQuery("category", Matchers.equalTo("clothes")))
				.addFacet(Facet.of("category"))
				.build()
		);

		// The filters contradict each other, so nothing matches
		assertThat(result.total().count(), is(0L));

		// Both are left out of the counts, which still show every category
		assertThat(
			result.facets().get("category").values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("shoes", 2),
				new SearchResult.Facet.Value("clothes", 2)
			)
		);
	}

	@Test
	public void testClauseInTheQueryOnTheSameFieldStillNarrows() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withQuery(Query.field("category", Matchers.equalTo("shoes")))
				.addFacet(Facet.of("category"))
				.build()
		);

		var facet = result.facets().get("category");
		assertThat(facet.values(), contains(new SearchResult.Facet.Value("shoes", 2)));
		assertThat(facet.totalValues(), is(1));
	}

	@Test
	public void testBooleanCounts() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("published"))
				.build()
		);

		assertThat(
			result.facets().get("published").values(),
			contains(
				new SearchResult.Facet.Value(true, 3),
				new SearchResult.Facet.Value(false, 1)
			)
		);
	}

	@Test
	public void testIntegerCountsComeBackAsNumbers() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("stock").withOrder(Facet.Order.VALUE))
				.build()
		);

		assertThat(
			result.facets().get("stock").values(),
			contains(
				new SearchResult.Facet.Value(3, 2),
				new SearchResult.Facet.Value(5, 1),
				new SearchResult.Facet.Value(7, 1)
			)
		);
	}

	@Test
	public void testDoubleCountsUndoTheSortableEncoding() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("price").withOrder(Facet.Order.VALUE))
				.build()
		);

		assertThat(
			result.facets().get("price").values(),
			contains(
				new SearchResult.Facet.Value(80.0, 2),
				new SearchResult.Facet.Value(120.0, 1),
				new SearchResult.Facet.Value(200.0, 1)
			)
		);
	}

	@Test
	public void testTimestampCountsTheInstantInUtc() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("added").withOrder(Facet.Order.VALUE))
				.build()
		);

		// The fourth document was given with an offset, and counts as its instant
		assertThat(
			result.facets().get("added").values(),
			contains(
				new SearchResult.Facet.Value("2024-05-01T12:00:00Z", 1),
				new SearchResult.Facet.Value("2024-06-01T12:00:00Z", 2),
				new SearchResult.Facet.Value("2024-07-01T10:00:00Z", 1)
			)
		);
	}

	@Test
	public void testZeroLimitStillCounts() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.withLimit(0)
				.addFacet(Facet.of("category"))
				.build()
		);

		assertThat(result.hits().castToList(), is(empty()));
		assertThat(result.total(), is(new SearchResult.Total(4, true)));
		assertThat(result.facets().get("category").totalValues(), is(2));
	}

	@Test
	public void testFieldThatNeverHeldAValueCountsNothing() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("brand"))
				.build()
		);

		var facet = result.facets().get("brand");
		assertThat(facet.values().castToList(), is(empty()));
		assertThat(facet.totalValues(), is(0));
	}

	@Test
	public void testTwoFacetsOverTheSameFieldAreToldApartByName() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("category"))
				.addFacet(
					Facet.of("category").withName("alphabetical").withOrder(Facet.Order.VALUE)
				)
				.build()
		);

		assertThat(result.facets().get("category").totalValues(), is(2));
		assertThat(
			result.facets().get("alphabetical").values().getFirst().value(),
			is("clothes")
		);
	}

	@Test
	public void testFacetOnAFieldNotDefinedForItIsRefused() throws IOException {
		var index = products();

		assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create()
					.addFacet(Facet.of("name"))
					.build()
			)
		);
	}

	@Test
	public void testFacetOnAnUnknownFieldIsRefused() throws IOException {
		var index = products();

		assertThrows(
			IndexFieldNotFoundException.class,
			() -> index.search(
				SearchRequest.create()
					.addFacet(Facet.of("missing"))
					.build()
			)
		);
	}

	@Test
	public void testTwoFacetsWithTheSameNameAreRefused() {
		assertThrows(
			IllegalArgumentException.class,
			() -> SearchRequest.create()
				.addFacet(Facet.of("category"))
				.addFacet(Facet.of("category"))
				.build()
		);
	}

	@Test
	public void testFacetLimitsOutsideTheBoundsAreRefused() {
		assertThrows(
			IllegalArgumentException.class,
			() -> Facet.of("category").withLimit(0)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> Facet.of("category").withLimit(Facet.MAX_LIMIT + 1)
		);
	}

	@Test
	public void testLocalizedFieldCountsTheLocaleOfTheSearch() throws IOException {
		var index = localized();

		var swedish = index.search(
			SearchRequest.create()
				.withLocale("sv")
				.addFacet(Facet.of("label"))
				.build()
		);

		assertThat(
			swedish.facets().get("label").values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("röd", 1),
				new SearchResult.Facet.Value("blå", 1)
			)
		);

		// A locale the field never held falls back to its default
		var german = index.search(
			SearchRequest.create()
				.withLocale("de")
				.addFacet(Facet.of("label"))
				.build()
		);

		assertThat(
			german.facets().get("label").values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("red", 1),
				new SearchResult.Facet.Value("blue", 1)
			)
		);
	}

	@Test
	public void testRangeBucketsCountInTheOrderGiven() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(
					Facet.of("price").withRanges(
						new Facet.Range(null, 100.0),
						new Facet.Range(100.0, 200.0),
						new Facet.Range(200.0, null)
					)
				)
				.build()
		);

		assertThat(
			result.facets().get("price").buckets(),
			contains(
				new SearchResult.Facet.Bucket(null, 100.0, 2),
				new SearchResult.Facet.Bucket(100.0, 200.0, 1),
				new SearchResult.Facet.Bucket(200.0, null, 1)
			)
		);
	}

	@Test
	public void testAdjacentBucketsCountNoValueTwice() throws IOException {
		var index = products();

		// Two documents hold stock 3 and one holds 5, both shared bounds
		var result = index.search(
			SearchRequest.create()
				.addFacet(
					Facet.of("stock").withRanges(
						new Facet.Range(3, 5),
						new Facet.Range(5, 7),
						new Facet.Range(7, 9)
					)
				)
				.build()
		);

		assertThat(
			result.facets().get("stock").buckets(),
			contains(
				new SearchResult.Facet.Bucket(3, 5, 2),
				new SearchResult.Facet.Bucket(5, 7, 1),
				new SearchResult.Facet.Bucket(7, 9, 1)
			)
		);
	}

	@Test
	public void testNegativeDoublesFallInTheRightBucket() throws IOException {
		var index = create(
			"balances",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields("balance", faceted(doubleField()).build())
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("balance", -50.0)
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("balance", -5.0)
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("balance", 5.0)
			)
		);
		index.commit();

		var result = index.search(
			SearchRequest.create()
				.addFacet(
					Facet.of("balance").withRanges(
						new Facet.Range(-100.0, 0.0),
						new Facet.Range(0.0, 100.0)
					)
				)
				.build()
		);

		assertThat(
			result.facets().get("balance").buckets(),
			contains(
				new SearchResult.Facet.Bucket(-100.0, 0.0, 2),
				new SearchResult.Facet.Bucket(0.0, 100.0, 1)
			)
		);
	}

	@Test
	public void testTimestampBucketsCompareInstants() throws IOException {
		var index = products();

		// The second bound names the same instant as 2024-07-01T10:00:00Z
		var result = index.search(
			SearchRequest.create()
				.addFacet(
					Facet.of("added").withRanges(
						new Facet.Range("2024-06-01T00:00:00Z", "2024-07-01T00:00:00Z"),
						new Facet.Range("2024-07-01T11:00:00+01:00", null)
					)
				)
				.build()
		);

		assertThat(
			result.facets().get("added").buckets(),
			contains(
				new SearchResult.Facet.Bucket(
					"2024-06-01T00:00:00Z", "2024-07-01T00:00:00Z", 2
				),
				new SearchResult.Facet.Bucket("2024-07-01T11:00:00+01:00", null, 1)
			)
		);
	}

	@Test
	public void testRangeBucketsAreSidewaysOfTheirOwnFilter() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFilter(new FieldQuery("price", Matchers.between(0.0, 100.0)))
				.addFacet(
					Facet.of("price").withRanges(
						new Facet.Range(null, 100.0),
						new Facet.Range(100.0, null)
					)
				)
				.addFacet(Facet.of("category"))
				.build()
		);

		// The buckets on the filtered field still count every document
		assertThat(
			result.facets().get("price").buckets(),
			contains(
				new SearchResult.Facet.Bucket(null, 100.0, 2),
				new SearchResult.Facet.Bucket(100.0, null, 2)
			)
		);

		// While the other facet is narrowed to the two cheap products
		assertThat(
			result.facets().get("category").values(),
			containsInAnyOrder(
				new SearchResult.Facet.Value("shoes", 1),
				new SearchResult.Facet.Value("clothes", 1)
			)
		);
	}

	@Test
	public void testValueAndRangeFacetsOverTheSameFieldAreToldApartByName() throws IOException {
		var index = products();

		var result = index.search(
			SearchRequest.create()
				.addFacet(Facet.of("price"))
				.addFacet(
					Facet.of("price")
						.withName("brackets")
						.withRanges(new Facet.Range(null, 100.0), new Facet.Range(100.0, null))
				)
				.build()
		);

		assertThat(result.facets().get("price").totalValues(), is(3));
		assertThat(
			result.facets().get("brackets").buckets(),
			contains(
				new SearchResult.Facet.Bucket(null, 100.0, 2),
				new SearchResult.Facet.Bucket(100.0, null, 2)
			)
		);
	}

	@Test
	public void testRangeBucketsOnAStringFieldAreRefused() throws IOException {
		var index = products();

		assertThrows(
			IndexInvalidQueryTypeException.class,
			() -> index.search(
				SearchRequest.create()
					.addFacet(
						Facet.of("category").withRanges(new Facet.Range("a", "m"))
					)
					.build()
			)
		);
	}

	@Test
	public void testRangeBucketsOnAFieldNotDefinedForFacetingAreRefused() throws IOException {
		var index = products();

		assertThrows(
			IndexFieldUsageException.class,
			() -> index.search(
				SearchRequest.create()
					.addFacet(
						Facet.of("name").withRanges(new Facet.Range(0, 10))
					)
					.build()
			)
		);
	}

	@Test
	public void testBucketThatHoldsNothingIsRefused() throws IOException {
		var index = products();

		// Inverted bounds
		assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.addFacet(
						Facet.of("price").withRanges(new Facet.Range(200.0, 100.0))
					)
					.build()
			)
		);

		// From and to naming the same value, which the bucket excludes
		assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.addFacet(
						Facet.of("price").withRanges(new Facet.Range(100.0, 100.0))
					)
					.build()
			)
		);
	}

	@Test
	public void testBucketWithoutBoundsIsRefused() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new Facet.Range(null, null)
		);
	}

	@Test
	public void testSearchWithoutFacetsAnswersNone() throws IOException {
		var index = products();

		var result = index.search(SearchRequest.all());

		assertThat(result.facets().isEmpty(), is(true));
	}

	/**
	 * A small catalog with a faceted field of every countable type, and one
	 * faceted field no document ever holds a value in.
	 *
	 * @return
	 * @throws IOException
	 */
	private Index products() throws IOException {
		var index = create(
			"products",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"name",
					string(
						StringFieldTypeDef.newBuilder()
							.setMatching(
								StringFieldTypeDef.TextUsageConfig.getDefaultInstance()
							)
					).setStored(true).build()
				)
				.putFields("category", faceted(string()).build())
				.putFields("tags", faceted(string()).setMultiple(true).build())
				.putFields("published", faceted(bool()).build())
				.putFields("price", faceted(doubleField()).build())
				.putFields("stock", faceted(int32()).build())
				.putFields("added", faceted(timestamp()).build())
				.putFields("brand", faceted(string()).build())
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Trail runner"),
				new Document.Value("category", "shoes"),
				new Document.Value("tags", "outdoor"),
				new Document.Value("tags", "sale"),
				new Document.Value("published", true),
				new Document.Value("price", 120.0),
				new Document.Value("stock", 3),
				new Document.Value("added", "2024-05-01T12:00:00Z")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "City sneaker"),
				new Document.Value("category", "shoes"),
				new Document.Value("tags", "sale"),
				new Document.Value("published", true),
				new Document.Value("price", 80.0),
				new Document.Value("stock", 5),
				new Document.Value("added", "2024-06-01T12:00:00Z")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "3"),
				new Document.Value("name", "Wool sweater"),
				new Document.Value("category", "clothes"),
				new Document.Value("published", false),
				new Document.Value("price", 80.0),
				new Document.Value("stock", 3),
				new Document.Value("added", "2024-06-01T12:00:00Z")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "4"),
				new Document.Value("name", "Rain jacket"),
				new Document.Value("category", "clothes"),
				new Document.Value("published", true),
				new Document.Value("price", 200.0),
				new Document.Value("stock", 7),
				new Document.Value("added", "2024-07-01T12:00:00+02:00")
			)
		);

		index.commit();
		return index;
	}

	/**
	 * An index whose faceted field holds a variant per locale, for checking
	 * that counting reads the variant the search does.
	 *
	 * @return
	 * @throws IOException
	 */
	private Index localized() throws IOException {
		var index = create(
			"localized",
			IndexDef.newBuilder()
				.putFields("id", string().setPrimaryKey(true).build())
				.putFields(
					"label",
					faceted(string())
						.setLocales(
							FieldDef.LocaleConfig.newBuilder()
								.setDefaultLocale("en")
								.addLocales("sv")
						)
						.build()
				)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("label", "red", "en"),
				new Document.Value("label", "röd", "sv")
			)
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("label", "blue", "en"),
				new Document.Value("label", "blå", "sv")
			)
		);

		index.commit();
		return index;
	}

	private static FieldDef.Builder faceted(FieldDef.Builder builder) {
		return builder
			.setFilter(FilterConfig.getDefaultInstance())
			.setFacet(FacetConfig.getDefaultInstance());
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

	private static FieldDef.Builder int32() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setInt32(Int32FieldTypeDef.getDefaultInstance())
			);
	}

	private static FieldDef.Builder doubleField() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder().setDouble(DoubleFieldTypeDef.getDefaultInstance())
			);
	}

	private static FieldDef.Builder timestamp() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setTimestamp(TimestampFieldTypeDef.getDefaultInstance())
			);
	}
}
