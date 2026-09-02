package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.LatLonDocValuesField;
import org.apache.lucene.document.LatLonPoint;
import org.apache.lucene.geo.GeoEncodingUtils;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.Pruning;
import org.apache.lucene.search.Sort;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.util.SloppyMath;
import org.junit.jupiter.api.Test;

/**
 * Tests for the ordering that reads distances from doc values and skips
 * through the points of the field it is filtered on.
 */
public class DistanceSortFieldTest {
	/**
	 * How many places lie on the meridian. Large enough that a narrow box
	 * around the origin is a small enough part of the point tree for the
	 * ordering to take it.
	 */
	private static final int COUNT = 20000;

	/** How many matches a search counts before it may leave documents out. */
	private static final int HITS_THRESHOLD = 1000;

	/**
	 * How many places are scattered around the origin. Points are held in
	 * blocks in the tree, so a page over a few thousand of them can never be a
	 * small enough part of it for the ordering to narrow anything.
	 */
	private static final int SCATTERED = 50000;

	private static final double[] SCATTERED_LATITUDES = new double[SCATTERED];
	private static final double[] SCATTERED_LONGITUDES = new double[SCATTERED];

	static {
		var random = new Random(20260902);
		for(var i = 0; i < SCATTERED; i++) {
			SCATTERED_LATITUDES[i] = 59.325 + (random.nextDouble() - 0.5);
			SCATTERED_LONGITUDES[i] = 18.070 + (random.nextDouble() - 0.5);
		}
	}

	@Test
	public void testDistancesComeFromTheDocValuesField() {
		var field = new DistanceSortField(
			"location:_:sort",
			"location:_:filter",
			59.325,
			18.070
		);

		assertThat(field.getField(), is("location:_:sort"));
		assertThat(field.getPointsField(), is("location:_:filter"));
		assertThat(field.getLatitude(), is(59.325));
		assertThat(field.getLongitude(), is(18.070));
		assertThat(field.getMissingValue(), is(Double.POSITIVE_INFINITY));
	}

