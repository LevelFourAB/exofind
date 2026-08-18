package se.l4.exofind.engine.benchmark.grouping;

import java.io.IOException;
import java.util.Collection;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * Turning the documents a search matched into the groups behind them, for the
 * layouts that keep a variant as a document of its own.
 *
 * <p>Three ways of doing it, differing in what they are allowed to assume and
 * therefore in what they have to remember - see {@link By}. All three visit
 * every document the search matched, which is the cost a grouping cannot get
 * out of: a page of ten products is only known once every variant that answered
 * has been rolled up into the product behind it.
 *
 * <p>All three count every group that matched, which a search over documents
 * cannot do on the side - the number of products behind a page of variants is
 * something only the grouping knows.
 */
final class GroupHeads {
	private GroupHeads() {
	}

	/**
	 * The groups a search matched.
	 *
	 * @param groups
	 *   the best {@code limit} of them, best first
	 * @param total
	 *   how many groups matched in all
	 */
	record Result(long[] groups, long total) {
	}

	/**
	 * Collect the groups a search matched, the group whose best document scored
	 * highest first.
	 *
	 * @param bound
	 *   how many groups the index holds, which {@link By#ORDINAL} sizes its
	 *   array by and the others ignore
	 */
	static CollectorManager<?, Result> byScore(String group, int limit, By by, int bound) {
		return manager(group, null, limit, true, by, bound);
	}

	/**
	 * Collect the groups a search matched, the group holding the lowest value
	 * among the documents that matched first.
	 *
	 * @param value
	 *   numeric doc values holding a double, as
	 *   {@link org.apache.lucene.document.DoubleDocValuesField} writes it
	 */
	static CollectorManager<?, Result> byMinValue(
		String group,
		String value,
		int limit,
		By by,
		int bound
	) {
		return manager(group, value, limit, false, by, bound);
	}

	/**
	 * Count the groups a search matched, ranking none of them.
	 */
	static CollectorManager<?, Long> count(String group, By by, int bound) {
		return new CollectorManager<Counting, Long>() {
			@Override
			public Counting newCollector() {
				return new Counting(group, by, bound);
			}

			@Override
			public Long reduce(Collection<Counting> collectors) {
				var total = 0L;
				for(var collector : collectors) {
					total += collector.total();
				}

				return total;
			}
		};
	}

	private static CollectorManager<Heads, Result> manager(
		String group,
		String value,
		int limit,
		boolean byScore,
		By by,
		int bound
	) {
		return new CollectorManager<Heads, Result>() {
			@Override
			public Heads newCollector() {
				return new Heads(group, value, limit, byScore, by, bound);
			}

			@Override
			public Result reduce(Collection<Heads> collectors) {
				if(collectors.size() == 1) {
					var only = collectors.iterator().next();
					return new Result(only.finished().drain(), only.total());
				}

				/*
				 * A group never spans two collectors: the documents of a
				 * product are written together and a merge keeps a segment's
				 * documents in the order it held them, so no group is split
				 * across the slices a search is cut into. Totals therefore add
				 * up rather than having to be deduplicated.
				 */
				var merged = new Top(limit, byScore);
				var total = 0L;
				for(var collector : collectors) {
					collector.finished().drainInto(merged);
					total += collector.total();
				}

				return new Result(merged.drain(), total);
			}
		};
	}

	/**
	 * Keeps the best document of every group, remembering as much as the
	 * {@link By} it was built with makes necessary.
	 */
	private static final class Heads implements Collector {
		private final String group;
		private final String value;
		private final int limit;
		private final boolean byScore;
		private final By by;

		private final Top top;

		/** Where a group already seen is kept, for {@link By#KEY}. */
		private final LongIntHashMap slots;

		/** The same, indexed rather than hashed, for {@link By#ORDINAL}. */
		private final int[] indexed;

		private long[] groups;
		private double[] keys;
		private int size;
		private boolean flushed;

		private long total;

		Heads(String group, String value, int limit, boolean byScore, By by, int bound) {
			this.group = group;
			this.value = value;
			this.limit = limit;
			this.byScore = byScore;
			this.by = by;

			this.top = new Top(limit, byScore);
			this.slots = by == By.KEY ? new LongIntHashMap() : null;
			this.indexed = by == By.ORDINAL ? new int[bound] : null;
			this.groups = by == By.RUN ? null : new long[1024];
			this.keys = by == By.RUN ? null : new double[1024];
		}

		@Override
		public ScoreMode scoreMode() {
			return byScore ? ScoreMode.COMPLETE : ScoreMode.COMPLETE_NO_SCORES;
		}

