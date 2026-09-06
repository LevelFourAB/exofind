package se.l4.exofind.engine.index.locales;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

/**
 * Pins what the light stemmers of the Indic languages cut, so that a change
 * to a suffix table that stops the forms of a word from meeting fails here
 * rather than in search results.
 *
 * Each test hands the stemmer the forms of one word and asserts that they end
 * at one string. The string itself is the stemmer's business; what matters
 * is that a search for any form finds the others.
 */
public class IndicStemmersTest {
	private static String stem(SuffixStemmer stemmer, String word) {
		var buffer = word.toCharArray();
		return new String(buffer, 0, stemmer.stem(buffer, buffer.length));
	}

	private static void assertMeet(SuffixStemmer stemmer, String... forms) {
		var first = stem(stemmer, forms[0]);
		for(var form : forms) {
			assertThat("`" + form + "` against `" + forms[0] + "`", stem(stemmer, form), is(first));
		}
	}

	/**
	 * A word shorter than the minimum stem is left as written, so that a
	 * short word ending in what looks like an ending keeps its letters.
	 */
	@Test
	public void testAShortWordIsLeftAlone() {
		var stemmer = SuffixStemmer.create()
			.step(2, "ab")
			.build();

		assertThat(stem(stemmer, "ab"), is("ab"));
		assertThat(stem(stemmer, "xab"), is("xab"));
		assertThat(stem(stemmer, "xyab"), is("xy"));
	}

	/**
	 * The longest ending a word carries is the one cut, whatever order the
	 * table lists them in.
	 */
	@Test
	public void testTheLongestEndingIsCut() {
		var stemmer = SuffixStemmer.create()
			.step(1, "b", "ab")
			.build();

		assertThat(stem(stemmer, "xxab"), is("xx"));
	}

	/**
	 * Each step cuts at most one ending, and the next step works on what is
	 * left.
	 */
	@Test
	public void testStepsCutInTurn() {
		var stemmer = SuffixStemmer.create()
			.step(1, "b", "c")
			.step(1, "a")
			.build();

		assertThat(stem(stemmer, "xab"), is("x"));
		assertThat(stem(stemmer, "xbc"), is("xb"));
	}

	/**
	 * Marathi: the house, in the house, to the house, in the houses, houses.
	 */
	@Test
	public void testMarathiNounCases() {
		assertMeet(IndicStemmers.MARATHI, "घर", "घरात", "घराला", "घरांमध्ये", "घरे", "घराचा");
	}

	/**
	 * Marathi: a masculine noun in -ā takes -yā before a postposition, and
	 * the boy, the boys, to the boy and to the boys all meet.
	 */
	@Test
	public void testMarathiObliqueStem() {
		assertMeet(IndicStemmers.MARATHI, "मुलगा", "मुलगे", "मुलग्याला", "मुलग्यांना");
	}

	/**
	 * Marathi: the present tense forms of a verb, and its conjunctive.
	 */
	@Test
	public void testMarathiVerbForms() {
		assertMeet(IndicStemmers.MARATHI, "करतो", "करते", "करतात", "करून", "करणार");
	}

	/**
	 * Gujarati: the house, in the house, from the house, houses, in the houses.
	 */
	@Test
	public void testGujaratiNounCases() {
		assertMeet(IndicStemmers.GUJARATI, "ઘર", "ઘરમાં", "ઘરથી", "ઘરો", "ઘરોમાં", "ઘરનું");
	}

	/**
	 * Gujarati: a masculine noun in -o, its oblique, its plural and the
	 * plural with a postposition.
	 */
	@Test
	public void testGujaratiGenderVowel() {
		assertMeet(IndicStemmers.GUJARATI, "છોકરો", "છોકરા", "છોકરાઓ", "છોકરાને", "છોકરાઓને", "છોકરાએ");
	}

	/**
	 * Punjabi: the boy, the boy before a postposition, and the boys before one.
	 */
	@Test
	public void testPunjabiObliqueAndPlural() {
		assertMeet(IndicStemmers.PUNJABI, "ਮੁੰਡਾ", "ਮੁੰਡੇ", "ਮੁੰਡਿਆਂ");
		assertMeet(IndicStemmers.PUNJABI, "ਕੁੜੀ", "ਕੁੜੀਆਂ");
		assertMeet(IndicStemmers.PUNJABI, "ਘਰ", "ਘਰਾਂ", "ਘਰੋਂ");
	}

	@Test
	public void testPunjabiVerbForms() {
		assertMeet(IndicStemmers.PUNJABI, "ਕਰਦਾ", "ਕਰਦੀ", "ਕਰਦੇ", "ਕਰਦੀਆਂ");
	}

	/**
	 * Kannada: a noun ending in -a joins its case with d, one ending in -e
	 * with y, and both take gaḷu in the plural.
	 */
	@Test
	public void testKannadaNounCases() {
		assertMeet(IndicStemmers.KANNADA, "ಮರ", "ಮರದ", "ಮರದಲ್ಲಿ", "ಮರಕ್ಕೆ", "ಮರವನ್ನು", "ಮರಗಳು", "ಮರಗಳಲ್ಲಿ");
		assertMeet(IndicStemmers.KANNADA, "ಮನೆ", "ಮನೆಯ", "ಮನೆಯಲ್ಲಿ", "ಮನೆಗೆ", "ಮನೆಯನ್ನು", "ಮನೆಗಳು");
	}

