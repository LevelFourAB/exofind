package se.l4.exofind.engine.query;

/**
 * Order results by how well they match, best first.
 *
 * Only clauses that score have anything to say here, see
 * {@link Query#scores()} - a search made only of filters leaves every document
 * matching equally well, and this step then decides nothing.
 *
 * @param order
 *   which way to order. Descending is what a search means by relevance,
 *   ascending is only useful for looking at what barely matched
 */
public record ScoreSort(Order order) implements SortBy {
	public static final ScoreSort INSTANCE = new ScoreSort(Order.DESCENDING);

	public ScoreSort {
		if(order == null) {
			order = Order.DESCENDING;
		}
	}

	@Override
	public String type() {
		return "score";
	}
}
