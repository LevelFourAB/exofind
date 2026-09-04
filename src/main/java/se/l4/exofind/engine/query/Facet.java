package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * A request to count the documents a search matches per value of a field.
 *
 * The counts are what a list of filters to pick from is built out of - each
 * value with how many results choosing it would leave. A facet is counted
 * sideways of the {@link SearchRequest#filters() filters} it
 * {@link #excludes(String) excludes} - by default the ones on its own field:
 * ticking a category still shows what the other categories would hold, while
 * every other filter and the whole query keep narrowing the counts.
 * {@link #excludeFilters()} is what changes the default.
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
 * A facet given a {@link #prefix() prefix} answers only the values that start
 * with it, counted the same way. A filter panel asks for this while a value is
 * typed into it. The prefix and the values of a string field are compared
 * folded, in case and Unicode form, by the normalize step of the autocomplete
 * chain of the field, so {@code rö} finds {@code Röd}. A number, boolean or
 * timestamp field compares the prefix with the value as the result shows it,
 * ignoring case. A field whose values are paths through a tree refuses a
 * prefix.
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
 * @param excludeFilters
 *   the field paths whose filter entries are left out of this facet's
 *   counts, or {@code null} for the facet's own field - the sideways rule a
 *   filtering UI wants. Empty leaves nothing out, so the counts are exactly
 *   the results; more paths widen the scope, which is what one control
 *   backed by several fields asks for
 * @param prefix
 *   what the answered values have to start with, or {@code null} to answer
 *   every value. Blank counts as {@code null}. Refused together with
 *   {@code ranges}
 * @param prefixEdits
 *   how many mistakes a value may be away from the prefix and still be
 *   answered, between 0 and {@link #MAX_PREFIX_EDITS}. A value is answered
 *   when some reading of the prefix within that many edits starts it, so a
 *   half typed word finds what it would have with the mistake fixed. The
 *   first character of the prefix is never read as a mistake. Only string
 *   fields compare this way; every other field answers as with zero
 */
public record Facet(
	String name,
	String field,
	int limit,
	Order order,
	ImmutableList<Range> ranges,
	String path,
	int depth,
	ImmutableList<String> excludeFilters,
	String prefix,
	int prefixEdits
) {
	/**
	 * The most mistakes a prefix may forgive. One is what a word still being
	 * typed forgives in a text search, and two on a short prefix reaches a
	 * large part of any dictionary.
	 */
	public static final int MAX_PREFIX_EDITS = 2;

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
		VALUE,

		/**
		 * The order the search settings of the index declare for the values
		 * of the field, which is what {@code S, M, L, XL} takes. A declared
		 * value comes first, by its declared order and then by count; every
		 * other value follows, by count. A field whose settings declare no
		 * order answers by count alone.
		 */
		DECLARED
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

		if(excludeFilters == null) {
			excludeFilters = Lists.immutable.of(field);
		}

		for(var excluded : excludeFilters) {
			if(excluded == null || excluded.isBlank()) {
				throw new IllegalArgumentException(
					"A path to leave the filters of out can not be blank - leave the whole list out for the facet's own field"
				);
			}
		}

		if(prefix != null && prefix.isBlank()) {
			prefix = null;
		}

		if(prefix != null && !ranges.isEmpty()) {
			throw new IllegalArgumentException(
				"Counting into buckets answers one count per bucket, so it can not also pick values by prefix"
			);
		}

		if(prefixEdits < 0 || prefixEdits > MAX_PREFIX_EDITS) {
			throw new IllegalArgumentException(
				"A prefix forgives between 0 and " + MAX_PREFIX_EDITS + " mistakes"
			);
		}
	}

	/**
	 * Count per value, answering the values that start with the prefix as it
	 * was typed.
	 */
	public Facet(
		String name,
		String field,
		int limit,
		Order order,
		ImmutableList<Range> ranges,
		String path,
		int depth,
		ImmutableList<String> excludeFilters,
		String prefix
	) {
		this(name, field, limit, order, ranges, path, depth, excludeFilters, prefix, 0);
	}

	/**
	 * Count per value, every value.
	 */
	public Facet(
		String name,
		String field,
		int limit,
		Order order,
		ImmutableList<Range> ranges,
		String path,
		int depth,
		ImmutableList<String> excludeFilters
	) {
		this(name, field, limit, order, ranges, path, depth, excludeFilters, null);
	}

	/**
	 * Count per value.
	 */
	public Facet(String name, String field, int limit, Order order) {
		this(name, field, limit, order, null, null, DEFAULT_DEPTH, null, null);
	}

	/**
	 * Get whether a filter entry naming the given field path is left out of
	 * this facet's counts.
	 *
	 * An entry is left out when its path equals one of
	 * {@link #excludeFilters()} or falls under it - {@code variants.color}
	 * falls under {@code variants}. Exclusion is always of whole entries: a
	 * {@code nested} filter whose clauses name several fields carries the one
	 * path they share, so it is left out together or not at all.
	 *
	 * @param filterPath
	 *   the field path a filter entry names - the field of a {@code field}
	 *   clause, or the most specific path covering everything a {@code nested}
	 *   clause reads
	 * @return
	 */
	public boolean excludes(String filterPath) {
		return excludeFilters.anySatisfy(excluded ->
			filterPath.equals(excluded) || filterPath.startsWith(excluded + '.')
		);
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
		return new Facet(null, field, DEFAULT_LIMIT, Order.COUNT, null, null, DEFAULT_DEPTH, null);
	}

	/**
	 * Key the counts by the given name instead of the field.
	 *
	 * @param name
	 * @return
	 */
	public Facet withName(String name) {
		return new Facet(name, field, limit, order, ranges, path, depth, excludeFilters, prefix, prefixEdits);
	}

	/**
	 * Set how many values to bring back at most.
	 *
	 * @param limit
	 * @return
	 */
	public Facet withLimit(int limit) {
		return new Facet(name, field, limit, order, ranges, path, depth, excludeFilters, prefix, prefixEdits);
	}

	/**
	 * Set the order values come back in.
	 *
	 * @param order
	 * @return
	 */
	public Facet withOrder(Order order) {
		return new Facet(name, field, limit, order, ranges, path, depth, excludeFilters, prefix, prefixEdits);
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
			name, field, limit, order, Lists.immutable.of(ranges), path, depth, excludeFilters,
			prefix, prefixEdits
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
			name, field, limit, order, Lists.immutable.ofAll(ranges), path, depth, excludeFilters,
			prefix, prefixEdits
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
		return new Facet(name, field, limit, order, ranges, path, depth, excludeFilters, prefix, prefixEdits);
	}

	/**
	 * Set how many levels of the tree to count.
	 *
	 * @param depth
	 * @return
	 */
	public Facet withDepth(int depth) {
		return new Facet(name, field, limit, order, ranges, path, depth, excludeFilters, prefix, prefixEdits);
	}

	/**
	 * Set the field paths whose filter entries are left out of the counts,
	 * replacing the facet's own field. Give none at all to count inside every
	 * filter, so the counts are exactly the results.
	 *
	 * @param excludeFilters
	 * @return
	 */
	public Facet withExcludeFilters(String... excludeFilters) {
		return new Facet(
			name, field, limit, order, ranges, path, depth,
			Lists.immutable.of(excludeFilters), prefix, prefixEdits
		);
	}

	/**
	 * Set the field paths whose filter entries are left out of the counts,
	 * replacing the facet's own field. An empty iterable counts inside every
	 * filter, so the counts are exactly the results.
	 *
	 * @param excludeFilters
	 * @return
	 */
	public Facet withExcludeFilters(Iterable<String> excludeFilters) {
		return new Facet(
			name, field, limit, order, ranges, path, depth,
			Lists.immutable.ofAll(excludeFilters), prefix, prefixEdits
		);
	}

	/**
	 * Answer only the values that start with the given prefix, see the class
	 * comment for how the two are compared.
	 *
	 * @param prefix
	 *   the prefix, or {@code null} to answer every value
	 * @return
	 */
	public Facet withPrefix(String prefix) {
		return new Facet(name, field, limit, order, ranges, path, depth, excludeFilters, prefix, prefixEdits);
	}

	/**
	 * Answer values the given number of mistakes away from the prefix as
	 * well, see the class comment for how the two are compared.
	 *
	 * @param prefixEdits
	 *   the mistakes to forgive, or zero to answer only the values the
	 *   prefix starts
	 * @return
	 */
	public Facet withPrefixEdits(int prefixEdits) {
		return new Facet(name, field, limit, order, ranges, path, depth, excludeFilters, prefix, prefixEdits);
	}
}
