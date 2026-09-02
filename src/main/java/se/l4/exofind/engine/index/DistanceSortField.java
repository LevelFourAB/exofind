package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.Objects;

import org.apache.lucene.document.LatLonPoint;
import org.apache.lucene.geo.GeoEncodingUtils;
import org.apache.lucene.geo.GeoUtils;
import org.apache.lucene.geo.Rectangle;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafFieldComparator;
import org.apache.lucene.search.Pruning;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.SloppyMath;

/**
 * Ordering documents by their distance from an origin, nearest first.
 *
 * <p>A geo point reaches Lucene twice: the {@code sort} usage writes doc
 * values holding the encoded latitude and longitude, and the {@code filter}
 * usage writes a {@code LatLonPoint}. Doc values give a document's distance
 * but have to be read for every document that matched. The points are a tree
 * over the same coordinates, so a search that wants ten hits can rule out a
 * whole subtree without reading a document in it. This ordering carries the
 * two names apart: distances come from the doc values name, and the documents
 * that can still make the page come from the points name.
 *
 * <p>Use this only when the field has both usages. The two Lucene fields must
 * hold the same coordinates for every document, which holds when one value was
 * written to both. {@link #getField()} stays the doc values name.
 *
 * <p>Distances are in meters, measured with
 * {@link SloppyMath#haversinMeters(double, double, double, double)}. A
 * document with several values takes the smallest of them. A document with no
 * value is at an infinite distance and sorts last, so
 * {@link #getMissingValue()} is always {@link Double#POSITIVE_INFINITY}.
 *
 * <h2>Documents that hold no point</h2>
 *
 * <p>Every value of an object field is a Lucene document of its own (see
 * {@link NestedDocuments}), and holds none of the fields of the index. Those
 * documents, and documents that hold no value for the field, are at an infinite
 * distance. Once the queue holds a full page the worst distance in it is
 * finite, so none of them can be competitive and none of them reaches the
 * page. This ordering therefore needs nothing that tells the documents of the
 * index apart from the values of an object field.
 *
 * <h2>What comes from Lucene</h2>
 *
 * <p>The comparison, the bounding box that rejects a document before its
 * distance is computed, and the sampling that limits how often the box is
 * rebuilt are taken from {@code LatLonPointDistanceComparator} in Apache
 * Lucene, which is licensed under the Apache License 2.0, the same license
 * this project carries. The skipping added on top follows the structure of
 * Lucene's {@code NumericComparator}: an iterator whose inner iterator is
 * replaced with a narrower one as the page fills.
 *
 * <p>The narrower iterator is the answer of
 * {@link LatLonPoint#newBoxQuery(String, double, double, double, double)} over
 * the points field, for the bounding box of the circle that holds everything
 * nearer than the worst hit in the queue. The box holds more than the circle
 * does, and the extra documents are measured and turned down like any other.
 * The radius is inclusive and carries a small margin, so a document that ties
 * with the worst hit stays competitive for the sort fields after this one.
 */
public final class DistanceSortField extends SortField {
	private final String points;
	private final double latitude;
	private final double longitude;

	/**
	 * @param values
	 *   name of the Lucene field holding the doc values distances are computed
	 *   from
	 * @param points
	 *   name of the Lucene field holding the same coordinates as a
	 *   {@code LatLonPoint}
	 * @param latitude
	 *   latitude of the origin, -90 to 90
	 * @param longitude
	 *   longitude of the origin, -180 to 180
	 * @throws IllegalArgumentException
	 *   if the origin is not a point on the earth
	 */
	public DistanceSortField(
		String values,
		String points,
		double latitude,
		double longitude
	) {
		super(values, SortField.Type.CUSTOM, false, Double.POSITIVE_INFINITY);

		GeoUtils.checkLatitude(latitude);
		GeoUtils.checkLongitude(longitude);

		this.points = Objects.requireNonNull(points);
		this.latitude = latitude;
		this.longitude = longitude;
	}

	/**
	 * Get the name of the Lucene field holding the points.
	 *
	 * @return
	 */
	public String getPointsField() {
		return points;
	}

	/**
	 * Get the latitude of the origin distances are measured from.
	 *
	 * @return
	 */
	public double getLatitude() {
		return latitude;
	}