	/**
	 * Kannada: a person noun joins its case with n and its plural with r, and
	 * a noun in -u drops the vowel before a case ending.
	 */
	@Test
	public void testKannadaJoiningConsonants() {
		assertMeet(IndicStemmers.KANNADA, "ಹುಡುಗ", "ಹುಡುಗನು", "ಹುಡುಗನ", "ಹುಡುಗನಿಗೆ", "ಹುಡುಗನನ್ನು", "ಹುಡುಗರು", "ಹುಡುಗರಿಗೆ");
		assertMeet(IndicStemmers.KANNADA, "ಕಣ್ಣು", "ಕಣ್ಣಿನ", "ಕಣ್ಣಿಗೆ", "ಕಣ್ಣುಗಳು");
	}

	@Test
	public void testKannadaVerbForms() {
		assertMeet(IndicStemmers.KANNADA, "ಮಾಡುತ್ತಾರೆ", "ಮಾಡುತ್ತದೆ", "ಮಾಡಿದರು", "ಮಾಡಿದ", "ಮಾಡಲು");
	}

	/**
	 * Malayalam: a noun in -am changes it to -att before a case ending and
	 * to -aṅṅaḷ in the plural.
	 */
	@Test
	public void testMalayalamNounInAnusvara() {
		assertMeet(IndicStemmers.MALAYALAM, "മരം", "മരത്തിൽ", "മരത്തിന്റെ", "മരങ്ങൾ", "മരങ്ങളിൽ", "മരങ്ങളുടെ");
	}

	/**
	 * Malayalam: other nouns take kaḷ in the plural and the case straight on.
	 */
	@Test
	public void testMalayalamNounCases() {
		assertMeet(IndicStemmers.MALAYALAM, "കുട്ടി", "കുട്ടികൾ", "കുട്ടിയുടെ", "കുട്ടിക്ക്", "കുട്ടികളുടെ", "കുട്ടിയെ");
		assertMeet(IndicStemmers.MALAYALAM, "വീട്", "വീടുകൾ", "വീടിന്റെ", "വീടിന്");
	}

	@Test
	public void testMalayalamVerbForms() {
		assertMeet(IndicStemmers.MALAYALAM, "പഠിക്കുന്നു", "പഠിക്കുന്ന", "പഠിക്കുക", "പഠിക്കാൻ");
	}

	/**
	 * Odia: the house, in the house, to the house, of the house, the house
	 * with the definite marker, and the houses.
	 */
	@Test
	public void testOdiaNounCases() {
		assertMeet(IndicStemmers.ODIA, "ଘର", "ଘରରେ", "ଘରକୁ", "ଘରର", "ଘରଟି", "ଘରଟିରେ", "ଘରଗୁଡ଼ିକ", "ଘରଗୁଡ଼ିକରେ");
	}

	/**
	 * Odia: a noun for people takes māne in the plural and the honorific ṅka
	 * before its case.
	 */
	@Test
	public void testOdiaPluralOfPeople() {
		assertMeet(IndicStemmers.ODIA, "ପିଲା", "ପିଲାମାନେ", "ପିଲାମାନଙ୍କୁ", "ପିଲାମାନଙ୍କର", "ପିଲାଙ୍କ", "ପିଲାକୁ");
	}

	@Test
	public void testOdiaVerbForms() {
		assertMeet(IndicStemmers.ODIA, "କରୁଛି", "କରିଥିଲା", "କରିବ", "କରିବାକୁ", "କରନ୍ତି");
	}

	/**
	 * Urdu: the boy, the boys, the boys before a postposition; the girl and
	 * the girls; a book, books, and books before a postposition.
	 */
	@Test
	public void testUrduNounCases() {
		assertMeet(IndicStemmers.URDU, "لڑکا", "لڑکے", "لڑکوں");
		assertMeet(IndicStemmers.URDU, "لڑکی", "لڑکیاں", "لڑکیوں");
		assertMeet(IndicStemmers.URDU, "کتاب", "کتابیں", "کتابوں");
	}

	/**
	 * Urdu: a noun in final heh loses it in its other forms, and an Arabic
	 * loan takes the Arabic plural.
	 */
	@Test
	public void testUrduFinalHehAndArabicPlural() {
		assertMeet(IndicStemmers.URDU, "بچہ", "بچے", "بچوں");
		assertMeet(IndicStemmers.URDU, "حال", "حالات");
	}

	@Test
	public void testUrduVerbForms() {
		assertMeet(IndicStemmers.URDU, "کرتا", "کرتی", "کرتے", "کرنا", "کرنے");
	}

	/**
	 * A suffix table is in the script's own characters, so that what the
	 * chain hands the stemmer after Unicode normalization is what the table
	 * holds - the Odia ḍa with a nukta is two characters in both.
	 */
	@Test
	public void testOdiaPluralOfThingsIsWrittenDecomposed() {
		var plural = "ଘରଗୁଡ଼ିକ";
		assertThat(plural.indexOf('଼') > 0, is(true));
		assertThat(plural.indexOf('ଡ଼'), is(-1));
		assertThat(stem(IndicStemmers.ODIA, plural), is("ଘର"));
	}
}
