package se.l4.exofind.engine.benchmark.grouping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.util.BitSet;
import org.eclipse.collections.api.factory.primitive.ObjectLongMaps;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * Counting how many products hold each value of a field, whichever layout the
 * values live in.
 *
 * <p>A count is of products and not of matches, because that is what ticking
 * the value would leave: a product with three red variants is one red product.
 * Every layout but {@link Shape#ROLLED_UP} therefore has to roll several
 * matches up into one count, and how much that costs is what differs between
 * them - a set held for the whole search where matches arrive in any order,
 * against a stamp cleared per product where they arrive together.
 */
final class ValueCounts {
	private ValueCounts() {
	}

	/**
	 * Count values where every document that matched is a product of its own.
	 */
	static CollectorManager<?, ShapeIndex.Counts> perDocument(String field, int limit) {
		return manager(field, limit, (reader, values) -> new Rollup() {
			@Override
			public boolean counts(int doc, long ordinal) {
				return true;
			}
		});
	}

	/**
	 * Count values where the documents that matched belong to products named by
	 * doc values.
	 *
	 * @param by
	 *   what the counting may assume about the documents it is handed
	 * @param bound
	 *   how many products the index holds, which {@link By#ORDINAL} sizes its
	 *   set by and the others ignore
	 */
	static CollectorManager<?, ShapeIndex.Counts> perGroup(
		String field,
		String group,
		By by,
		int bound,
		int limit
	) {
		return manager(field, limit, (reader, values) -> {
			var ids = reader.getNumericDocValues(group);
			if(ids == null) {
				return null;
			}

			Owner owner = doc -> read(ids, doc);
			return switch(by) {
				case RUN -> new PerRun(values.getValueCount(), owner);
				case KEY -> new PerKey(values.getValueCount(), owner);
				case ORDINAL -> new PerOrdinal(values.getValueCount(), bound, owner);
			};
		});
	}

	/**
	 * Count values where the documents that matched are the values of an object
	 * field, and the product is the next document at or after them.
	 */
	static CollectorManager<?, ShapeIndex.Counts> perParent(
		String field,
		BitSetProducer parents,
		int limit
	) {
		return manager(field, limit, (reader, values) -> {
			var documents = parents.getBitSet(reader.getContext());
			if(documents == null) {
				return null;
			}

			return new PerRun(values.getValueCount(), doc -> parentOf(documents, doc));
		});
	}

	/**
	 * Reads which product a document counts towards, or {@code -1} when it
	 * counts towards none.
	 */
	private interface Owner {
		long of(int doc) throws IOException;
	}

	/**
	 * Decides whether a value of a document is the first sighting of it for the
	 * product behind that document.
	 */
	private interface Rollup {
		boolean counts(int doc, long ordinal) throws IOException;
	}

	private interface Rollups {
		Rollup open(org.apache.lucene.index.LeafReader reader, SortedSetDocValues values)
			throws IOException;
	}

	private record Value(String value, long count) {
	}

	private static long read(NumericDocValues ids, int doc) throws IOException {
		return ids.advanceExact(doc) ? ids.longValue() : -1;
	}

	private static long parentOf(BitSet parents, int doc) {
		return doc < parents.length() ? parents.nextSetBit(doc) : DocIdSetIterator.NO_MORE_DOCS;
	}

	/**
	 * Products arrive one after another, so the values already counted for one
	 * are forgotten as soon as the next turns up. Stamped rather than cleared,
	 * so moving on to the next product costs nothing.
	 */
	private static final class PerRun implements Rollup {
		private final Owner owner;
		private final int[] stamps;
		private int stamp;
		private long carried = -1;
		private int read = -1;

		PerRun(long values, Owner owner) {
			this.owner = owner;
			this.stamps = new int[(int) values];
		}

		@Override
		public boolean counts(int doc, long ordinal) throws IOException {
			if(doc != read) {
				read = doc;

				var product = owner.of(doc);
				if(product != carried) {
					carried = product;
					stamp++;
				}
			}

			if(stamps[(int) ordinal] == stamp) {
				return false;
			}

			stamps[(int) ordinal] = stamp;
			return true;
		}
	}

	/**
	 * A product may be picked up again at any later document, so every value
	 * counted for it is held until the search ends.
	 */
	private static final class PerKey implements Rollup {
		private final Owner owner;
		private final long values;
		private final LongHashSet counted = new LongHashSet();

		private int read = -1;
		private long carried = -1;

		PerKey(long values, Owner owner) {
			this.owner = owner;
			this.values = values;
		}

		@Override
		public boolean counts(int doc, long ordinal) throws IOException {
			if(doc != read) {
				read = doc;
				carried = owner.of(doc);
			}

			return carried >= 0 && counted.add(carried * values + ordinal);
		}
	}

	/**
	 * The products are numbered, so a value counted for one is remembered by
	 * setting a bit rather than by hashing a pair.
	 */
	private static final class PerOrdinal implements Rollup {
		private final Owner owner;
		private final long values;
		private final long[] counted;

		private int read = -1;
		private long carried = -1;

		PerOrdinal(long values, int bound, Owner owner) {
			this.owner = owner;
			this.values = values;
			this.counted = new long[(int) ((bound * values + 63) >>> 6)];
		}

		@Override
		public boolean counts(int doc, long ordinal) throws IOException {
			if(doc != read) {
				read = doc;
				carried = owner.of(doc);
			}

			if(carried < 0) {
				return false;
			}

			var bit = carried * values + ordinal;
			var word = (int) (bit >>> 6);
			var mask = 1L << (bit & 63);
			if((counted[word] & mask) != 0) {
				return false;
			}

			counted[word] |= mask;
			return true;
		}
	}

	private static CollectorManager<Counting, ShapeIndex.Counts> manager(
		String field,
		int limit,
		Rollups rollups
	) {
		return new CollectorManager<Counting, ShapeIndex.Counts>() {
			@Override
			public Counting newCollector() {
				return new Counting(field, rollups);
			}

			@Override
			public ShapeIndex.Counts reduce(Collection<Counting> collectors) throws IOException {
				var totals = ObjectLongMaps.mutable.<String>empty();
				for(var collector : collectors) {
					collector.addTo(totals);
				}

				var values = new ArrayList<Value>(totals.size());
				totals.forEachKeyValue((value, count) -> values.add(new Value(value, count)));
				values.sort(
					Comparator.comparingLong(Value::count).reversed()
						.thenComparing(Value::value)
				);

				var kept = Math.min(limit, values.size());
				var names = new String[kept];
				var counts = new long[kept];
				for(var i = 0; i < kept; i++) {
					names[i] = values.get(i).value();
					counts[i] = values.get(i).count();
				}

				return new ShapeIndex.Counts(names, counts);
			}
		};
	}

	/**
	 * Counts by ordinal per segment and resolves the terms once the search is
	 * over, so that a value held by many documents is read out of the
	 * dictionary once rather than once per document.
	 */
	private static final class Counting implements Collector {
		private final String field;
		private final Rollups rollups;
		private final List<Counted> counted = new ArrayList<>();

		Counting(String field, Rollups rollups) {
			this.field = field;
			this.rollups = rollups;
		}

		@Override
		public ScoreMode scoreMode() {
			return ScoreMode.COMPLETE_NO_SCORES;
		}

		@Override
		public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
			var reader = context.reader();
			var values = reader.getSortedSetDocValues(field);
			var rollup = values == null ? null : rollups.open(reader, values);
			if(rollup == null) {
				return new LeafCollector() {
					@Override
					public void setScorer(Scorable scorer) {
					}

					@Override
					public void collect(int doc) {
					}
				};
			}

			var counts = new long[(int) values.getValueCount()];
			counted.add(new Counted(values, counts));

			return new LeafCollector() {
				@Override
				public void setScorer(Scorable scorer) {
				}

				@Override
				public void collect(int doc) throws IOException {
					if(!values.advanceExact(doc)) {
						return;
					}

					for(var i = values.docValueCount(); i > 0; i--) {
						var ordinal = values.nextOrd();
						if(rollup.counts(doc, ordinal)) {
							counts[(int) ordinal]++;
						}
					}
				}
			};
		}

		void addTo(org.eclipse.collections.api.map.primitive.MutableObjectLongMap<String> totals)
			throws IOException
		{
			for(var leaf : counted) {
				for(var ordinal = 0; ordinal < leaf.counts().length; ordinal++) {
					var count = leaf.counts()[ordinal];
					if(count == 0) {
						continue;
					}

					totals.addToValue(leaf.values().lookupOrd(ordinal).utf8ToString(), count);
				}
			}
		}

		private record Counted(SortedSetDocValues values, long[] counts) {
		}
	}
}
