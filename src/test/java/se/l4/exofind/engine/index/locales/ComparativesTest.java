package se.l4.exofind.engine.index.locales;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

/**
 * Tests for the lexicon of comparative words - that phrases are found folded
 * and that every locale with a lexicon reads the words a shopper types.
 */
public class ComparativesTest {
	@Test
	public void testPhrasesAreFoundFolded() {
		var comparatives = Comparatives.create()
			.below("Under", "less than")
			.build();

		assertThat(comparatives.boundOf("under"), is(Comparison.BELOW));
		assertThat(comparatives.boundOf("less than"), is(Comparison.BELOW));
		assertThat(comparatives.boundOf("over"), is(nullValue()));
	}

	@Test
	public void testLongestPhraseCountsWords() {
		var comparatives = Comparatives.create()
			.below("under")
			.atLeast("at least")
			.between("between")
			.to("to")
			.build();

		assertThat(comparatives.longestPhrase(), is(2));
	}

	@Test
	public void testEmptyLexiconHasNothing() {
		assertThat(Comparatives.none().isEmpty(), is(true));
		assertThat(Comparatives.none().longestPhrase(), is(0));
	}

	@Test
	public void testLocalesReadTheirOwnWords() {
		assertThat(bound("en", "under"), is(Comparison.BELOW));
		assertThat(bound("en", "at least"), is(Comparison.AT_LEAST));
		assertThat(bound("sv", "högst"), is(Comparison.AT_MOST));
		assertThat(bound("sv", "över"), is(Comparison.ABOVE));
		assertThat(bound("de", "höchstens"), is(Comparison.AT_MOST));
		assertThat(bound("de", "mehr als"), is(Comparison.ABOVE));
		assertThat(bound("fr", "moins de"), is(Comparison.BELOW));
		assertThat(bound("es", "más de"), is(Comparison.ABOVE));
		assertThat(bound("nb", "minst"), is(Comparison.AT_LEAST));
		assertThat(bound("da", "højst"), is(Comparison.AT_MOST));
		assertThat(bound("nl", "minder dan"), is(Comparison.BELOW));
		assertThat(bound("it", "almeno"), is(Comparison.AT_LEAST));
		assertThat(bound("fi", "alle"), is(Comparison.BELOW));
		assertThat(bound("pt", "mais de"), is(Comparison.ABOVE));
	}

	@Test
	public void testLocaleWithoutALexiconReadsNoWords() {
		assertThat(Locales.get("ja").orElseThrow().getComparatives().isEmpty(), is(true));
	}

	private static Comparison bound(String locale, String phrase) {
		return Locales.get(locale)
			.orElseThrow()
			.getComparatives()
			.boundOf(Comparatives.fold(phrase));
	}
}
