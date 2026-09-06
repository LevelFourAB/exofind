package se.l4.exofind.engine.index.locales;

/**
 * The light stemmers of the Slavic languages Lucene ships none for, one
 * {@link SuffixStemmer} per language.
 *
 * Slovak and Slovenian inflect a noun or an adjective through a case ending
 * on the stem, and a verb through a tense or person ending, in the way Czech
 * does. The tables follow the shape of Lucene's Czech light stemmer: the
 * case endings are cut, then the verb endings, and what remains is the same
 * string for the regular forms of a word. A form that changes the stem
 * itself - the Slovak {@code žien} for {@code žena}, the Slovenian
 * {@code psa} for {@code pes} - is left as written and matches only itself.
 *
 * Two steps rather than one keep a verb ending from being missed under a
 * case-like vowel: {@code robili} loses its {@code i} in the first step and
 * its {@code il} in the second, so it meets {@code robiť}. The second step
 * also runs on a bare noun that happens to end like a verb, which is what
 * keeps {@code program} and {@code programu} together: both end at the same
 * string, whatever that string is.
 *
 * A stem is never cut shorter than three characters.
 */
public final class SlavicStemmers {
	private SlavicStemmers() {
	}

	/**
	 * Slovak. The first step cuts the case endings of nouns and adjectives,
	 * the second the endings of the verb: person, tense and the infinitive.
	 */
	public static final SuffixStemmer SLOVAK = SuffixStemmer.create()
		.step(3,
			// Nouns, the plural and the oblique cases
			"ovia", "iach", "ami", "ach", "ách", "och", "ovi", "ov", "om", "mi", "ou",
			// Adjectives
			"ieho", "iemu", "ého", "ému", "ých", "ými", "ích", "ími", "ej", "ým", "ím", "ich",
			// The vowel a case or a gender ends in
			"ie", "ia", "iu", "a", "e", "i", "o", "u", "y", "á", "é", "í", "ú", "ý"
		)
		.step(3,
			"ovať", "oval", "ujem", "uješ", "uj", "aj",
			"iem", "ieš", "ám", "áš", "am", "aš", "ím", "íš", "em", "eš",
			"iť", "ať", "uť", "eť", "il", "al", "ul", "el"
		)
		.build();

	/**
	 * Slovenian. The first step cuts the case endings of nouns and
	 * adjectives, with the {@code ov} a short masculine noun extends its
	 * plural by - {@code grad}, {@code gradovi} - and the dual; the second
	 * the endings of the verb.
	 */
	public static final SuffixStemmer SLOVENIAN = SuffixStemmer.create()
		.step(3,
			// Nouns, the plural, the dual and the oblique cases
			"ovimi", "ovoma", "ovih", "ovim", "ovi", "ove", "ova", "ovo", "ov", "ev",
			/*
			 * The bare `mi` of the feminine i-declension is left out: it is
			 * rare, and would take the `i` plural off every noun whose stem
			 * ends in m, such as sistemi.
			 */
			"ami", "oma", "ema", "ima", "imi", "ah", "am", "ih", "im", "om", "em", "ju",
			// Adjectives
			"ega", "emu",
			// The vowel a case or a gender ends in
			"a", "e", "i", "o", "u"
		)
		.step(3,
			"aj", "ij", "ej", "at", "it", "et", "al", "il", "el",
			"am", "aš", "im", "iš", "em", "eš", "ava", "iva", "eva"
		)
		.build();
}
