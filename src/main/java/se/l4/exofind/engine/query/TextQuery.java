package se.l4.exofind.engine.query;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.map.ImmutableMap;

import se.l4.exofind.engine.query.matchers.TextMatcher;

/**
 * Match text that someone typed, against several fields at once.
 *
 * This is the clause a search box turns into. It is the same
 * {@link TextMatcher} a {@link FieldQuery} would carry, asked of every field
 * named here. {@code combine} decides what the matcher's {@code match} has to
 * hold within: each word found in whichever field holds it, or every word
 * found in a single field - see {@link Combine}.
 *
 * Naming no fields searches every field of the index that can be matched on,
 * which is what makes a search work without being told about the schema.
 *
 * @param matcher
 *   the text to look for and how to look for it
 * @param fields
 *   the fields to look in and how much each of them counts, empty for every
 *   field that can be matched on. A field mapped to {@code null} counts as
 *   much as its definition says
 * @param combine
 *   what a match is complete within, {@code null} for {@link Combine#TERM}
 * @param targets
 *   the fields a reading of the text may be a filter on, empty for every
 *   field of the index that declares a unit. Naming targets is how a search
 *   says which of the fields declaring a unit a number typed in the box is
 *   about, where the index alone can not tell. Every field of one target's
 *   chain has to be in one unit, or a number would mean one currency on one
 *   product and another on the next. See {@link ValueTarget}
 */
public record TextQuery(
	TextMatcher matcher,
	ImmutableMap<String, Float> fields,
	Combine combine,
	ImmutableList<ValueTarget> targets
) implements Query {
	/**
	 * What a match is complete within when text is searched across several
	 * fields. Makes no difference to a search of one field, or to a
	 * {@link TextMatcher.Match#PHRASE phrase}, which holds within one field
	 * either way.
	 */
	public enum Combine {
		/**
		 * Each word counts in whichever field holds it best, and the words of
		 * a document may sit in different fields - {@code red nike shoes}
		 * finds a document whose color, brand and name each hold one word. A
		 * document is ranked by its words added up, each counted in the field
		 * that matched it best.
		 */
		TERM,

		/**
		 * A single field has to satisfy the matcher on its own, and a document
		 * is ranked by the field it matched best - so a title hit counts for
		 * more than the same words buried in a description when the title is
		 * weighted higher. Words spread over several fields do not match.
		 */
		FIELD
	}

	public TextQuery {
		if(fields == null) {
			fields = Maps.immutable.empty();
		}

		if(combine == null) {
			combine = Combine.TERM;
		}

		if(targets == null) {
			targets = Lists.immutable.empty();
		}
	}

	public TextQuery(TextMatcher matcher, ImmutableMap<String, Float> fields) {
		this(matcher, fields, null, null);
	}

	public TextQuery(TextMatcher matcher, ImmutableMap<String, Float> fields, Combine combine) {
		this(matcher, fields, combine, null);
	}

	@Override
	public String type() {
		return "text";
	}

	@Override
	public boolean scores() {
		return true;
	}

	/**
	 * Get the text being searched for.
	 *
	 * @return
	 */
	public String text() {
		return matcher.text();
	}

	/**
	 * Search the given text in every field that can be matched on.
	 *
	 * @param text
	 * @return
	 */
	public static TextQuery of(String text) {
		return new TextQuery(TextMatcher.of(text), Maps.immutable.empty());
	}

	/**
	 * Search in every field that can be matched on, matching in the given way.
	 *
	 * @param matcher
	 * @return
	 */
	public static TextQuery of(TextMatcher matcher) {
		return new TextQuery(matcher, Maps.immutable.empty());
	}

	/**
	 * Get this clause with a field added to the ones being searched, counting
	 * as much as the definition of the index says it does. Naming fields picks
	 * which of them a search covers without discarding the ranking the index
	 * declared for itself.
	 *
	 * @param name
	 * @return
	 */
	public TextQuery withField(String name) {
		return new TextQuery(matcher, fields.newWithKeyValue(name, null), combine, targets);
	}

	/**
	 * Get this clause with a field added to the ones being searched, counting
	 * the given amount instead of whatever the definition of the index says.
	 *
	 * @param name
	 * @param weight
	 *   how much a hit in this field counts, relative to the other fields
	 * @return
	 */
	public TextQuery withField(String name, float weight) {
		return new TextQuery(matcher, fields.newWithKeyValue(name, weight), combine, targets);
	}

	/**
	 * Get this clause searching the given fields instead.
	 *
	 * @param fields
	 * @return
	 */
	public TextQuery withFields(ImmutableMap<String, Float> fields) {
		return new TextQuery(matcher, fields, combine, targets);
	}

	/**
	 * Get this clause matching the text in the given way.
	 *
	 * @param matcher
	 * @return
	 */
	public TextQuery withMatcher(TextMatcher matcher) {
		return new TextQuery(matcher, fields, combine, targets);
	}

	/**
	 * Get this clause with matches complete within the given scope.
	 *
	 * @param combine
	 * @return
	 */
	public TextQuery withCombine(Combine combine) {
		return new TextQuery(matcher, fields, combine, targets);
	}

	/**
	 * Get this clause with a reading of its text allowed to be a filter on
	 * the given targets only.
	 *
	 * @param targets
	 * @return
	 */
	public TextQuery withTargets(ValueTarget... targets) {
		return new TextQuery(matcher, fields, combine, Lists.immutable.of(targets));
	}

	/**
	 * Get this clause with a reading of its text allowed to be a filter on
	 * the given targets only.
	 *
	 * @param targets
	 * @return
	 */
	public TextQuery withTargets(ImmutableList<ValueTarget> targets) {
		return new TextQuery(matcher, fields, combine, targets);
	}
}
