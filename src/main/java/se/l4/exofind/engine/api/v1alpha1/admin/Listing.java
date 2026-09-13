package se.l4.exofind.engine.api.v1alpha1.admin;

import java.util.List;
import java.util.function.Function;

import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.Location;
import se.l4.exofind.engine.errors.ValidationException;

/**
 * One page of an admin listing, cut out of every entry the caller may see.
 *
 * <p>The index, key and reindex listings take the same three parameters:
 * {@code prefix} keeps the entries whose name starts with it, {@code after}
 * skips the entries up to and including that name, and {@code limit} caps how
 * many are answered. A listing that was cut short names the last entry it
 * holds in {@code next}, which the caller passes as {@code after} to read on.
 * Without a {@code limit} the whole listing is answered, as it was before the
 * parameters existed.
 *
 * <p>The entries are ordered by name before the page is cut, so {@code after}
 * and {@code next} name a place in that order. An entry the caller has no
 * permission on is left out before the page is cut, so it counts against
 * neither the limit nor the place to continue from.
 *
 * @param entries
 *   the entries of this page, in name order
 * @param next
 *   the name to pass as {@code after} to read the entries after this page,
 *   or {@code null} when the page holds the rest
 */
record Listing<T>(List<T> entries, String next) {
	/**
	 * Most entries one page may hold. A listing is read from one registry
	 * or one storage prefix, so the cap protects the size of the answer
	 * rather than the cost of building it.
	 */
	static final int MAX_LIMIT = 1000;

	private static final ErrorType LIMIT_INVALID =
		ErrorType.withCode("request:limit_out_of_range")
			.withStatus(400)
			.withArguments("value", "max")
			.withMessage(
				"A limit is a whole number from 1 to {{max}}, which `{{value}}` is not"
			);

	/**
	 * Cut the page a request asked for.
	 *
	 * @param visible
	 *   every entry the caller may see, in any order
	 * @param nameOf
	 *   the name an entry is ordered and filtered by
	 * @param prefix
	 *   the {@code prefix} parameter, or {@code null} to keep every name
	 * @param after
	 *   the {@code after} parameter, or {@code null} to start at the first
	 *   name
	 * @param limit
	 *   the {@code limit} parameter as it was sent, or {@code null} to answer
	 *   the rest
	 * @throws ValidationException
	 *   if the limit is not a whole number from 1 to {@link #MAX_LIMIT}
	 */
	static <T> Listing<T> of(
		ListIterable<T> visible,
		Function<T, String> nameOf,
		String prefix,
		String after,
		String limit
	) {
		var wanted = parseLimit(limit);

		var ordered = visible
			.select(entry -> {
				var name = nameOf.apply(entry);
				return (prefix == null || name.startsWith(prefix))
					&& (after == null || name.compareTo(after) > 0);
			})
			.toSortedListBy(nameOf::apply);

		if(wanted < 0 || ordered.size() <= wanted) {
			return new Listing<>(ordered.toList(), null);
		}

		var page = ordered.subList(0, wanted);
		return new Listing<>(List.copyOf(page), nameOf.apply(page.getLast()));
	}

	/**
	 * Read how many entries a request asked for, or {@code -1} when it asked
	 * for the rest.
	 */
	private static int parseLimit(String limit) {
		if(limit == null) {
			return -1;
		}

		int value;
		try {
			value = Integer.parseInt(limit.trim());
		} catch(NumberFormatException e) {
			value = 0;
		}

		if(value < 1 || value > MAX_LIMIT) {
			throw new ValidationException(
				LIMIT_INVALID.toMessage(
					Location.create("limit"),
					"value", limit,
					"max", MAX_LIMIT
				)
			);
		}

		return value;
	}
}
