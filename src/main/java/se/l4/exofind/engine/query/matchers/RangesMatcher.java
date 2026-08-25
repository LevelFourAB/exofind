package se.l4.exofind.engine.query.matchers;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * Match values that fall inside any one of several ranges.
 *
 * This is what the ticked buckets of a range facet turn into, the way a list
 * of ticked values is {@link InMatcher} - so an empty list is allowed and
 * matches nothing, and a filter nobody has picked a bucket in is the caller's
 * to leave out. A single range is {@link RangeMatcher}.
 *
 * @param ranges
 *   the ranges to look in, a value matching when any one of them holds it
 */
public record RangesMatcher(ImmutableList<RangeMatcher> ranges) implements Matcher {
	public RangesMatcher {
		if(ranges == null) {
			ranges = Lists.immutable.empty();
		}
	}

	@Override
	public String id() {
		return "ranges";
	}

	/**
	 * Match a value inside any of the given ranges.
	 *
	 * @param ranges
	 * @return
	 */
	public static RangesMatcher of(RangeMatcher... ranges) {
		return new RangesMatcher(Lists.immutable.of(ranges));
	}

	/**
	 * Match a value inside any of the given ranges.
	 *
	 * @param ranges
	 * @return
	 */
	public static RangesMatcher of(Iterable<? extends RangeMatcher> ranges) {
		return new RangesMatcher(Lists.immutable.<RangeMatcher>ofAll(ranges));
	}
}