	@Test
	public void testOriginOffTheEarthIsRefused() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new DistanceSortField("a:_:sort", "a:_:filter", 91, 0)
		);
	}

	/**
	 * A document without a point is infinitely far away, and there is no other
	 * end for it to sort at.
	 */
	@Test
	public void testOnlyInfinityIsAcceptedAsTheMissingValue() {
		var field = distanceSort();

		field.setMissingValue(Double.POSITIVE_INFINITY);

		assertThrows(IllegalArgumentException.class, () -> field.setMissingValue(0d));
	}

	@Test
	public void testOrderingsOfTheSameFieldsAndOriginAreEqual() {
		assertThat(distanceSort(), is(distanceSort()));
		assertThat(distanceSort().hashCode(), is(distanceSort().hashCode()));
	}

	@Test
	public void testOrderingsDifferingInFieldOrOriginAreNotEqual() {
		assertThat(
			distanceSort(),
			is(not(new DistanceSortField("location:_:sort", "other:_:filter", 59.325, 18.070)))
		);

		assertThat(
			distanceSort(),
			is(not(new DistanceSortField("location:_:sort", "location:_:filter", 57.707, 18.070)))
		);

		assertThat(
			distanceSort(),
			is(not(new DistanceSortField("location:_:sort", "location:_:filter", 59.325, 11.967)))
		);
	}

	@Test
	public void testToStringNamesBothFieldsAndTheOrigin() {
		var text = distanceSort().toString();

		assertThat(text, containsString("location:_:sort"));
		assertThat(text, containsString("location:_:filter"));
		assertThat(text, containsString("59.325"));
		assertThat(text, containsString("18.07"));
	}

	/**
	 * Walking a page of distances backwards is not supported, the same as for
	 * Lucene's own distance ordering.
	 */
	@Test
	public void testOrderingCanNotBeMirrored() {
		assertThrows(
			UnsupportedOperationException.class,
			() -> SortKeys.reverse(new Sort(distanceSort()))
		);
	}

	/**
	 * The point of the two names is that Lucene finds the points under one of
	 * them while reading distances from the other. A comparator that found no
	 * points reads every match, and answers the same order while doing it, so
	 * nothing about a result would show the difference.
	 */
	@Test
	public void testTheComparatorSkipsThroughThePointsAndReadsTheDocValues()
		throws IOException
	{
		try(var directory = places()) {
			try(var reader = DirectoryReader.open(directory)) {
				var segment = reader.leaves().get(0);

				var comparator = distanceSort()
					.getComparator(1, Pruning.GREATER_THAN_OR_EQUAL_TO);
				var leaf = comparator.getLeafComparator(segment);

				// An iterator at all means the points of the filter field were found
				assertThat(leaf.competitiveIterator(), is(notNullValue()));

				// The distance read is the one the doc values hold
				leaf.copy(0, 100);
				assertThat(
					(Object) comparator.value(0),
					is((Object) distanceOf(100))
				);
			}
		}
	}

	@Test
	public void testTheCompetitiveIteratorLeavesOutWhatIsTooFarAway() throws IOException {
		try(var directory = places()) {
			try(var reader = DirectoryReader.open(directory)) {
				var segment = reader.leaves().get(0);

				assertThat(costOnceFull(segment), lessThan((long) COUNT));
			}
		}
	}

	/**
	 * Every narrower set is walked out of the point tree, so a search that
	 * built one for every improvement of its queue would pay more than it
	 * saves. A page over a few thousand scattered places builds a handful.
	 */
	@Test
	public void testTheIteratorIsBuiltAFewTimesForAPage() throws IOException {
		try(var directory = scattered()) {
			try(var reader = DirectoryReader.open(directory)) {
				var segment = reader.leaves().get(0);

				var comparator = distanceSort()
					.getComparator(10, Pruning.GREATER_THAN_OR_EQUAL_TO);

				assertThat(collect(comparator, segment, 10), is(nearestScattered(10)));

				var builds = DistanceSortField.buildsOf(comparator);
				assertThat(builds, greaterThan(0));
				assertThat(builds, lessThanOrEqualTo(5));
			}
		}
	}

	/**
	 * A collector that cannot leave documents out asks for an ordering that
	 * does not skip.
	 */
	@Test
	public void testNoPruningLeavesTheComparatorWithoutAnIterator() throws IOException {
		try(var directory = places()) {
			try(var reader = DirectoryReader.open(directory)) {
				var segment = reader.leaves().get(0);

				var comparator = distanceSort().getComparator(1, Pruning.NONE);
				var leaf = comparator.getLeafComparator(segment);

				assertThat(leaf.competitiveIterator(), is(nullValue()));

				leaf.copy(0, 100);
				leaf.setBottom(0);
				leaf.setHitsThresholdReached();

				assertThat(leaf.competitiveIterator(), is(nullValue()));
			}
		}
	}

	/**
	 * Read a page out of a segment the way a collector does: walk the
	 * documents the ordering leaves, keep the nearest ones in slots, and tell
	 * the ordering which slot holds the worst of them.
	 *
	 * @param comparator
	 *   the ordering under test, which keeps the count of narrower sets it
	 *   built
	 * @param segment
	 * @param hits
	 *   how many documents the page holds
	 * @return
	 *   the distance of each document on the page, in meters, nearest first
	 */
	private static List<Double> collect(
		FieldComparator<?> comparator,
		LeafReaderContext segment,
		int hits
	) throws IOException {
		var leaf = comparator.getLeafComparator(segment);
		var iterator = leaf.competitiveIterator();

		var collected = 0;
		var counted = 0;
		var bottom = 0;
		var counting = true;

		for(var doc = iterator.nextDoc();
			doc != DocIdSetIterator.NO_MORE_DOCS;
			doc = iterator.nextDoc())
		{
			counted++;
			if(counting && counted > HITS_THRESHOLD) {
				leaf.setHitsThresholdReached();
				counting = false;
			}

			if(collected < hits) {
				leaf.copy(collected, doc);
				collected++;

				if(collected == hits) {
					bottom = worstSlot(comparator, hits);
					leaf.setBottom(bottom);
				}
			} else if(leaf.compareBottom(doc) > 0) {
				leaf.copy(bottom, doc);
				bottom = worstSlot(comparator, hits);
				leaf.setBottom(bottom);
			}
		}

		var page = new ArrayList<Double>();
		for(var slot = 0; slot < hits; slot++) {
			page.add((Double) comparator.value(slot));
		}

		page.sort(Comparator.naturalOrder());
		return page;
	}

	/**
	 * Get the slot holding the document that is furthest away.
	 */
	private static int worstSlot(FieldComparator<?> comparator, int hits) {
		var worst = 0;
		for(var slot = 1; slot < hits; slot++) {
			if(comparator.compare(slot, worst) > 0) {
				worst = slot;
			}
		}

		return worst;
	}

	/**
	 * Get what the competitive iterator costs once the queue is full and the
	 * collector has counted past its threshold.
	 */
	private static long costOnceFull(LeafReaderContext segment) throws IOException {
		var comparator = distanceSort().getComparator(1, Pruning.GREATER_THAN_OR_EQUAL_TO);
		var leaf = comparator.getLeafComparator(segment);

		// The hundredth place, which leaves the ones beyond it out of the page
		leaf.copy(0, 100);

		leaf.setBottom(0);
		leaf.setHitsThresholdReached();

		return leaf.competitiveIterator().cost();
	}

	/**
	 * An index of places on one meridian, a thousandth of a degree apart, with
	 * the same coordinates written as points and as doc values.
	 */
	private static ByteBuffersDirectory places() throws IOException {
		var directory = new ByteBuffersDirectory();

		try(var writer = new IndexWriter(directory, new IndexWriterConfig())) {
			for(var i = 0; i < COUNT; i++) {
				var document = new Document();
				document.add(new LatLonPoint("location:_:filter", latitudeOf(i), 18.070));
				document.add(
					new LatLonDocValuesField("location:_:sort", latitudeOf(i), 18.070)
				);
				writer.addDocument(document);
			}

			writer.forceMerge(1);
		}

		return directory;
	}

	/**
	 * An index of places scattered over about a degree around the origin,
	 * seeded so that the page and the count of builds are the same on every
	 * run.
	 */
	private static ByteBuffersDirectory scattered() throws IOException {
		var directory = new ByteBuffersDirectory();

		try(var writer = new IndexWriter(directory, new IndexWriterConfig())) {
			for(var i = 0; i < SCATTERED; i++) {
				var document = new Document();
				document.add(
					new LatLonPoint(
						"location:_:filter",
						SCATTERED_LATITUDES[i],
						SCATTERED_LONGITUDES[i]
					)
				);
				document.add(
					new LatLonDocValuesField(
						"location:_:sort",
						SCATTERED_LATITUDES[i],
						SCATTERED_LONGITUDES[i]
					)
				);
				writer.addDocument(document);
			}

			writer.forceMerge(1);
		}

		return directory;
	}

	/**
	 * Get the distances of the scattered places that are nearest the origin,
	 * in meters, nearest first.
	 */
	private static List<Double> nearestScattered(int count) {
		var distances = new ArrayList<Double>();
		for(var i = 0; i < SCATTERED; i++) {
			distances.add(
				SloppyMath.haversinMeters(
					59.325,
					18.070,
					encoded(SCATTERED_LATITUDES[i], true),
					encoded(SCATTERED_LONGITUDES[i], false)
				)
			);
		}

		distances.sort(Comparator.naturalOrder());
		return List.copyOf(distances.subList(0, count));
	}

	private static double latitudeOf(int number) {
		return 59.325 + number * 0.001;
	}

	/**
	 * Get a coordinate as the ordering reads it back, which is the value the
	 * index encoded it to.
	 */
	private static double encoded(double coordinate, boolean latitude) {
		return latitude
			? GeoEncodingUtils.decodeLatitude(GeoEncodingUtils.encodeLatitude(coordinate))
			: GeoEncodingUtils.decodeLongitude(GeoEncodingUtils.encodeLongitude(coordinate));
	}

	/**
	 * Get how far a place is from the origin, in meters, measured the way the
	 * ordering measures it: from the coordinates as they were encoded.
	 */
	private static double distanceOf(int number) {
		return SloppyMath.haversinMeters(
			59.325,
			18.070,
			GeoEncodingUtils.decodeLatitude(
				GeoEncodingUtils.encodeLatitude(latitudeOf(number))
			),
			GeoEncodingUtils.decodeLongitude(GeoEncodingUtils.encodeLongitude(18.070))
		);
	}

	private static DistanceSortField distanceSort() {
		return new DistanceSortField(
			"location:_:sort",
			"location:_:filter",
			59.325,
			18.070
		);
	}
}
