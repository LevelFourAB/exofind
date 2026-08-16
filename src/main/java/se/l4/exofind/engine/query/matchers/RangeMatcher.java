package se.l4.exofind.engine.query.matchers;

/**
 * Match values that fall between two bounds.
 *
 * Either bound may be left out, which leaves that side open - what a slider
 * that has only had one of its handles moved asks for. Both bounds being open
 * is not a range but {@link AnyMatcher}, and is rejected rather than quietly
 * meaning something else.
 *
 * @param lower
 *   the value the range starts at, or {@code null} for open
 * @param lowerInclusive
 *   if a value equal to {@code lower} is inside the range
 * @param upper
 *   the value the range ends at, or {@code null} for open
 * @param upperInclusive
 *   if a value equal to {@code upper} is inside the range
 */
public record RangeMatcher(
	Object lower,
	boolean lowerInclusive,
	Object upper,
	boolean upperInclusive
) implements Matcher {
	public RangeMatcher {
		if(lower == null && upper == null) {
			throw new IllegalArgumentException(
				"A range needs at least one bound, use AnyMatcher to match every value"
			);
		}
	}

	@Override
	public String id() {
		return "range";
	}

	/**
	 * Match values from {@code lower} to {@code upper}, both included.
	 *
	 * @param lower
	 * @param upper
	 * @return
	 */
	public static RangeMatcher between(Object lower, Object upper) {
		return new RangeMatcher(lower, true, upper, true);
	}

	/**
	 * Match values that are the given value or above it.
	 *
	 * @param lower
	 * @return
	 */
	public static RangeMatcher atLeast(Object lower) {
		return new RangeMatcher(lower, true, null, false);
	}

	/**
	 * Match values above the given value.
	 *
	 * @param lower
	 * @return
	 */
	public static RangeMatcher greaterThan(Object lower) {
		return new RangeMatcher(lower, false, null, false);
	}

	/**
	 * Match values that are the given value or below it.
	 *
	 * @param upper
	 * @return
	 */
	public static RangeMatcher atMost(Object upper) {
		return new RangeMatcher(null, false, upper, true);
	}

	/**
	 * Match values below the given value.
	 *
	 * @param upper
	 * @return
	 */
	public static RangeMatcher lessThan(Object upper) {
		return new RangeMatcher(null, false, upper, false);
	}
}
