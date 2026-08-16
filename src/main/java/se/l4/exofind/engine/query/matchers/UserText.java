package se.l4.exofind.engine.query.matchers;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.set.SetIterable;

/**
 * The text of a search box, taken apart into the parts a person meant by it.
 *
 * This is what {@link TextMatcher.Match#USER} reads the typed text with. Two
 * pieces of punctuation carry meaning, both of them ones people already use
 * without being told to:
 *
 * <ul>
 * <li>text between double quotes is a phrase - {@code "apple watch"} asks for
 * those words in that order
 * <li>a minus in front of a word or a quoted phrase leaves it out -
 * {@code shoes -leather}
 * </ul>
 *
 * Everything else is text. A minus inside a word ({@code e-mail}), a quote
 * inside one ({@code it"s}), a minus with nothing after it - none of them mean
 * anything here, so they stay part of what is searched for and analysis
 * decides what to do with them. A quote nobody closed runs to the end of the
 * text, because somebody halfway through typing a phrase has not made a
 * mistake yet.
 *
 * Nothing here can fail. A person typing into a search box is not writing a
 * query language, and a search that answers an error rather than results is
 * the one outcome they cannot do anything about.
 *
 * @param parts
 *   what was typed, in the order it was typed
 */
public record UserText(ImmutableList<Part> parts) {
	/**
	 * What a part of the text asks for.
	 */
	public enum Kind {
		/**
		 * A single word, taken with the words around it.
		 */
		WORD,

		/**
		 * Words that were quoted, which have to be found together.
		 */
		PHRASE
	}

	/**
	 * One part of what was typed.
	 *
	 * @param text
	 *   the text of the part, without the punctuation that marked it
	 * @param kind
	 *   what the part asks for
	 * @param exclude
	 *   if the part was marked to be left out
	 * @param open
	 *   if the text ended in the middle of this part - a word, or a quote that
	 *   was never closed. Only the last part can be open, and only an open one
	 *   can hold a word somebody is still typing
	 */
	public record Part(String text, Kind kind, boolean exclude, boolean open) {
	}

	/**
	 * Take apart what somebody typed.
	 *
	 * @param text
	 * @return
	 */
	public static UserText parse(String text) {
		var parts = Lists.mutable.<Part>empty();
		if(text == null) {
			return new UserText(parts.toImmutable());
		}

		var length = text.length();
		var i = 0;

		while(i < length) {
			if(Character.isWhitespace(text.charAt(i))) {
				i++;
				continue;
			}

			/*
			 * A minus only excludes when something follows it to exclude. On
			 * its own it is a character somebody typed, like any other.
			 */
			var exclude = false;
			if(text.charAt(i) == '-'
				&& i + 1 < length
				&& !Character.isWhitespace(text.charAt(i + 1))) {
				exclude = true;
				i++;
			}

			if(text.charAt(i) == '"') {
				i++;

				var start = i;
				while(i < length && text.charAt(i) != '"') {
					i++;
				}

				var quoted = text.substring(start, i);
				var open = i == length;
				if(!open) {
					i++;
				}

				// Quotes around nothing asked for nothing
				if(!quoted.isBlank()) {
					parts.add(new Part(quoted, Kind.PHRASE, exclude, open));
				}

				continue;
			}

			var start = i;
			while(i < length && !Character.isWhitespace(text.charAt(i))) {
				i++;
			}

			parts.add(new Part(text.substring(start, i), Kind.WORD, exclude, true));
		}

		return new UserText(parts.toImmutable());
	}

	/**
	 * Get if nothing was typed that can be searched for.
	 *
	 * @return
	 */
	public boolean isEmpty() {
		return parts.isEmpty();
	}

	/**
	 * Get the loose words of the text, in the order they were typed and each of
	 * them once.
	 *
	 * These are the parts that can be let go of when the search finds nothing:
	 * a quoted phrase and a part marked to be left out were asked for
	 * deliberately, so neither is here.
	 *
	 * @return
	 */
	public ImmutableList<String> words() {
		return parts.select(part -> part.kind() == Kind.WORD && !part.exclude())
			.collect(Part::text)
			.distinct();
	}

