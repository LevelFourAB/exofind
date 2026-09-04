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

	/**
	 * The object names fields whose values a search reads out of the typed
	 * text, as filters on the field.
	 *
	 * <p>A node without this name would search for a colour or a brand as
	 * words in the text while its peers also find the documents holding it as
	 * a value, which is a different set of documents rather than a different
	 * order of them.
	 */
	public static final String INTERPRET_VALUES = "interpret_values";

	/**
	 * The object declares values of fields, with an order and labels per
	 * locale.
	 *
	 * <p>A node without this name would answer a facet ordered by declaration
	 * in count order and without labels, and would search a label typed into
	 * the box as a word while its peers find the value it stands for.
	 */
	public static final String DECLARED_VALUES = "declared_values";

	/**
	 * The object names fields whose values are suggested while a search is
	 * typed.
	 *
	 * <p>A node without this name would answer no suggestions for an index
	 * whose peers suggest the values of the named fields, so a search box
	 * would show suggestions from some nodes and none from others.
	 */
	public static final String SUGGEST_VALUES = "suggest_values";

	private static final ImmutableSet<String> SUPPORTED = Sets.immutable.of(
		RANKING,
		QUERY_SYNONYMS,
		TYPO_EXCLUSIONS,
		INTERPRET_VALUES,
		DECLARED_VALUES,
		SUGGEST_VALUES
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

		for(var field : settings.getFieldsMap().values()) {
			if(field.hasInterpret()) {
				features.add(INTERPRET_VALUES);
			}

			if(field.getValuesCount() > 0) {
				features.add(DECLARED_VALUES);
			}

			if(field.hasSuggest()) {
				features.add(SUGGEST_VALUES);
			}
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
