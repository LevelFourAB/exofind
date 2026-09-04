package se.l4.exofind.engine.query.matchers;

/**
 * Match text that a person typed, analyzed the same way the field was.
 *
 * This is the only matcher that scores, and the only one that depends on how a
 * field is analyzed - which is why a type that has nothing to analyze rejects
 * it.
 *
 * @param text
 *   what was typed
 * @param match
 *   how the words in it are combined
 * @param prefix
 *   how the word that is still being typed is treated
 * @param typos
 *   whether words may contain typing mistakes
 * @param slop
 *   how far the words of a phrase may be moved apart, {@code 0} for words
 *   next to each other
 * @param relax
 *   what may be let go of rather than find nothing
 * @param interpret
 *   whether parts of the text are read as filters on the fields of the index
 */
public record TextMatcher(
	String text,
	Match match,
	Prefix prefix,
	Typos typos,
	int slop,
	Relax relax,
	Interpret interpret
) implements Matcher {
	/**
	 * How the words of the text are combined.
	 */
	public enum Match {
		/**
		 * Every word has to be found. Narrows as more is typed, which is what
		 * a search box is expected to do.
		 */
		ALL,

		/**
		 * Any one word is enough. Widens as more is typed, useful when
		 * something is better than nothing.
		 */
		ANY,

		/**
		 * Every word has to be found, in the order typed and next to each
		 * other - what quoting means in a search box. {@code slop} lets other
		 * words sit between them; the order typed is kept whatever it is set
		 * to. The last word may still be half typed, per {@link Prefix};
		 * typing mistakes are never forgiven, whatever the field declares,
		 * because a phrase is asked for when the words are known exactly.
		 */
		PHRASE,

		/**
		 * Read the text the way people write in a search box, and combine what
		 * is left as {@link #ALL}. Quoting asks for a phrase and a leading
		 * minus leaves something out; see {@link UserText} for the whole of
		 * what is understood.
		 *
		 * Nothing a person can type is an error here - punctuation that means
		 * nothing is part of the word it sits in, and a quote nobody closed
		 * runs to the end of the text. This is the mode a search box is wired
		 * to; the others are for a caller that knows what it is asking.
		 */
		USER
	}

	/**
	 * How the last word of the text is treated.
	 */
	public enum Prefix {
		/**
		 * Let the last word match anything it starts. Someone who has typed
		 * {@code spr} is halfway through a word, and treating it as a whole one
		 * would leave them looking at nothing until they finish it.
		 */
		LAST_TOKEN,

		/**
		 * Take every word as complete.
		 */
		OFF
	}

	/**
	 * Whether the words of the text may contain typing mistakes.
	 *
	 * How many mistakes a word may contain, and how long it has to be before
	 * any are allowed, is declared on the field being searched - a query can
	 * ask for words to be taken as typed, but never allow typos on a field
	 * whose definition did not.
	 */
	public enum Typos {
		/**
		 * Allow what the definition of the field allows, which is nothing
		 * unless it declares typo tolerance.
		 */
		AUTO,

		/**
		 * Take every word as typed, whatever the field allows. This is what a
		 * quoted or exact search turns into.
		 */
		OFF
	}

	/**
	 * What the words of the text may be let go of rather than find nothing.
	 *
	 * Long text finds nothing more often the more of it there is - one word a
	 * document happens not to hold empties the page, and nothing found is the
	 * one outcome the person who typed it can do nothing about. Letting a word
	 * go is what turns that into results, and only ever happens when the search
	 * as a whole found nothing, so a search that worked is answered exactly as
	 * it was asked.
	 *
	 * Only loose words are ever let go. A quoted phrase and a word marked to be
	 * left out were asked for deliberately, and {@link Match#PHRASE} is that
	 * same deliberate ask written as its own clause, so neither is touched. A
	 * search matching {@link Match#ANY} is already as wide as it goes and has
	 * nothing to let go of.
	 *
	 * Whatever is let go is answered alongside the results, because a page that
	 * quietly ignored half of what was typed is worse than an empty one - the
	 * person reading it believes it.
	 */
	public enum Relax {
		/**
		 * Nothing found is the answer.
		 */
		OFF,

		/**
		 * Let go of words nothing in the index holds. Keeping such a word can
		 * only ever find nothing, so dropping it never loses a result - it only
		 * turns an empty page into the results the rest of the words have.
		 */
		UNMATCHED,

		/**
		 * Those, and then the word that says the least about what was wanted -
		 * the one the most documents hold - until something is found or a
		 * single word is left. Widens the search to text that was never typed,
		 * which is worth it where an empty page is the worse answer.
		 */
		WORDS
	}

	/**
	 * Whether parts of the text are read as filters on the fields of the
	 * index, rather than as words to look for.
	 *
	 * Only text in {@link Match#USER} mode is read this way: it is the text
	 * of a search box, where {@code shoes under 100 kr} means a price as much
	 * as it means words. A number next to a comparative word of the search
	 * locale, or next to the unit a number field declares, is read as a range
	 * on that field. The words are still searched as text besides, so a
	 * reading never hides a document that holds them as words; it only adds
	 * the documents the filter finds. What was read is answered alongside the
	 * results, see {@link se.l4.exofind.engine.query.SearchResult.Interpreted}.
	 */
	public enum Interpret {
		/**
		 * Read whatever the index declares can be read - the units of its
		 * number fields.
		 */
		AUTO,

		/**
		 * Take every word as text. What a search box sends when a person
		 * has turned a reading off.
		 */
		OFF
	}

	public TextMatcher {
		if(match == null) {
			match = Match.ALL;
		}

		if(interpret == null) {
			interpret = Interpret.AUTO;
		}

		if(prefix == null) {
			prefix = Prefix.LAST_TOKEN;
		}

		if(typos == null) {
			typos = Typos.AUTO;
		}

		if(relax == null) {
			/*
			 * Dropping a word nothing holds can only turn an empty page into
			 * results, so every search gets that much without asking. Widening
			 * beyond it searches for text nobody typed, which is the caller's
			 * to ask for.
			 */
			relax = Relax.UNMATCHED;
		}

		if(slop < 0) {
			throw new IllegalArgumentException(
				"The words of a phrase can not be moved a negative distance"
			);
		}
	}

	/**
	 * Match the given text with the words next to each other where that means
	 * anything.
	 *
	 * @param text
	 * @param match
	 * @param prefix
	 * @param typos
	 * @param slop
	 */
	public TextMatcher(String text, Match match, Prefix prefix, Typos typos, int slop) {
		this(text, match, prefix, typos, slop, null, null);
	}

	/**
	 * Match the given text with the words next to each other where that means
	 * anything, reading whatever the index declares can be read.
	 *
	 * @param text
	 * @param match
	 * @param prefix
	 * @param typos
	 * @param slop
	 * @param relax
	 */
	public TextMatcher(
		String text,
		Match match,
		Prefix prefix,
		Typos typos,
		int slop,
		Relax relax
	) {
		this(text, match, prefix, typos, slop, relax, null);
	}

	/**
	 * Match the given text with the words next to each other where that means
	 * anything.
	 *
	 * @param text
	 * @param match
	 * @param prefix
	 * @param typos
	 */
	public TextMatcher(String text, Match match, Prefix prefix, Typos typos) {
		this(text, match, prefix, typos, 0);
	}

	/**
	 * Match the given text with the defaults - every word has to be found and
	 * the last one may still be half typed.
	 *
	 * @param text
	 * @return
	 */
	public static TextMatcher of(String text) {
		return new TextMatcher(text, Match.ALL, Prefix.LAST_TOKEN, Typos.AUTO, 0);
	}

	/**
	 * Get this matcher looking for the given text instead.
	 *
	 * @param text
	 * @return
	 */
	public TextMatcher withText(String text) {
		return new TextMatcher(text, match, prefix, typos, slop, relax, interpret);
	}

	/**
	 * Get this matcher with the words combined in the given way.
	 *
	 * @param match
	 * @return
	 */
	public TextMatcher withMatch(Match match) {
		return new TextMatcher(text, match, prefix, typos, slop, relax, interpret);
	}

	/**
	 * Get this matcher with the last word treated in the given way.
	 *
	 * @param prefix
	 * @return
	 */
	public TextMatcher withPrefix(Prefix prefix) {
		return new TextMatcher(text, match, prefix, typos, slop, relax, interpret);
	}

	/**
	 * Get this matcher allowing typing mistakes in the given way.
	 *
	 * @param typos
	 * @return
	 */
	public TextMatcher withTypos(Typos typos) {
		return new TextMatcher(text, match, prefix, typos, slop, relax, interpret);
	}

	/**
	 * Get this matcher letting go of the given amount rather than finding
	 * nothing.
	 *
	 * @param relax
	 * @return
	 */
	public TextMatcher withRelax(Relax relax) {
		return new TextMatcher(text, match, prefix, typos, slop, relax, interpret);
	}

	/**
	 * Get this matcher letting the words of a phrase sit the given distance
	 * apart.
	 *
	 * Only a phrase has a distance to loosen - it is what {@link Match#PHRASE}
	 * asks for, and what a quoted part of a {@link Match#USER} text asks for.
	 * Under {@link Match#ALL} and {@link Match#ANY} the words are looked for
	 * wherever they sit, so there is nothing here to say.
	 *
	 * @param slop
	 *   how many other words may sit between the words of the phrase, counted
	 *   across the phrase as a whole
	 * @return
	 */
	public TextMatcher withSlop(int slop) {
		return new TextMatcher(text, match, prefix, typos, slop, relax, interpret);
	}

	/**
	 * Get this matcher reading parts of its text as filters in the given way.
	 *
	 * @param interpret
	 * @return
	 */
	public TextMatcher withInterpret(Interpret interpret) {
		return new TextMatcher(text, match, prefix, typos, slop, relax, interpret);
	}

	@Override
	public String id() {
		return "text";
	}
}
