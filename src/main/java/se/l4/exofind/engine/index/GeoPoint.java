package se.l4.exofind.engine.index;

/**
 * A point on the earth, as a WGS 84 latitude and longitude in degrees.
 *
 * This is the value a geo point field holds - what a document carries, and
 * what reading one back returns. Whether the coordinates are on the earth at
 * all is judged when a document is indexed, so a value here is what was given
 * rather than something already checked.
 *
 * @param latitude
 *   degrees north of the equator, {@code -90} to {@code 90}
 * @param longitude
 *   degrees east of the prime meridian, {@code -180} to {@code 180}
 */
public record GeoPoint(double latitude, double longitude) {
}
