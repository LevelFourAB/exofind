package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

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
 * An ordering given {@code when} or {@code fallback} names its values itself,
 * the way a {@link ValueTarget} does, and the {@link NestedQuery} clauses of
 * the search take no part: a document is ordered by the values of the first
 * step of the chain it holds any value on, and by the end of those the
 * ordering asks for. That is ordering by the price a customer sees - the price
 * on their own list, or on the store's list where a product has none on
 * theirs - whatever the search matched on. Only a number or a timestamp field
 * orders this way, and every field of the chain has to be of one type.
 *
 * @param field
 *   name of the field, as it is called in the definition of the index
 * @param order
 *   which way to order
 * @param when
 *   the clauses that have to hold where the value is read, empty for none -
 *   see {@link ValueTarget#when()}
 * @param fallback
 *   the targets read instead where a document holds no value on the field,
 *   empty for none - see {@link ValueTarget#fallback()}
 */
public record FieldSort(
	String field,
	Order order,
	ImmutableList<Query> when,
	ImmutableList<ValueTarget> fallback
) implements SortBy {
	public FieldSort {
		if(order == null) {
			order = Order.ASCENDING;
		}

		if(when == null) {
			when = Lists.immutable.empty();
		}

		if(fallback == null) {
			fallback = Lists.immutable.empty();
		}
	}

	/**
	 * Order by every value of the field the search matched.
	 */
	public FieldSort(String field, Order order) {
		this(field, order, null, null);
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
		return new FieldSort(field, Order.DESCENDING, when, fallback);
	}

	/**
	 * Get this ordering reading only the values where the given clauses hold.
	 *
	 * @param when
	 * @return
	 */
	public FieldSort withWhen(Query... when) {
		return new FieldSort(field, order, Lists.immutable.of(when), fallback);
	}

	/**
	 * Get this ordering with the given targets read instead where a document
	 * holds no value on the field.
	 *
	 * @param fallback
	 * @return
	 */
	public FieldSort withFallback(ValueTarget... fallback) {
		return new FieldSort(field, order, when, Lists.immutable.of(fallback));
	}

	/**
	 * Get the values this ordering reads, as a target.
	 *
	 * @return
	 */
	public ValueTarget target() {
		return new ValueTarget(field, when, fallback);
	}
}
