package se.l4.exofind.engine.api.v1alpha1.admin;

import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.patch.PatchKeys;

/**
 * The key each list of the search settings declares, so a change can name an
 * entry by one word.
 *
 * <p>Search settings have a shape no client defines, so the keys are declared
 * here instead of in a stored definition:
 *
 * <pre>
 * ranking.signals[sales]        the signals reading the field `sales`
 * ranking.tieBreakers[sales]    the tie breaker on the field `sales`
 * fields.size.values[S]         the declared value `S` of the field `size`
 * </pre>
 *
 * <p>A key of `ranking.signals` names every entry reading that field, because
 * two signals may read one field with different shapes. A key of
 * `ranking.tieBreakers` and of `fields.<name>.values` names at most one, which
 * the validation of each holds them to.
 *
 * <p>Every other list is reached by a field inside its entries, or by no
 * selector at all.
 */
final class SearchSettingsKeys {
	/** What the settings declare, as {@link ObjectPatch} asks for it. */
	static final PatchKeys KEYS = SearchSettingsKeys::keyOf;

	private SearchSettingsKeys() {
	}

	private static String keyOf(ListIterable<String> names) {
		if(names.size() == 2
			&& "ranking".equals(names.get(0))
			&& ("signals".equals(names.get(1)) || "tieBreakers".equals(names.get(1)))) {
			return "field";
		}

		/*
		 * The name of a field settings entry is one name of the path even when
		 * it reads as a dotted path through objects, because a change writes
		 * the dot escaped.
		 */
		if(names.size() == 3
			&& "fields".equals(names.get(0))
			&& "values".equals(names.get(2))) {
			return "value";
		}

		return null;
	}
}
