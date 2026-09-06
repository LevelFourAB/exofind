package se.l4.exofind.engine.index.locales;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

/**
 * Pins what the light stemmers of Slovak and Slovenian cut, so that a change
 * to a suffix table that stops the forms of a word from meeting fails here
 * rather than in search results.
 *
 * Each test hands the stemmer the forms of one word and asserts that they end
 * at one string. The string itself is the stemmer's business; what matters
 * is that a search for any form finds the others.
 */
public class SlavicStemmersTest {
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
	 * Slovak: a feminine noun through its cases, and a masculine one with
	 * the plural cases that end in a consonant.
	 */
	@Test
	public void testSlovakNounCases() {
		assertMeet(SlavicStemmers.SLOVAK, "žena", "ženy", "žene", "ženu", "ženou", "ženám", "ženách", "ženami");
		assertMeet(SlavicStemmers.SLOVAK, "hrad", "hradu", "hrady", "hradov", "hradom", "hradoch", "hradmi");
		assertMeet(SlavicStemmers.SLOVAK, "chlap", "chlapi", "chlapovi", "chlapov", "chlapovia", "chlapoch");
	}

	/**
	 * Slovak: a hard and a soft adjective through gender, number and case.
	 */
	@Test
	public void testSlovakAdjectives() {
		assertMeet(SlavicStemmers.SLOVAK, "pekný", "pekná", "pekné", "pekného", "peknému", "peknom", "peknej", "peknou", "pekní", "pekných", "peknými");
		assertMeet(SlavicStemmers.SLOVAK, "cudzí", "cudzia", "cudzie", "cudzieho", "cudziemu", "cudzích");
	}

	/**
	 * Slovak: the persons and tenses of a verb, and its infinitive, for the
	 * three conjugations.
	 */
	@Test
	public void testSlovakVerbForms() {
		assertMeet(SlavicStemmers.SLOVAK, "robiť", "robím", "robíš", "robí", "robíme", "robia", "robil", "robila", "robili");
		assertMeet(SlavicStemmers.SLOVAK, "čítať", "čítam", "číta", "čítame", "čítajú", "čítal", "čítala");
		assertMeet(SlavicStemmers.SLOVAK, "pracovať", "pracujem", "pracuje", "pracujú", "pracoval", "pracovali");
	}

	/**
	 * A noun that ends like a verb is cut the same way in every form, so its
	 * forms still meet.
	 */
	@Test
	public void testSlovakNounEndingLikeAVerbStaysTogether() {
		assertMeet(SlavicStemmers.SLOVAK, "program", "programu", "programy", "programov", "programom");
	}

	/**
	 * Slovenian: a feminine, a neuter and a short masculine noun through
	 * their cases, including the plural the masculine extends by ov.
	 */
	@Test
	public void testSlovenianNounCases() {
		assertMeet(SlavicStemmers.SLOVENIAN, "hiša", "hiše", "hiši", "hišo", "hiš", "hišam", "hišah", "hišami");
		assertMeet(SlavicStemmers.SLOVENIAN, "mesto", "mesta", "mestu", "mestom", "mestih", "mest");
		assertMeet(SlavicStemmers.SLOVENIAN, "grad", "gradu", "gradom", "gradovi", "gradove", "gradov", "gradovih");
	}

	@Test
	public void testSlovenianAdjectives() {
		assertMeet(SlavicStemmers.SLOVENIAN, "lep", "lepa", "lepo", "lepega", "lepemu", "lepem", "lepim", "lepih", "lepimi");
	}

	/**
	 * Slovenian: the persons and tenses of a verb and its infinitive, for
	 * the a- and the i-conjugation.
	 */
	@Test
	public void testSlovenianVerbForms() {
		assertMeet(SlavicStemmers.SLOVENIAN, "delati", "delam", "delaš", "dela", "delamo", "delajo", "delal", "delala", "delali");
		assertMeet(SlavicStemmers.SLOVENIAN, "hoditi", "hodim", "hodi", "hodimo", "hodijo", "hodil", "hodila", "hodili");
	}

	@Test
	public void testSlovenianNounEndingLikeAVerbStaysTogether() {
		assertMeet(SlavicStemmers.SLOVENIAN, "avtomobil", "avtomobila", "avtomobilu", "avtomobili", "avtomobilov");
		assertMeet(SlavicStemmers.SLOVENIAN, "sistem", "sistema", "sistemi", "sistemov");
	}

	/**
	 * A short word is left as written, so that a word of three letters is
	 * not cut to the letters that happen to remain.
	 */
	@Test
	public void testAShortWordIsLeftAlone() {
		assertThat(stem(SlavicStemmers.SLOVAK, "dom"), is("dom"));
		assertThat(stem(SlavicStemmers.SLOVAK, "oko"), is("oko"));
		assertThat(stem(SlavicStemmers.SLOVENIAN, "pes"), is("pes"));
	}
}
