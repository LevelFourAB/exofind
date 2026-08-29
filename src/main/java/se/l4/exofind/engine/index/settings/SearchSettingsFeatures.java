package se.l4.exofind.engine.index.settings;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.SetIterable;

/**
 * The capabilities a stored search settings object can use, and which of them
 * this build has.
 *
 * <p>Protobuf keeps fields it has no code for, so a node reading settings
 * written by a newer version sees the parts it understands and drops the rest.
 * Settings change what a search answers, which makes that unsafe in both
 * directions at once: a node that applied half an object would search
 * differently from one that applied all of it, without either being able to
 * tell. Whichever version stores an object therefore writes down what it uses,
 * and a node that does not know a name on that list sets the whole object
 * aside - searching with the definition alone - rather than honouring part of
 * it.
 *
 * <p>This only works if the names are stable, so a name here is never renamed
 * or reused once released - it is a value written to disk, not an identifier.
 */
public final class SearchSettingsFeatures {
	/**
	 * The object carries a ranking that replaces the definition's.
	 */
	public static final String RANKING = "ranking";

	/**
	 * The object carries synonym sets applied to the text of a search.
	 *
	 * <p>A node without this name would search for the words as they were
	 * typed while its peers also search for what the rules make of them, which
	 * is a different set of documents rather than a different order of them.
	 */
	public static final String QUERY_SYNONYMS = "query_synonyms";

	/**
	 * The object carries words that are matched as they are spelled.
	 *
	 * <p>A node without this name would forgive mistakes in a word its peers
	 * look up as it stands, which is a different set of documents rather than a
	 * different order of them.
	 */
	public static final String TYPO_EXCLUSIONS = "typo_exclusions";

	private static final ImmutableSet<String> SUPPORTED = Sets.immutable.of(
		RANKING,
		QUERY_SYNONYMS,
		TYPO_EXCLUSIONS
	);

	private SearchSettingsFeatures() {
	}

	/**
	 * Get the features a stored object uses, as this build understands it.
	 *
	 * @param settings
	 * @return
	 *   the names, sorted so that the same object always produces the same
	 *   list
	 */
	public static ListIterable<String> requiredBy(SearchSettingsStore settings) {
		var features = Sets.mutable.<String>empty();

		if(settings.hasRanking()) {
			features.add(RANKING);
		}

		if(!settings.getSynonymsMap().isEmpty()) {
			features.add(QUERY_SYNONYMS);
		}

		if(!settings.getTypoExclusionsMap().isEmpty()) {
			features.add(TYPO_EXCLUSIONS);
		}

		return features.toSortedList();
	}

	/**
	 * Get a settings object with its required features filled in, ready to be
	 * stored.
	 *
	 * @param settings
	 * @return
	 */
	public static SearchSettingsStore describe(SearchSettingsStore settings) {
		return settings.toBuilder()
			.clearRequiredFeatures()
			.addAllRequiredFeatures(requiredBy(settings).toList())
			.build();
	}

	/**
	 * Get the features a stored object asks for that this build does not have.
	 *
	 * @param settings
	 * @return
	 *   the names, empty when the object can be honoured here
	 */
	public static SetIterable<String> unsupportedIn(SearchSettingsStore settings) {
		var unsupported = Sets.mutable.<String>empty();

		for(var feature : settings.getRequiredFeaturesList()) {
			if(!SUPPORTED.contains(feature)) {
				unsupported.add(feature);
			}
		}

		return unsupported;
	}

	/**
	 * Get the names this build supports.
	 */
	public static ListIterable<String> supported() {
		return Lists.immutable.ofAll(SUPPORTED).toSortedList().toImmutable();
	}
}
