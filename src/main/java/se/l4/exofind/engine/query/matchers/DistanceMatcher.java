package se.l4.exofind.engine.query.matchers;

/**
 * Match values within a distance of an origin - what "near me" asks for.
 *
 * Only a type with a notion of place can answer this, which today is the geo
 * point. Whether the origin is on the earth and the radius is one that can be
 * walked is judged by the type, the same way every matcher's value is.
 *
 * @param latitude
 *   degrees north of the equator the origin sits at
 * @param longitude
 *   degrees east of the prime meridian the origin sits at
 * @param radius
 *   how far from the origin a value may be, in meters
 */
public record DistanceMatcher(
	double latitude,
	double longitude,
	double radius
) implements Matcher {
	@Override
	public String id() {
		return "distance";
	}
}
