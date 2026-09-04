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
 *   field of the index that declares a unit. See {@link Target}
 */
public record TextQuery(
	TextMatcher matcher,
	ImmutableMap<String, Float> fields,
	Combine combine,
	ImmutableList<Target> targets
) implements Query {
	/**
	 * A field a reading of the text may be a filter on.
	 *
	 * Naming targets is how a search says which of the fields declaring a
	 * unit a number typed in the box is about, where the index alone can not
	 * tell - a product priced on many lists holds the same unit on every one
	 * of them, and only the caller knows which list the person searching is
	 * on.
	 *
	 * A target inside a {@code nested} list is read against one value at a
	 * time, and {@code when} says which value: the clauses that have to hold
	 * in the same value as the number, such as the list id next to the
	 * amount. A target outside a list may carry {@code when} as well, and the
	 * clauses then have to hold for the document.
	 *
	 * The {@code fallback} targets are read instead, in order, for a document
	 * that holds no value on the target - a product with no price on the
	 * customer's list is read on the store's list. Every target of a chain
	 * has to be in one unit, or a number would mean one currency on one
	 * product and another on the next.
	 *
	 * @param field
	 *   name of the field, as it is called in the definition of the index
	 * @param when
	 *   the clauses that have to hold where the number is read, empty for
	 *   none
	 * @param fallback
	 *   the targets read instead where the document holds no value on this
	 *   one, empty for none
	 */
	public record Target(
		String field,
		ImmutableList<Query> when,
		ImmutableList<Target> fallback
	) {
		public Target {
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
		public static Target of(String field) {
			return new Target(field, null, null);
		}

		/**
		 * Get this target read only where the given clauses hold.
		 *
		 * @param when
		 * @return
		 */
		public Target withWhen(Query... when) {
			return new Target(field, Lists.immutable.of(when), fallback);
		}

		/**
		 * Get this target with the given targets read instead where a
		 * document holds no value on it.
		 *
		 * @param fallback
		 * @return
		 */
		public Target withFallback(Target... fallback) {
			return new Target(field, when, Lists.immutable.of(fallback));
		}
	}

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
	public TextQuery withTargets(Target... targets) {
		return new TextQuery(matcher, fields, combine, Lists.immutable.of(targets));
	}

	/**
	 * Get this clause with a reading of its text allowed to be a filter on
	 * the given targets only.
	 *
	 * @param targets
	 * @return
	 */
	public TextQuery withTargets(ImmutableList<Target> targets) {
		return new TextQuery(matcher, fields, combine, targets);
	}
}