		@Override
		public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
			var reader = context.reader();
			var ids = reader.getNumericDocValues(group);
			if(ids == null) {
				// A segment holding nothing that belongs to a group
				return nothing();
			}

			var values = byScore ? null : reader.getNumericDocValues(value);

			return switch(by) {
				case RUN -> new Adjacent(ids, values);
				case KEY -> new Hashed(ids, values);
				case ORDINAL -> new Indexed(ids, values);
			};
		}

		/**
		 * Get the best groups this collector saw, moving what is still held
		 * per group into the page first.
		 */
		Top finished() {
			if(by != By.RUN && !flushed) {
				for(var slot = 0; slot < size; slot++) {
					top.offer(groups[slot], keys[slot]);
				}

				flushed = true;
			}

			return top;
		}

		long total() {
			return total;
		}

		private double keyOf(int doc, Scorable scorer, NumericDocValues values)
			throws IOException
		{
			if(byScore) {
				return scorer.score();
			}

			if(values == null || !values.advanceExact(doc)) {
				return Double.POSITIVE_INFINITY;
			}

			return Double.longBitsToDouble(values.longValue());
		}

		private boolean better(double candidate, double held) {
			return byScore ? candidate > held : candidate < held;
		}

		/**
		 * Take a group seen for the first time, and get where it is kept.
		 */
		private int take(long id, double key) {
			if(size == groups.length) {
				var grown = groups.length * 2;
				var nextGroups = new long[grown];
				var nextKeys = new double[grown];
				System.arraycopy(groups, 0, nextGroups, 0, size);
				System.arraycopy(keys, 0, nextKeys, 0, size);
				groups = nextGroups;
				keys = nextKeys;
			}

			groups[size] = id;
			keys[size] = key;
			total++;
			return size++;
		}

		/**
		 * The documents of a group arrive together, so the group being carried
		 * is finished as soon as another one turns up.
		 */
		private final class Adjacent implements LeafCollector {
			private final NumericDocValues ids;
			private final NumericDocValues values;

			private Scorable scorer;
			private long carried = -1;
			private double key;
			private boolean carrying;

			Adjacent(NumericDocValues ids, NumericDocValues values) {
				this.ids = ids;
				this.values = values;
			}

			@Override
			public void setScorer(Scorable scorer) {
				this.scorer = scorer;
			}

			@Override
			public void collect(int doc) throws IOException {
				if(!ids.advanceExact(doc)) {
					return;
				}

				var id = ids.longValue();
				var candidate = keyOf(doc, scorer, values);

				if(carrying && id == carried) {
					if(better(candidate, key)) {
						key = candidate;
					}

					return;
				}

				if(carrying) {
					finish();
				}

				carried = id;
				key = candidate;
				carrying = true;
			}

			@Override
			public void finish() {
				if(!carrying) {
					return;
				}

				top.offer(carried, key);
				total++;
				carrying = false;
			}
		}

		/**
		 * A group may be picked up again at any later document, and its
		 * identifier says nothing about where to keep it, so it is hashed.
		 */
		private final class Hashed implements LeafCollector {
			private final NumericDocValues ids;
			private final NumericDocValues values;

			private Scorable scorer;

			Hashed(NumericDocValues ids, NumericDocValues values) {
				this.ids = ids;
				this.values = values;
			}

			@Override
			public void setScorer(Scorable scorer) {
				this.scorer = scorer;
			}

			@Override
			public void collect(int doc) throws IOException {
				if(!ids.advanceExact(doc)) {
					return;
				}

				var id = ids.longValue();
				var candidate = keyOf(doc, scorer, values);

				var slot = slots.getIfAbsent(id, -1);
				if(slot >= 0) {
					if(better(candidate, keys[slot])) {
						keys[slot] = candidate;
					}

					return;
				}

				slots.put(id, take(id, candidate));
			}
		}

		/**
		 * The groups are numbered, so where a group is kept is read off an array
		 * rather than hashed. Slots are held one past their index, leaving zero
		 * to mean a group not yet seen and the array to arrive usable.
		 */
		private final class Indexed implements LeafCollector {
			private final NumericDocValues ids;
			private final NumericDocValues values;

			private Scorable scorer;

			Indexed(NumericDocValues ids, NumericDocValues values) {
				this.ids = ids;
				this.values = values;
			}

			@Override
			public void setScorer(Scorable scorer) {
				this.scorer = scorer;
			}

