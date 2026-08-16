package se.l4.exofind.engine.index.types.geo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.AbstractIndexTest;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.GeoPoint;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexException;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.GeoPointFieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for indexing and searching geo points - narrowing to a distance from
 * an origin, ordering by that distance, and what a stored point comes back as.
 */
public class GeoPointIndexingTest extends AbstractIndexTest {
	/**
	 * Three places in Stockholm and one in Gothenburg, so what is near the
	 * center of Stockholm is obvious from the test itself.
	 */
	private static final GeoPoint OLD_TOWN = new GeoPoint(59.325, 18.070);
	private static final GeoPoint SODERMALM = new GeoPoint(59.315, 18.070);
	private static final GeoPoint AIRPORT = new GeoPoint(59.650, 17.930);
	private static final GeoPoint GOTHENBURG = new GeoPoint(57.707, 11.967);

	@Test
	public void testDistanceFindsThePlacesWithin() throws IOException {
		var index = places();

		// 5 km around the old town reaches Södermalm but not the airport
		var result = search(
			index,
			Query.field("location", Matchers.withinDistance(59.325, 18.070, 5_000))
		);

		assertThat(ids(result), containsInAnyOrder("old_town", "sodermalm"));
	}

	@Test
	public void testWiderDistanceReachesFurther() throws IOException {
		var index = places();

		var result = search(
			index,
			Query.field("location", Matchers.withinDistance(59.325, 18.070, 50_000))
		);

		assertThat(ids(result), containsInAnyOrder("old_town", "sodermalm", "airport"));
	}

	@Test
	public void testDistanceSortOrdersNearestFirst() throws IOException {
		var index = places();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.distance("location", 59.325, 18.070))
				.build()
		);

		assertThat(ids(result), contains("old_town", "sodermalm", "airport", "gothenburg"));
	}

	/**
	 * The origin is part of the order, so measuring from another city turns
	 * the order around.
	 */
	@Test
	public void testDistanceSortFollowsTheOrigin() throws IOException {
		var index = places();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.distance("location", 57.707, 11.967))
				.build()
		);

		assertThat(ids(result).get(0), is("gothenburg"));
	}

	@Test
	public void testAnyFindsTheDocumentsWithAValue() throws IOException {
		var index = places();

		index.addDocument(new Document(new Document.Value("id", "nowhere")));
		index.commit();

		var result = search(index, Query.field("location", Matchers.any()));

		assertThat(
			ids(result),
			containsInAnyOrder("old_town", "sodermalm", "airport", "gothenburg")
		);
	}

	/**
	 * A plain field sort carries no origin to measure from, so it is refused
	 * with directions rather than answered with an order that means nothing.
	 */
	@Test
	public void testPlainFieldSortIsRefused() throws IOException {
		var index = places();

		var e = assertThrows(
			IndexException.class,
			() -> index.search(
				SearchRequest.create()
					.withSort(SortBy.field("location"))
					.build()
			)
		);

		assertThat(e.getCode(), is("index:query:geo_point:sort_needs_origin"));
	}

	@Test
	public void testPointComesBackFromTheSource() throws IOException {
		var index = places();

		assertThat(index.getDocument("old_town").get("location"), is(OLD_TOWN));
	}

	@Test
	public void testStoredPointComesBackWithoutASource() throws IOException {
		var index = create(
			definition()
				.setSource(IndexDef.SourceMode.SOURCE_MODE_NONE)
				.putFields("location", geoPoint().setStored(true).build())
		);

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("location", OLD_TOWN)
			)
		);
		index.commit();

		assertThat(index.getDocument("1").get("location"), is(OLD_TOWN));
	}

	@Test
	public void testPointOffTheEarthIsRefused() throws IOException {
		var index = places();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "broken"),
					new Document.Value("location", new GeoPoint(91, 0))
				)
			)
		);

		assertThat(
			e.getErrors().collect(error -> error.getCode()).toList(),
			hasItem("index:update:geo_point:out_of_range")
		);
	}

	@Test
	public void testValueThatIsNotAPointIsRefused() throws IOException {
		var index = places();

		var e = assertThrows(
			ValidationException.class,
			() -> index.addDocument(
				new Document(
					new Document.Value("id", "broken"),
					new Document.Value("location", "59.325,18.070")
				)
			)
		);

		assertThat(
			e.getErrors().collect(error -> error.getCode()).toList(),
			hasItem("index:update:geo_point:invalid_value")
		);
	}

	@Test
	public void testEqualityIsRefused() throws IOException {
		var index = places();

		assertThrows(
			se.l4.exofind.engine.index.IndexInvalidQueryTypeException.class,
			() -> search(index, Query.field("location", Matchers.equalTo(OLD_TOWN)))
		);
	}

	private Index places() throws IOException {
		var index = create(
			definition()
				.putFields(
					"location",
					geoPoint()
						.setFilter(FilterConfig.getDefaultInstance())
						.setSort(SortConfig.getDefaultInstance())
						.build()
				)
		);

		index.addDocument(place("old_town", OLD_TOWN));
		index.addDocument(place("sodermalm", SODERMALM));
		index.addDocument(place("airport", AIRPORT));
		index.addDocument(place("gothenburg", GOTHENBURG));

		index.commit();
		return index;
	}

	private static Document place(String id, GeoPoint location) {
		return new Document(
			new Document.Value("id", id),
			new Document.Value("location", location)
		);
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				FieldDef.newBuilder()
					.setPrimaryKey(true)
					.setType(
						FieldTypeDef.newBuilder()
							.setString(StringFieldTypeDef.getDefaultInstance())
					)
					.build()
			);
	}

	private static FieldDef.Builder geoPoint() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setGeoPoint(GeoPointFieldTypeDef.getDefaultInstance())
			);
	}

	private static SearchResult search(Index index, Query... query) throws IOException {
		return index.search(SearchRequest.create().withQuery(query).build());
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
