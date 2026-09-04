package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * What a {@link SuggestRequest} found: the suggestions in the order to show
 * them, the most common first, with every suggestion the text starts before
 * the ones found a mistake away.
 *
 * @param suggestions
 *   the suggestions, at most as many as the request's limit
 */
public record SuggestResult(ImmutableList<Suggestion> suggestions) {
	private static final SuggestResult EMPTY = new SuggestResult(Lists.immutable.empty());

	public SuggestResult {
		if(suggestions == null) {
			suggestions = Lists.immutable.empty();
		}
	}

	/**
	 * A result with nothing suggested.
	 *
	 * @return
	 */
	public static SuggestResult empty() {
		return EMPTY;
	}

	/**
	 * One thing to search for.
	 *
	 * @param text
	 *   what to show and to search for: the label of the value in the
	 *   locale of the request where the settings declare one, or the value
	 *   itself
	 * @param typed
	 *   how many characters at the start of {@code text} the typed text
	 *   covers, for marking the part a person has typed apart from the part
	 *   that completes it. Zero when the suggestion was found a mistake away
	 * @param corrected
	 *   whether the suggestion was found one mistake away from the text
	 *   instead of starting with it
	 * @param field
	 *   the field the value is held by, named as a facet names it
	 * @param value
	 *   the value as the field stores it, in the shape the type of the
	 *   field returns it in, which a filter on the field takes
	 * @param label
	 *   the label the search settings declare for the value in the locale of
	 *   the request, or {@code null} where they declare none
	 * @param count
	 *   how many documents hold the value under the filters of the request
	 */
	public record Suggestion(
		String text,
		int typed,
		boolean corrected,
		String field,
		Object value,
		String label,
		long count
	) {
	}
}
