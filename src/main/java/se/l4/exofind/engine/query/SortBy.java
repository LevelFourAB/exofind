package se.l4.exofind.engine.query;

/**
 * One step in the order results come back in.
 *
 * A search carries a list of these and the first one that can tell two
 * documents apart decides. Leaving the list empty orders by how well documents
 * match, which is what a search is usually expected to do.
 */
public sealed interface SortBy permits FieldSort, ScoreSort, GeoDistanceSort {
	/**
	 * Which way values are ordered.
	 */
	enum Order {
		ASCENDING,
		DESCENDING
	}

	/**
	 * Get the unique identifier for this kind of ordering.
	 *
	 * @return
	 */
	String type();

	/**
	 * Get which way this step orders.
	 *
	 * @return
	 */
	Order order();

	/**
	 * Order by how well documents match, best first.
	 *
	 * @return
	 */
	static ScoreSort score() {
		return ScoreSort.INSTANCE;
	}

	/**
	 * Order by the value of a field, smallest first.
	 *
	 * @param field
	 * @return
	 */
	static FieldSort field(String field) {
		return new FieldSort(field, Order.ASCENDING);
	}

	/**
	 * Order by the value of a field.
	 *
	 * @param field
	 * @param order
	 * @return
	 */
	static FieldSort field(String field, Order order) {
		return new FieldSort(field, order);
	}

	/**
	 * Order by how far a field's value is from the origin, nearest first.
	 *
	 * @param field
	 * @param latitude
	 * @param longitude
	 * @return
	 */
	static GeoDistanceSort distance(String field, double latitude, double longitude) {
		return new GeoDistanceSort(field, latitude, longitude);
	}
}