	/**
	 * Get the longitude of the origin distances are measured from.
	 *
	 * @return
	 */
	public double getLongitude() {
		return longitude;
	}

	@Override
	public Double getMissingValue() {
		return (Double) super.getMissingValue();
	}

	/**
	 * @throws IllegalArgumentException
	 *   for anything but {@link Double#POSITIVE_INFINITY}, the only end a
	 *   document without a point sorts at
	 */
	@Override
	public void setMissingValue(Object missingValue) {
		if(!Double.valueOf(Double.POSITIVE_INFINITY).equals(missingValue)) {
			throw new IllegalArgumentException(
				"A document without a point sorts last, so the missing value can only be "
					+ "Double.POSITIVE_INFINITY, got " + missingValue
			);
		}

		this.missingValue = missingValue;
	}

	@Override
	public FieldComparator<?> getComparator(int numHits, Pruning pruning) {
		var comparator = new DistanceComparator(
			getField(),
			points,
			latitude,
			longitude,
			numHits,
			pruning
		);

		if(!getOptimizeSortWithIndexedData()) {
			comparator.disableSkipping();
		}

		return comparator;
	}

	/**
	 * Get how many times a comparator of this ordering has built a narrower
	 * set of documents to read, over every segment it has been given.
	 *
	 * @param comparator
	 *   a comparator {@link #getComparator(int, Pruning)} answered with
	 * @return
	 * @throws ClassCastException
	 *   if the comparator came from something else
	 */
	static int buildsOf(FieldComparator<?> comparator) {
		return ((DistanceComparator) comparator).builds;
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) {
			return true;
		}

		if(!super.equals(obj) || getClass() != obj.getClass()) {
			return false;
		}

