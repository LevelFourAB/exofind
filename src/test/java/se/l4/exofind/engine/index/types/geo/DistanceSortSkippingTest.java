package se.l4.exofind.engine.index.types.geo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.apache.lucene.util.SloppyMath;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.AbstractIndexTest;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.GeoPoint;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.FilterConfig;
import se.l4.exofind.engine.index.schema.GeoPointFieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.ObjectFieldTypeDef;
import se.l4.exofind.engine.index.schema.SortConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortBy;
import se.l4.exofind.engine.query.matchers.Matchers;

/**
 * Tests for ordering by distance over more documents than a search reads.
 *
 * A geo point field with both {@code filter} and {@code sort} answers the
 * order without reading every match, so these searches take a different path
 * through Lucene than the same order over a field that only has {@code sort}.
 * That path starts leaving documents out once a search has counted past the
 * total hits threshold, so the index here holds more documents than that
 * threshold.
 *
 * The index also holds an object field, so most Lucene documents in a segment
 * hold no point at all, and five documents that hold no location.
 */
public class DistanceSortSkippingTest extends AbstractIndexTest {
	private static final int COUNT = 2000;
	private static final int GAPS = 5;
	private static final int PAGE = 20;

	private static final double ORIGIN_LATITUDE = 59.325;
	private static final double ORIGIN_LONGITUDE = 18.070;

	/** The locations of the documents, indexed by their number. */
	private static final List<GeoPoint> LOCATIONS = locations();

	/** Document identifiers, nearest to the origin first. */
	private static final List<Object> NEAREST = nearest();

