package se.l4.exofind.engine.query;

/**
 * Order results by how far a field's value is from an origin, nearest first.
 *
 * The field has to have been defined as sortable, the same as any other
 * ordering by a field. There is no farthest first - the distance to the far
 * side of the earth bounds nothing, so nearest first is the only order that
 * means anything.
 *
 * @param field
 *   name of the field, as it is called in the definition of the index
 * @param latitude
 *   degrees north of the equator the origin sits at
 * @param longitude
 *   degrees east of the prime meridian the origin sits at
 */
public record GeoDistanceSort(
	String field,
	double latitude,
	double longitude
) implements SortBy {
	@Override
	public String type() {
		return "distance";
	}

	@Override
	public Order order() {
		return Order.ASCENDING;
	}
}