		var other = (DistanceSortField) obj;
		return points.equals(other.points)
			&& Double.doubleToLongBits(latitude) == Double.doubleToLongBits(other.latitude)
			&& Double.doubleToLongBits(longitude) == Double.doubleToLongBits(other.longitude);
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), points, latitude, longitude);
	}

	@Override
	public String toString() {
		return "<distance:\"" + getField() + "\" points=" + points
			+ " latitude=" + latitude + " longitude=" + longitude + ">";
	}

	/**
	 * Compares documents by their distance from the origin and narrows what a
	 * search reads once the queue holds a full page.
	 *
	 * One instance serves every segment of a search, as the queue it fills is
	 * one queue. State that belongs to a segment is replaced in
	 * {@link #getLeafComparator(LeafReaderContext)}.
	 */
	private static final class DistanceComparator
		extends FieldComparator<Double>
		implements LeafFieldComparator
	{
		/**
		 * How often the bounding box is rebuilt once {@code setBottom} has been
		 * called this many times. Above the limit only one call in 64 rebuilds
		 * it, which bounds the cost of a queue that keeps improving.
		 */
		private static final int BOX_SAMPLE_AFTER = 1024;

		/** The narrowest sampling of rebuild attempts, as a power of two. */
		private static final int MIN_SKIP_INTERVAL = 32;

		/** The widest sampling of rebuild attempts, as a power of two. */
		private static final int MAX_SKIP_INTERVAL = 8192;

		/**
		 * How many rebuild attempts run unsampled before the interval applies.
		 */
		private static final int SKIP_ATTEMPTS_BEFORE_SAMPLING = 256;

		private final String values;
		private final String points;
		private final double latitude;
		private final double longitude;

		/** The distance of each hit in the queue, as a haversine sort key. */
		private final double[] slots;

		private Pruning pruning;

		/** The worst distance in the queue, as a haversine sort key. */
		private double bottom;

		/** The distance a search continues from, in meters. */
		private double topValue;

		private boolean queueFull;
		private boolean hitsThresholdReached;

		private SortedNumericDocValues currentDocs;
		private long[] currentValues = new long[4];
		private int valuesDocID = -1;

		/*
		 * The bounding box holding everything closer than the worst hit in the
		 * queue, encoded the way LatLonPoint encodes a coordinate so that a
		 * document is rejected without decoding it. A second longitude range
		 * covers the case where the box crosses the dateline.
		 */
		private int minLat = Integer.MIN_VALUE;
		private int maxLat = Integer.MAX_VALUE;
		private int minLon = Integer.MIN_VALUE;
		private int maxLon = Integer.MAX_VALUE;
		private int minLon2 = Integer.MAX_VALUE;

		private int setBottomCounter = 0;

		/** Skipping over the current segment, or {@code null} for none. */
		private Skipping skipping;

		/**
		 * How many narrower sets have been built, over every segment read so
		 * far. Read by {@link DistanceSortField#buildsOf(FieldComparator)}.
		 */
		private int builds;

		DistanceComparator(
			String values,
			String points,
			double latitude,
			double longitude,
			int numHits,
			Pruning pruning
		) {
			this.values = values;
			this.points = points;
			this.latitude = latitude;
			this.longitude = longitude;
			this.slots = new double[numHits];
			this.pruning = pruning;
		}

		@Override
		public void disableSkipping() {
			pruning = Pruning.NONE;
			skipping = null;
		}

		@Override
		public LeafFieldComparator getLeafComparator(LeafReaderContext context)
			throws IOException
		{
			currentDocs = DocValues.getSortedNumeric(context.reader(), values);
			valuesDocID = -1;
			skipping = pruning == Pruning.NONE ? null : skippingOver(context);

			return this;
		}

		/**
		 * Get skipping over a segment, or {@code null} where the points cannot
		 * answer for it.
		 */
		private Skipping skippingOver(LeafReaderContext context) throws IOException {
			var reader = context.reader();

			var info = reader.getFieldInfos().fieldInfo(points);
			if(info == null
				|| info.getPointDimensionCount() != 2
				|| info.getPointNumBytes() != Integer.BYTES)
			{
				// Written before the field gained its filter usage, or not a point
				return null;
			}

			if(reader.getPointValues(points) == null) {
				return null;
			}

			return new Skipping(context);
		}

		@Override
		public int compare(int slot1, int slot2) {
			return Double.compare(slots[slot1], slots[slot2]);
		}

		@Override
		public void setBottom(int slot) throws IOException {
			bottom = slots[slot];
			queueFull = true;

			/*
			 * Sample once the queue has been improved often enough: a search
			 * that keeps replacing the worst hit would otherwise spend its time
			 * building boxes.
			 */
			if(setBottomCounter < BOX_SAMPLE_AFTER || (setBottomCounter & 0x3F) == 0x3F) {
				var box = Rectangle.fromPointDistance(latitude, longitude, metersOf(bottom));

				minLat = GeoEncodingUtils.encodeLatitude(box.minLat);
				maxLat = GeoEncodingUtils.encodeLatitude(box.maxLat);

				if(box.crossesDateline()) {
					minLon = Integer.MIN_VALUE;
					maxLon = GeoEncodingUtils.encodeLongitude(box.maxLon);
					minLon2 = GeoEncodingUtils.encodeLongitude(box.minLon);
				} else {
					minLon = GeoEncodingUtils.encodeLongitude(box.minLon);
					maxLon = GeoEncodingUtils.encodeLongitude(box.maxLon);
					minLon2 = Integer.MAX_VALUE;
				}
			}

			setBottomCounter++;

			if(skipping != null) {
				skipping.update();
			}
		}

		@Override
		public void setTopValue(Double value) {
			topValue = value.doubleValue();
		}

		@Override
		public void setScorer(Scorable scorer) throws IOException {
			if(skipping != null) {
				skipping.setScorer(scorer);
			}
		}

		@Override
		public void setHitsThresholdReached() throws IOException {
			hitsThresholdReached = true;

			if(skipping != null) {
				skipping.update();
			}
		}

		@Override
		public DocIdSetIterator competitiveIterator() {
			return skipping == null ? null : skipping.iterator;
		}

		@Override
		public int compareBottom(int doc) throws IOException {
			if(doc > currentDocs.docID()) {
				currentDocs.advance(doc);
			}

			if(doc < currentDocs.docID()) {
				return Double.compare(bottom, Double.POSITIVE_INFINITY);
			}

			readValues();

			var count = currentDocs.docValueCount();
			var cmp = -1;

			for(var i = 0; i < count; i++) {
				var encoded = currentValues[i];

				var latitudeBits = (int) (encoded >> 32);
				if(latitudeBits < minLat || latitudeBits > maxLat) {
					continue;
				}

				var longitudeBits = (int) (encoded & 0xFFFFFFFFL);
				if((longitudeBits < minLon || longitudeBits > maxLon) && longitudeBits < minLon2) {
					continue;
				}

				cmp = Math.max(cmp, Double.compare(bottom, sortKeyOf(encoded)));

				// Competitive already, so the rest of the values change nothing
				if(cmp > 0) {
					return cmp;
				}
			}

			return cmp;
		}

		@Override
		public int compareTop(int doc) throws IOException {
			return Double.compare(topValue, metersOf(sortKey(doc)));
		}

		@Override
		public void copy(int slot, int doc) throws IOException {
			slots[slot] = sortKey(doc);

			if(skipping != null) {
				skipping.visited(doc);
			}
		}

		@Override
		public Double value(int slot) {
			return Double.valueOf(metersOf(slots[slot]));
		}

		/**
		 * Get the distance of a document as a haversine sort key, taking the
		 * smallest of the values it holds.
		 */
		private double sortKey(int doc) throws IOException {
			if(doc > currentDocs.docID()) {
				currentDocs.advance(doc);
			}

			var smallest = Double.POSITIVE_INFINITY;
			if(doc == currentDocs.docID()) {
				readValues();

				var count = currentDocs.docValueCount();
				for(var i = 0; i < count; i++) {
					smallest = Math.min(smallest, sortKeyOf(currentValues[i]));
				}
			}

			return smallest;
		}

		private double sortKeyOf(long encoded) {
			return SloppyMath.haversinSortKey(
				latitude,
				longitude,
				GeoEncodingUtils.decodeLatitude((int) (encoded >> 32)),
				GeoEncodingUtils.decodeLongitude((int) (encoded & 0xFFFFFFFFL))
			);
		}

		/**
		 * Read the values of the document the doc values are positioned at,
		 * unless they have been read already.
		 */
		private void readValues() throws IOException {
			if(valuesDocID == currentDocs.docID()) {
				return;
			}

			valuesDocID = currentDocs.docID();

			var count = currentDocs.docValueCount();
			if(count > currentValues.length) {
				currentValues = new long[ArrayUtil.oversize(count, Long.BYTES)];
			}

			for(var i = 0; i < count; i++) {
				currentValues[i] = currentDocs.nextValue();
			}
		}

		/**
		 * Turn a haversine sort key into meters.
		 */
		private static double metersOf(double sortKey) {
			return Double.isInfinite(sortKey) ? sortKey : SloppyMath.haversinMeters(sortKey);
		}

		/**
		 * The documents of one segment that can still make the page.
		 *
		 * Starts as every document of the segment and is replaced with the
		 * answer of a distance query as the queue improves. Building that
		 * answer walks the point tree, so it happens only when the radius has
		 * at least halved, and only every so often once it has been attempted
		 * often enough.
		 */
		private final class Skipping {
			private final LeafReaderContext context;
			private final int maxDoc;
			private final UpdateableIterator iterator = new UpdateableIterator();

			private IndexSearcher searcher;

			/** The highest document already collected. */
			private int maxDocVisited = -1;

			/**
			 * How many documents the current iterator is expected to answer, or
			 * {@code -1} until a scorer has said.
			 */
			private long iteratorCost = -1;

			/**
			 * The radius of the last attempt, in meters, whether or not it ended
			 * in a narrower set. An attempt that was turned down costs an
			 * estimate over the point tree, so the next one waits for the same
			 * halving a successful one does.
			 */
			private double attemptedRadius = Double.POSITIVE_INFINITY;

			private int updateCounter = 0;
			private int skipInterval = MIN_SKIP_INTERVAL;
			private int failures = 0;

			private Skipping(LeafReaderContext context) {
				this.context = context;
				this.maxDoc = context.reader().maxDoc();

				iterator.update(DocIdSetIterator.all(maxDoc));
			}

			void visited(int doc) {
				maxDocVisited = doc;
			}

			void setScorer(Scorable scorer) throws IOException {
				if(iteratorCost != -1) {
					return;
				}

				iteratorCost = scorer instanceof Scorer s ? s.iterator().cost() : maxDoc;
				update();
			}

			/**
			 * Narrow what the search reads, where the page is far enough along
			 * for a narrower set to be worth building.
			 */
			void update() throws IOException {
				if(!hitsThresholdReached || !queueFull) {
					return;
				}

				var radius = metersOf(bottom);
				if(!Double.isFinite(radius)) {
					// Everything collected so far is a document without a point
					return;
				}

				if(radius >= attemptedRadius || radius > attemptedRadius / 2) {
					return;
				}

				updateCounter++;
				if(updateCounter > SKIP_ATTEMPTS_BEFORE_SAMPLING
					&& (updateCounter & (skipInterval - 1)) != skipInterval - 1)
				{
					return;
				}

				attemptedRadius = radius;
				build(radius);
			}

			/**
			 * Replace the iterator with the documents inside the bounding box of
			 * the circle holding everything nearer than the worst hit in the
			 * queue.
			 *
			 * The box holds the circle, so every document that can still make
			 * the page is inside it, and the corners only add documents that
			 * {@code compareBottom} measures and turns down. Weighting a box
			 * costs four encoded bounds. Weighting a circle precomputes a grid
			 * of sub-boxes, which costs more than the walk of the point tree it
			 * saves at the rate this is rebuilt.
			 */
			private void build(double radius) throws IOException {
				/*
				 * A hit exactly as far away as the worst one in the queue is
				 * still competitive for the sort fields after this one. The
				 * margin covers the rounding of the round trip between a
				 * haversine sort key and meters, and stays far below the
				 * centimeter a coordinate is encoded to.
				 */
				var box = Rectangle.fromPointDistance(
					latitude,
					longitude,
					radius + Math.max(1e-3, radius * 1e-9)
				);

				// A box around a pole or over the dateline is the query's to handle
				var query = LatLonPoint.newBoxQuery(
					points,
					box.minLat,
					box.maxLat,
					box.minLon,
					box.maxLon
				);

				var searcher = searcher();
				var weight = searcher
					.rewrite(query)
					.createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);

				var supplier = weight.scorerSupplier(context);
				if(supplier == null) {
					iterator.update(DocIdSetIterator.empty());
					iteratorCost = 0;
					builds++;
					adjustInterval(true);
					return;
				}

				/*
				 * Estimated off the point tree without materializing anything.
				 * Until a scorer has said what the search reads, the whole
				 * segment is the number to beat.
				 */
				var reading = iteratorCost < 0 ? maxDoc : iteratorCost;
				if(supplier.cost() >= reading >>> 3) {
					// Fewer than eight documents in nine left out, so not worth building
					adjustInterval(false);
					return;
				}

				var found = supplier.get(Long.MAX_VALUE).iterator();
				found.advance(maxDocVisited + 1);

				iterator.update(found);
				iteratorCost = found.cost();
				builds++;
				adjustInterval(true);
			}

			/**
			 * Get the searcher the distance query is weighted against. It reads
			 * nothing but the points of the segment, and is only what a weight
			 * has to be created from.
			 */
			private IndexSearcher searcher() {
				if(searcher == null) {
					searcher = new IndexSearcher(context.reader());
				}

				return searcher;
			}

			/**
			 * Sample rebuild attempts more often while they pay off and less
			 * often while they do not.
			 */
			private void adjustInterval(boolean narrowed) {
				if(updateCounter <= SKIP_ATTEMPTS_BEFORE_SAMPLING) {
					return;
				}

				if(narrowed) {
					skipInterval = Math.max(skipInterval / 2, MIN_SKIP_INTERVAL);
					failures = 0;
				} else if(failures >= 3) {
					skipInterval = Math.min(skipInterval * 2, MAX_SKIP_INTERVAL);
					failures = 0;
				} else {
					failures++;
				}
			}
		}
	}

	/**
	 * The documents a search still reads, over an iterator that is replaced as
	 * the page fills.
	 *
	 * The replacement does not have to be positioned where this iterator is.
	 * It is advanced on the next call.
	 */
	private static final class UpdateableIterator extends DocIdSetIterator {
		private DocIdSetIterator in = DocIdSetIterator.empty();
		private int doc = -1;

		void update(DocIdSetIterator iterator) {
			this.in = Objects.requireNonNull(iterator);
		}

		@Override
		public int docID() {
			return doc;
		}

		@Override
		public int nextDoc() throws IOException {
			return advance(doc + 1);
		}

		@Override
		public int advance(int target) throws IOException {
			var current = in.docID();
			if(current < target) {
				current = in.advance(target);
			}

			return this.doc = current;
		}

		@Override
		public long cost() {
			return in.cost();
		}
	}
}
