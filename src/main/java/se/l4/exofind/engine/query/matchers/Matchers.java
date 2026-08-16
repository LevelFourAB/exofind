package se.l4.exofind.engine.query.matchers;

/**
 * Shorthands for building the matchers a query is made of.
 *
 * <pre>
 * Query.field("category", Matchers.equalTo("fiction"))
 * Query.field("price", Matchers.atMost(30))
 * </pre>
 */
public final class Matchers {
	private Matchers() {
	}

	/**
	 * Match a value equal to the given one.
	 *
	 * @param value
	 * @return
	 */
	public static EqualsMatcher equalTo(Object value) {
		return new EqualsMatcher(value);
	}

	/**
	 * Match documents that have any value for the field.
	 *
	 * @return
	 */
	public static AnyMatcher any() {
		return AnyMatcher.INSTANCE;
	}

	/**
	 * Match a value equal to any of the given ones.
	 *
	 * @param values
	 * @return
	 */
	public static InMatcher in(Object... values) {
		return InMatcher.of(values);
	}

	/**
	 * Match a value equal to any of the given ones.
	 *
	 * @param values
	 * @return
	 */
	public static InMatcher in(Iterable<?> values) {
		return InMatcher.of(values);
	}

	/**
	 * Match a value between the given bounds, both included.
	 *
	 * @param lower
	 * @param upper
	 * @return
	 */
	public static RangeMatcher between(Object lower, Object upper) {
		return RangeMatcher.between(lower, upper);
	}

	/**
	 * Match a value that is the given one or above it.
	 *
	 * @param lower
	 * @return
	 */
	public static RangeMatcher atLeast(Object lower) {
		return RangeMatcher.atLeast(lower);
	}

	/**
	 * Match a value above the given one.
	 *
	 * @param lower
	 * @return
	 */
	public static RangeMatcher greaterThan(Object lower) {
		return RangeMatcher.greaterThan(lower);
	}

	/**
	 * Match a value that is the given one or below it.
	 *
	 * @param upper
	 * @return
	 */
	public static RangeMatcher atMost(Object upper) {
		return RangeMatcher.atMost(upper);
	}

	/**
	 * Match a value below the given one.
	 *
	 * @param upper
	 * @return
	 */
	public static RangeMatcher lessThan(Object upper) {
		return RangeMatcher.lessThan(upper);
	}

	/**
	 * Match a value starting with the given prefix.
	 *
	 * @param value
	 * @return
	 */
	public static PrefixMatcher prefix(String value) {
		return new PrefixMatcher(value);
	}

	/**
	 * Match values sitting at or below the given path of a tree.
	 *
	 * @param path
	 * @return
	 */
	public static UnderMatcher under(String path) {
		return new UnderMatcher(path);
	}

	/**
	 * Match text that someone typed, against a single field.
	 *
	 * @param text
	 * @return
	 */
	public static TextMatcher text(String text) {
		return TextMatcher.of(text);
	}

	/**
	 * Match values within a distance of an origin.
	 *
	 * @param latitude
	 * @param longitude
	 * @param radius
	 *   in meters
	 * @return
	 */
	public static DistanceMatcher withinDistance(
		double latitude,
		double longitude,
		double radius
	) {
		return new DistanceMatcher(latitude, longitude, radius);
	}
}
