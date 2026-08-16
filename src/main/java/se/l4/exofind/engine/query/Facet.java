package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * A request to count the documents a search matches per value of a field.
 *
 * The counts are what a list of filters to pick from is built out of - each
 * value with how many results choosing it would leave. A facet is counted
 * sideways of the {@link SearchRequest#filters() filters} on its own field:
 * ticking a category still shows what the other categories would hold, while
 * every other filter and the whole query keep narrowing the counts.
 *
 * A facet given {@link #ranges() ranges} counts into those buckets instead of
 * per value - what a price or date facet shows. The counts come back one per
 * bucket, in the order the buckets were given; {@code limit} and {@code order}
 * take no part.
 *
 * A field inside an object is named by its dotted path and counts how many
 * documents hold each value there, so a product holding three red variants is
 * one red product. Only the values the search matched are counted, which is
 * what its {@link NestedQuery} clauses say.
 *
 * A field whose values are paths through a tree counts a level at a time and
 * answers the counts nested, which is what a category navigation is built out
 * of: {@link #path()} says which level to count the children of and
 * {@link #depth()} how far below it to go. Such a field always counts as a
 * tree, so a facet that gives neither answers the top level.
 *
 * @param name
 *   what the counts are keyed by in the result. Defaults to the field, and
 *   only has to be given when one search counts the same field twice
 * @param field
 *   name of the field to count, as it is called in the definition of the
 *   index. The field has to be defined for faceting
 * @param limit
 *   how many values to bring back at most, between 1 and {@link #MAX_LIMIT}.
 *   Counting down a tree applies it per level
 * @param order
 *   the order values come back in, see {@link Order}. Counting down a tree
 *   orders each level on its own
 * @param ranges
 *   the buckets to count into, at most {@link #MAX_LIMIT} of them - empty to
 *   count per value
 * @param path
 *   the level of the tree to count the children of, or {@code null} to count
 *   from the top. Only means something for a field whose values are paths,
 *   which is what refuses it on any other
 * @param depth
 *   how many levels below {@link #path()} to count, between 1 and
 *   {@link #MAX_DEPTH}
 */
public record Facet(
	String name,
	String field,
	int limit,
	Order order,
	ImmutableList<Range> ranges,
	String path,
	int depth
) {
	/**
	 * How many values a facet brings back when nothing else is asked for.
	 */
	public static final int DEFAULT_LIMIT = 10;

	/**
	 * The most values a facet may bring back. Counting keeps a candidate set
	 * of this size per facet, so the cap is what keeps one request from
	 * asking for a facet the size of the index.
	 */
	public static final int MAX_LIMIT = 1_000;

	/**
	 * How many levels of a tree a facet counts when nothing else is asked for.
	 * The children of one level are what a navigation shows at a time.
	 */
	public static final int DEFAULT_DEPTH = 1;

	/**
	 * The most levels of a tree a facet may count at once. Every level
	 * multiplies the nodes that can come back by the limit, so the cap is what
	 * keeps one request from asking for a tree the size of the index.
	 */
	public static final int MAX_DEPTH = 10;

	/**
	 * One bucket of a facet counted into ranges, holding the values from
	 * {@code from} up to but not including {@code to}.
	 *
	 * The bounds are values of the field being counted, in the shape a query
	 * gives them in - a number for a number field, an ISO 8601 string for a
	 * timestamp. An adjacent pair of buckets sharing a bound counts no value
	 * twice, as {@code from} is inclusive and {@code to} is not.
	 *
	 * @param from
	 *   the lowest value the bucket holds, or {@code null} for no lower end
	 * @param to
	 *   where the bucket ends, itself outside it, or {@code null} for no
	 *   upper end
	 */
	public record Range(Object from, Object to) {
		public Range {
			if(from == null && to == null) {
				throw new IllegalArgumentException("A bucket needs at least one bound");
			}
		}
	}

	/**
	 * The order the values of a facet come back in.
	 */
	public enum Order {
		/**
		 * The most common values first, which is what a list of filters
		 * usually leads with.
		 */
		COUNT,

		/**
		 * Ascending by the value itself, the order a list of sizes or years
		 * reads naturally in.
		 */
		VALUE
	}

	public Facet {
		if(field == null || field.isBlank()) {
			throw new IllegalArgumentException("A facet needs a field to count");
		}

		if(name == null) {
			name = field;
		}

		if(limit < 1 || limit > MAX_LIMIT) {
			throw new IllegalArgumentException(
				"A facet brings back between 1 and " + MAX_LIMIT + " values"
			);
		}

		if(order == null) {
			order = Order.COUNT;
		}

		if(ranges == null) {
			ranges = Lists.immutable.empty();
		}

		if(ranges.size() > MAX_LIMIT) {
			throw new IllegalArgumentException(
				"A facet counts into at most " + MAX_LIMIT + " buckets"
			);
		}

		if(path != null && path.isBlank()) {
			throw new IllegalArgumentException(
				"A facet counts the children of a path - leave it out to count from the top"
			);
		}

		if(depth == 0) {
			depth = DEFAULT_DEPTH;
		}

		if(depth < 1 || depth > MAX_DEPTH) {
			throw new IllegalArgumentException(
				"A facet counts between 1 and " + MAX_DEPTH + " levels of a tree"
			);
		}

		if(!ranges.isEmpty() && (path != null || depth != DEFAULT_DEPTH)) {
			throw new IllegalArgumentException(
				"Counting into buckets answers one count per bucket, so it can not also count down a tree"
			);
		}
	}

	/**
	 * Count per value.
	 */
	public Facet(String name, String field, int limit, Order order) {
		this(name, field, limit, order, null, null, DEFAULT_DEPTH);
	}

	/**
	 * Get whether the facet asks for something only a field whose values are
	 * paths can answer.
	 *
	 * Counting one level from the top is what such a field answers anyway, so
	 * it is not an ask another field could disappoint - naming a path or
	 * reaching further down is.
	 *
	 * @return
	 */
	public boolean asksForATree() {
		return path != null || depth != DEFAULT_DEPTH;
	}

	/**
	 * Count the given field, keyed by its own name, with the most common
	 * values first.
	 *
	 * @param field
	 * @return
	 */
	public static Facet of(String field) {
		return new Facet(null, field, DEFAULT_LIMIT, Order.COUNT, null, null, DEFAULT_DEPTH);
	}

	/**
	 * Key the counts by the given name instead of the field.
	 *
	 * @param name
	 * @return
	 */
	public Facet withName(String name) {
		return new Facet(name, field, limit, order, ranges, path, depth);
	}

	/**
	 * Set how many values to bring back at most.
	 *
	 * @param limit
	 * @return
	 */
	public Facet withLimit(int limit) {
		return new Facet(name, field, limit, order, ranges, path, depth);
	}

	/**
	 * Set the order values come back in.
	 *
	 * @param order
	 * @return
	 */
	public Facet withOrder(Order order) {
		return new Facet(name, field, limit, order, ranges, path, depth);
	}

	/**
	 * Count into the given buckets instead of per value, replacing any set
	 * before.
	 *
	 * @param ranges
	 * @return
	 */
	public Facet withRanges(Range... ranges) {
		return new Facet(
			name, field, limit, order, Lists.immutable.of(ranges), path, depth
		);
	}

	/**
	 * Count into the given buckets instead of per value, replacing any set
	 * before.
	 *
	 * @param ranges
	 * @return
	 */
	public Facet withRanges(Iterable<? extends Range> ranges) {
		return new Facet(
			name, field, limit, order, Lists.immutable.ofAll(ranges), path, depth
		);
	}

	/**
	 * Count the children of the given level of the tree instead of the top,
	 * which is what drilling into a category asks for.
	 *
	 * @param path
	 * @return
	 */
	public Facet withPath(String path) {
		return new Facet(name, field, limit, order, ranges, path, depth);
	}

	/**
	 * Set how many levels of the tree to count.
	 *
	 * @param depth
	 * @return
	 */
	public Facet withDepth(int depth) {
		return new Facet(name, field, limit, order, ranges, path, depth);
	}
}