	/**
	 * Get this text without the given loose words, leaving everything else as
	 * it was typed.
	 *
	 * A word that was typed twice goes both times - the two ask for the same
	 * term, so leaving one behind would drop nothing.
	 *
	 * @param words
	 *   the words to leave out, matched against {@link #words()}
	 * @return
	 */
	public UserText without(SetIterable<String> words) {
		return new UserText(
			parts.reject(part -> part.kind() == Kind.WORD
				&& !part.exclude()
				&& words.contains(part.text()))
		);
	}

	/**
	 * Get what was typed, as text that reads back to these same parts.
	 *
	 * Only the punctuation that carried meaning is written back, so the text is
	 * the same search rather than the same characters - runs of whitespace
	 * become single spaces, and a quote nobody closed is left unclosed so that
	 * the phrase inside it stays one somebody is still typing.
	 *
	 * @return
	 */
	public String text() {
		var result = new StringBuilder();

		for(var part : parts) {
			if(result.length() > 0) {
				result.append(' ');
			}

			if(part.exclude()) {
				result.append('-');
			}

			if(part.kind() == Kind.PHRASE) {
				result.append('"').append(part.text());

				if(!part.open()) {
					result.append('"');
				}
			} else {
				result.append(part.text());
			}
		}

		return result.toString();
	}

	/**
	 * Get what has to be found, as the matchers it stands for: the loose words
	 * as one matcher, so that they keep counting together, and every quoted
	 * phrase as its own.
	 *
	 * The last word of the text is the one that may still be half typed, so
	 * the {@link TextMatcher.Prefix} of the given matcher only reaches the
	 * part the text ended in the middle of - a word after a closed quote is
	 * finished, and so is the quote before it.
	 *
	 * @param base
	 *   the matcher the options of the search were given on
	 * @return
	 */
	public ImmutableList<TextMatcher> required(TextMatcher base) {
		var result = Lists.mutable.<TextMatcher>empty();
		var last = parts.size() - 1;

		var words = new StringBuilder();
		var wordsEndTheText = false;

		for(var i = 0; i <= last; i++) {
			var part = parts.get(i);
			if(part.exclude() || part.kind() != Kind.WORD) {
				continue;
			}

			if(words.length() > 0) {
				words.append(' ');
			}

			words.append(part.text());
			wordsEndTheText = i == last;
		}

		if(words.length() > 0) {
			result.add(
				base.withText(words.toString())
					.withMatch(TextMatcher.Match.ALL)
					.withPrefix(wordsEndTheText ? base.prefix() : TextMatcher.Prefix.OFF)
					.withSlop(0)
			);
		}

		for(var i = 0; i <= last; i++) {
			var part = parts.get(i);
			if(part.exclude() || part.kind() != Kind.PHRASE) {
				continue;
			}

			result.add(
				base.withText(part.text())
					.withMatch(TextMatcher.Match.PHRASE)
					.withPrefix(
						i == last && part.open()
							? base.prefix()
							: TextMatcher.Prefix.OFF
					)
			);
		}

		return result.toImmutable();
	}

	/**
	 * Get what has to be left out, as the matchers it stands for, one per part
	 * that was marked - two exclusions are two things to leave out rather than
	 * one condition that has to hold twice.
	 *
	 * An exclusion is taken exactly as typed: it is never read as a word still
	 * being typed, and never widened to the words near it. Both would throw
	 * documents away over a word nobody wrote, and what is thrown away is the
	 * one thing a search cannot show.
	 *
	 * @param base
	 *   the matcher the options of the search were given on
	 * @return
	 */
	public ImmutableList<TextMatcher> excluded(TextMatcher base) {
		return parts.select(Part::exclude)
			.collect(part -> base.withText(part.text())
				.withMatch(
					part.kind() == Kind.PHRASE
						? TextMatcher.Match.PHRASE
						: TextMatcher.Match.ALL
				)
				.withPrefix(TextMatcher.Prefix.OFF)
				.withTypos(TextMatcher.Typos.OFF)
				.withSlop(part.kind() == Kind.PHRASE ? base.slop() : 0));
	}
}