	@Test
	public void testNearestDocumentsComeFirst() throws IOException {
		var index = places();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.distance("location", ORIGIN_LATITUDE, ORIGIN_LONGITUDE))
				.withLimit(PAGE)
				.build()
		);

		assertThat(ids(result), is(NEAREST.subList(0, PAGE)));
	}

	@Test
	public void testDeepPageKeepsTheOrder() throws IOException {
		var index = places();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.distance("location", ORIGIN_LATITUDE, ORIGIN_LONGITUDE))
				.withOffset(1200)
				.withLimit(PAGE)
				.build()
		);

		assertThat(ids(result), is(NEAREST.subList(1200, 1200 + PAGE)));
	}

	/**
	 * A document without a location is infinitely far away, so it sorts after
	 * every document that has one.
	 */
	@Test
	public void testDocumentsWithoutALocationComeLast() throws IOException {
		var index = places();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.distance("location", ORIGIN_LATITUDE, ORIGIN_LONGITUDE))
				.withOffset(COUNT)
				.withLimit(PAGE)
				.build()
		);

		assertThat(ids(result), hasSize(GAPS));
		assertThat(ids(result), containsInAnyOrder(gaps()));
	}

	/**
	 * A page small enough to fill with the documents holding no location has
	 * nothing to measure against until a document with one arrives.
	 */
	@Test
	public void testPageSmallerThanTheDocumentsWithoutALocation() throws IOException {
		var index = places();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.distance("location", ORIGIN_LATITUDE, ORIGIN_LONGITUDE))
				.withLimit(3)
				.build()
		);

		assertThat(ids(result), is(NEAREST.subList(0, 3)));
	}

	@Test
	public void testPagesAfterAHitContinueTheOrder() throws IOException {
		var index = places();

		var first = page(index, null);
		assertThat(ids(first), is(NEAREST.subList(0, PAGE)));

		var second = page(index, first);
		assertThat(ids(second), is(NEAREST.subList(PAGE, 2 * PAGE)));

		var third = page(index, second);
		assertThat(ids(third), is(NEAREST.subList(2 * PAGE, 3 * PAGE)));
	}

	/**
	 * A search that matches fewer documents than a page holds never fills the
	 * queue, so nothing is ever left out.
	 */
	@Test
	public void testNarrowFilterOrdersEveryMatch() throws IOException {
		var index = places();

		var radius = radiusAround(3);

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.field(
						"location",
						Matchers.withinDistance(ORIGIN_LATITUDE, ORIGIN_LONGITUDE, radius)
					)
				)
				.withSort(SortBy.distance("location", ORIGIN_LATITUDE, ORIGIN_LONGITUDE))
				.withLimit(PAGE)
				.build()
		);

		assertThat(ids(result), is(NEAREST.subList(0, 3)));
	}

	@Test
	public void testWideFilterOrdersTheNearestMatches() throws IOException {
		var index = places();

		var radius = radiusAround(400);

		var result = index.search(
			SearchRequest.create()
				.withQuery(
					Query.field(
						"location",
						Matchers.withinDistance(ORIGIN_LATITUDE, ORIGIN_LONGITUDE, radius)
					)
				)
				.withSort(SortBy.distance("location", ORIGIN_LATITUDE, ORIGIN_LONGITUDE))
				.withLimit(PAGE)
				.build()
		);

		assertThat(ids(result), is(NEAREST.subList(0, PAGE)));
	}

	/**
	 * A field without {@code filter} has no points to skip through, and orders
	 * the same documents the same way.
	 */
	@Test
	public void testFieldWithoutFilterOrdersTheSame() throws IOException {
		var index = places();

		var result = index.search(
			SearchRequest.create()
				.withSort(SortBy.distance("plain", ORIGIN_LATITUDE, ORIGIN_LONGITUDE))
				.withLimit(PAGE)
				.build()
		);

		assertThat(ids(result), is(NEAREST.subList(0, PAGE)));

		var deep = index.search(
			SearchRequest.create()
				.withSort(SortBy.distance("plain", ORIGIN_LATITUDE, ORIGIN_LONGITUDE))
				.withOffset(1200)
				.withLimit(PAGE)
				.build()
		);

		assertThat(ids(deep), is(NEAREST.subList(1200, 1200 + PAGE)));
	}

	private static SearchResult page(Index index, SearchResult previous) throws IOException {
		var request = SearchRequest.create()
			.withSort(SortBy.distance("location", ORIGIN_LATITUDE, ORIGIN_LONGITUDE))
			.withLimit(PAGE);

		if(previous != null) {
			request = request.withAfter(previous.hits().getLast().key());
		}

		return index.search(request.build());
	}

	/**
	 * An index holding a location in a field that is both filtered and sorted,
	 * the same location in a field that is only sorted, and a few values of an
	 * object field so that most Lucene documents in a segment are not
	 * documents of the index.
	 */
	private Index places() throws IOException {
		var index = create(
			"places",
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setPrimaryKey(true)
						.setType(
							FieldTypeDef.newBuilder()
								.setString(StringFieldTypeDef.getDefaultInstance())
						)
						.build()
				)
				.putFields(
					"location",
					geoPoint()
						.setFilter(FilterConfig.getDefaultInstance())
						.setSort(SortConfig.getDefaultInstance())
						.build()
				)
				.putFields(
					"plain",
					geoPoint().setSort(SortConfig.getDefaultInstance()).build()
				)
				.putFields(
					"variants",
					FieldDef.newBuilder()
						.setMultiple(true)
						.setType(
							FieldTypeDef.newBuilder().setObject(
								ObjectFieldTypeDef.newBuilder()
									.setMode(ObjectFieldTypeDef.Mode.MODE_NESTED)
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
							)
						)
						.build()
				)
		);

		/*
		 * Written first, so a small page fills its queue with documents that
		 * are infinitely far away before it sees one that is not.
		 */
		for(var i = 0; i < GAPS; i++) {
			index.addDocument(
				new Document(
					new Document.Value("id", "gap" + i),
					new Document.Value(
						"variants",
						new Document(new Document.Value("price", 1.0))
					)
				)
			);
		}

		for(var i = 0; i < COUNT; i++) {
			var location = LOCATIONS.get(i);

			index.addDocument(
				new Document(
					new Document.Value("id", id(i)),
					new Document.Value("location", location),
					new Document.Value("plain", location),
					new Document.Value(
						"variants",
						new Document(new Document.Value("price", i * 1.5))
					),
					new Document.Value(
						"variants",
						new Document(new Document.Value("price", i * 2.5))
					)
				)
			);
		}

		index.commit();
		return index;
	}

	private static FieldDef.Builder geoPoint() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setGeoPoint(GeoPointFieldTypeDef.getDefaultInstance())
			);
	}

	/**
	 * Get locations spread over about a degree around the origin. Seeded, so
	 * the expected order is the same on every run.
	 */
	private static List<GeoPoint> locations() {
		var random = new Random(20260902);

		var points = new ArrayList<GeoPoint>();
		for(var i = 0; i < COUNT; i++) {
			points.add(
				new GeoPoint(
					ORIGIN_LATITUDE + (random.nextDouble() - 0.5),
					ORIGIN_LONGITUDE + (random.nextDouble() - 0.5)
				)
			);
		}

		return List.copyOf(points);
	}

	/**
	 * Get the identifiers of the documents holding a location, nearest to the
	 * origin first.
	 */
	private static List<Object> nearest() {
		var numbers = new ArrayList<Integer>();
		for(var i = 0; i < COUNT; i++) {
			numbers.add(i);
		}

		numbers.sort(Comparator.comparingDouble(DistanceSortSkippingTest::distanceOf));

		var ids = new ArrayList<Object>();
		for(var number : numbers) {
			ids.add(id(number));
		}

		return List.copyOf(ids);
	}

	/**
	 * Get a radius that holds exactly the given number of documents, placed
	 * halfway between the last one inside it and the first one outside, so
	 * that no document sits on the edge.
	 */
	private static double radiusAround(int count) {
		var distances = new ArrayList<Double>();
		for(var i = 0; i < COUNT; i++) {
			distances.add(distanceOf(i));
		}

		distances.sort(Comparator.naturalOrder());

		return (distances.get(count - 1) + distances.get(count)) / 2;
	}

	private static double distanceOf(int number) {
		var location = LOCATIONS.get(number);

		return SloppyMath.haversinMeters(
			ORIGIN_LATITUDE,
			ORIGIN_LONGITUDE,
			location.latitude(),
			location.longitude()
		);
	}

	private static String id(int number) {
		return String.format("%04d", number);
	}

	private static Object[] gaps() {
		var ids = new Object[GAPS];
		for(var i = 0; i < GAPS; i++) {
			ids[i] = "gap" + i;
		}
		return ids;
	}

	private static List<Object> ids(SearchResult result) {
		return result.hits().collect(SearchResult.Hit::id).toList();
	}
}