			@Override
			public void collect(int doc) throws IOException {
				if(!ids.advanceExact(doc)) {
					return;
				}

				var id = (int) ids.longValue();
				var candidate = keyOf(doc, scorer, values);

				var held = indexed[id];
				if(held > 0) {
					var slot = held - 1;
					if(better(candidate, keys[slot])) {
						keys[slot] = candidate;
					}

					return;
				}

				indexed[id] = take(id, candidate) + 1;
			}
		}
	}

	/**
	 * Counts the groups a search matched and ranks none of them.
	 */
	private static final class Counting implements Collector {
		private final String group;
		private final By by;

		private final LongHashSet hashed;
		private final long[] indexed;
		private long runs;

		Counting(String group, By by, int bound) {
			this.group = group;
			this.by = by;
			this.hashed = by == By.KEY ? new LongHashSet() : null;
			this.indexed = by == By.ORDINAL ? new long[(bound + 63) >>> 6] : null;
		}

		@Override
		public ScoreMode scoreMode() {
			return ScoreMode.COMPLETE_NO_SCORES;
		}

		@Override
		public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
			var ids = context.reader().getNumericDocValues(group);
			if(ids == null) {
				return nothing();
			}

			return new LeafCollector() {
				private long carried = -1;
				private boolean carrying;

				@Override
				public void setScorer(Scorable scorer) {
				}

				@Override
				public void collect(int doc) throws IOException {
					if(!ids.advanceExact(doc)) {
						return;
					}

					var id = ids.longValue();
					switch(by) {
						case RUN -> {
							if(!carrying || id != carried) {
								carried = id;
								carrying = true;
								runs++;
							}
						}
						case KEY -> hashed.add(id);
						case ORDINAL -> indexed[(int) (id >>> 6)] |= 1L << (id & 63);
					}
				}
			};
		}

		long total() {
			return switch(by) {
				case RUN -> runs;
				case KEY -> hashed.size();
				case ORDINAL -> {
					var total = 0L;
					for(var word : indexed) {
						total += Long.bitCount(word);
					}

					yield total;
				}
			};
		}
	}

	private static LeafCollector nothing() {
		return new LeafCollector() {
			@Override
			public void setScorer(Scorable scorer) {
			}

			@Override
			public void collect(int doc) {
			}
		};
	}

	/**
	 * The best few groups, kept as a heap whose root is the worst of them so
	 * that a group that cannot reach the page is turned away by one comparison.
	 */
	private static final class Top {
		private final int limit;
		private final boolean highest;
		private final long[] groups;
		private final double[] keys;
		private int size;

		Top(int limit, boolean highest) {
			this.limit = Math.max(1, limit);
			this.highest = highest;
			this.groups = new long[this.limit];
			this.keys = new double[this.limit];
		}

		void offer(long group, double key) {
			if(size < limit) {
				groups[size] = group;
				keys[size] = key;
				size++;
				up(size - 1);
				return;
			}

			if(!worse(key, group, keys[0], groups[0])) {
				groups[0] = group;
				keys[0] = key;
				down();
			}
		}

		void drainInto(Top other) {
			for(var i = 0; i < size; i++) {
				other.offer(groups[i], keys[i]);
			}
		}

		/**
		 * Get the groups held, best first, leaving nothing behind.
		 */
		long[] drain() {
			var result = new long[size];
			for(var i = size - 1; i >= 0; i--) {
				result[i] = groups[0];

				size--;
				groups[0] = groups[size];
				keys[0] = keys[size];
				down();
			}

			return result;
		}

		private void up(int at) {
			while(at > 0) {
				var parent = (at - 1) >>> 1;
				if(!worse(keys[at], groups[at], keys[parent], groups[parent])) {
					return;
				}

				swap(at, parent);
				at = parent;
			}
		}

		private void down() {
			var at = 0;
			while(true) {
				var left = at * 2 + 1;
				if(left >= size) {
					return;
				}

				var worst = left;
				var right = left + 1;
				if(right < size && worse(keys[right], groups[right], keys[left], groups[left])) {
					worst = right;
				}

				if(!worse(keys[worst], groups[worst], keys[at], groups[at])) {
					return;
				}

				swap(at, worst);
				at = worst;
			}
		}

		private void swap(int a, int b) {
			var group = groups[a];
			groups[a] = groups[b];
			groups[b] = group;

			var key = keys[a];
			keys[a] = keys[b];
			keys[b] = key;
		}

		/**
		 * Whether one group belongs further from the page than another. Groups
		 * that tie on their key are ordered by identifier, so that a page is the
		 * same page whichever order the groups were offered in.
		 */
		private boolean worse(double key, long group, double against, long againstGroup) {
			if(key != against) {
				return highest ? key < against : key > against;
			}

			return group > againstGroup;
		}
	}
}
