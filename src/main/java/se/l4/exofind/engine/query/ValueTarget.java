package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * The value of a field that stands for a document: a field, the clauses that
 * say which of its values count, and the targets read instead where a
 * document holds none of them.
 *
 * This is how a search names the price a customer sees when a product is
 * priced on many lists. The field alone can not say it, because every list
 * holds the same field; the caller knows which list the customer is on and
 * which list stands in where a product has no price on it.
 *
 * A target inside a {@code nested} list is read one value at a time, and
 * {@code when} says which values count: the clauses that have to hold in the
 * same value as the field, such as the list id next to the amount. A target
 * outside a list may carry {@code when} as well, and the clauses then have to
 * hold for the document.
 *
 * The {@code fallback} targets are read instead, in order, for a document that
 * holds no value on this one - a product with no price on the customer's list
 * is read on the store's list. A fallback may carry fallbacks of its own,
 * which are read after it and before the next fallback beside it.
 *
 * What the value is used for decides what else a target has to be: a reading
 * of a number in {@link TextQuery} needs every field of the chain in one unit,
 * and a {@link FieldSort} or a {@link Facet} needs every field of it to be of
 * one type.
 *
 * @param field
 *   name of the field, as it is called in the definition of the index
 * @param when
 *   the clauses that have to hold where the value is read, empty for none
 * @param fallback
 *   the targets read instead where the document holds no value on this one,
 *   empty for none
 */
public record ValueTarget(
	String field,
	ImmutableList<Query> when,
	ImmutableList<ValueTarget> fallback
) {
	public ValueTarget {
		if(field == null || field.isBlank()) {
			throw new IllegalArgumentException("A target needs a field");
		}

		if(when == null) {
			when = Lists.immutable.empty();
		}

		if(fallback == null) {
			fallback = Lists.immutable.empty();
		}
	}

	/**
	 * Read the given field.
	 *
	 * @param field
	 * @return
	 */
	public static ValueTarget of(String field) {
		return new ValueTarget(field, null, null);
	}

	/**
	 * Get this target read only where the given clauses hold.
	 *
	 * @param when
	 * @return
	 */
	public ValueTarget withWhen(Query... when) {
		return new ValueTarget(field, Lists.immutable.of(when), fallback);
	}

	/**
	 * Get this target with the given targets read instead where a document
	 * holds no value on it.
	 *
	 * @param fallback
	 * @return
	 */
	public ValueTarget withFallback(ValueTarget... fallback) {
		return new ValueTarget(field, when, Lists.immutable.of(fallback));
	}

	/**
	 * Get whether this target reads anything but every value of its field -
	 * whether it carries {@code when} clauses or fallbacks.
	 *
	 * @return
	 */
	public boolean selects() {
		return when.notEmpty() || fallback.notEmpty();
	}
}
