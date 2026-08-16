package se.l4.exofind.engine.query;

/**
 * Order results by the value of a field.
 *
 * The field has to have been defined as sortable, as the values are ordered by
 * something written when the document was indexed rather than by the value
 * itself. Where documents without a value end up is part of that definition
 * too.
 *
 * A field inside an object is named by its dotted path and orders documents by
 * one of the values they hold there - the end of them this ordering asks for,
 * so ascending by price orders products by their cheapest value. Only the
 * values the search matched take part, which is what its {@link NestedQuery}
 * clauses say.
 *
 * @param field
 *   name of the field, as it is called in the definition of the index
 * @param order
 *   which way to order
 */
public record FieldSort(String field, Order order) implements SortBy {
	public FieldSort {
		if(order == null) {
			order = Order.ASCENDING;
		}
	}

	@Override
	public String type() {
		return "field";
	}

	/**
	 * Get this ordering reversed.
	 *
	 * @return
	 */
	public FieldSort descending() {
		return new FieldSort(field, Order.DESCENDING);
	}
}
