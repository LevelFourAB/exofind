package se.l4.exofind.engine.index.locales;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;

/**
 * Tests for the spellings of a declared unit - that a currency and a CLDR
 * unit are spelled the way the locale writes them, and that anything else is
 * matched as declared.
 */
public class UnitsTest {
	@Test
	public void testCurrencyCodeIsACurrency() {
		assertThat(Units.isCurrency("SEK"), is(true));
		assertThat(Units.isCurrency("EUR"), is(true));
	}

	@Test
	public void testSymbolAndUnitAreNotCurrencies() {
		assertThat(Units.isCurrency("kr"), is(false));
		assertThat(Units.isCurrency("gigabyte"), is(false));
		assertThat(Units.isCurrency("XYZ"), is(false));
	}

	@Test
	public void testCurrencyIsSpelledTheWayTheLocaleWritesIt() {
		var swedish = Units.spellingsOf("SEK", locale("sv"));
		assertThat(swedish, hasItems("kr", "sek", ":-"));

		var english = Units.spellingsOf("SEK", locale("en"));
		assertThat(english, hasItems("kr", "sek"));
	}

	@Test
	public void testCurrencySymbolIsKnownInEveryLocale() {
		assertThat(Units.spellingsOf("USD", locale("sv")), hasItems("$", "usd"));
		assertThat(Units.spellingsOf("EUR", locale("de")), hasItems("€", "euro"));
	}

	@Test
	public void testMeasureIsSpelledShortAndLong() {
		var english = Units.spellingsOf("gigabyte", locale("en"));
		assertThat(english, hasItems("gb", "gigabyte", "gigabytes"));
	}

	@Test
	public void testMeasureIsSpelledTheWayTheLocaleWritesIt() {
		var french = Units.spellingsOf("gigabyte", locale("fr"));
		assertThat(french, hasItems("go", "gigaoctet"));

		// English abbreviations are typed everywhere
		assertThat(french, hasItem("gb"));
	}

	@Test
	public void testSignNextToTheNumberIsASpelling() {
		assertThat(Units.spellingsOf("percent", locale("en")), hasItem("%"));
	}

	@Test
	public void testNamesOfSeveralWordsAreLeftOut() {
		assertThat(Units.spellingsOf("USD", locale("en")), not(hasItem("us dollar")));
	}

	@Test
	public void testAnythingElseIsMatchedAsDeclared() {
		assertThat(Units.spellingsOf("mAh", locale("en")), contains("mah"));
	}

	private static LocaleSupport locale(String tag) {
		return Locales.get(tag).orElseThrow();
	}
}
