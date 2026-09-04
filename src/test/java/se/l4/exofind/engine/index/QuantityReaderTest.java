package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ImmutableList;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.locales.Locales;

/**
 * Tests for reading numbers out of typed words - what counts as a quantity,
 * which words it takes, and how the number and its unit are read.
 */
public class QuantityReaderTest {
	private static final ImmutableList<String> UNITS = Lists.immutable.of(
		"kr", "sek", "$", "gb", "gigabyte", "€"
	);

	@Test
	public void testComparativeWordMakesABound() {
		var quantities = read("en", "shoes", "under", "100");

		assertThat(quantities, hasSize(1));
		var quantity = quantities.get(0);
		assertThat(quantity.start(), is(1));
		assertThat(quantity.end(), is(3));
		assertThat(quantity.lower(), is(nullValue()));
		assertThat(quantity.upper(), is(100L));
		assertThat(quantity.upperInclusive(), is(false));
		assertThat(quantity.unit(), is(nullValue()));
	}

	@Test
	public void testComparativeOfSeveralWordsMakesABound() {
		var quantity = one("en", "at", "least", "100");

		assertThat(quantity.start(), is(0));
		assertThat(quantity.end(), is(3));
		assertThat(quantity.lower(), is(100L));
		assertThat(quantity.lowerInclusive(), is(true));
		assertThat(quantity.upper(), is(nullValue()));
	}

	@Test
	public void testAtMostIncludesTheNumber() {
		var quantity = one("en", "max", "100");

		assertThat(quantity.upper(), is(100L));
		assertThat(quantity.upperInclusive(), is(true));
	}

	@Test
	public void testAboveLeavesTheNumberOut() {
		var quantity = one("en", "over", "100");

		assertThat(quantity.lower(), is(100L));
		assertThat(quantity.lowerInclusive(), is(false));
	}

	@Test
	public void testUnitAfterTheNumberMakesAnExactValue() {
		var quantity = one("en", "100", "kr");

		assertThat(quantity.isExact(), is(true));
		assertThat(quantity.lower(), is(100L));
		assertThat(quantity.unit(), is("kr"));
	}

	@Test
	public void testUnitBeforeTheNumberIsRead() {
		var quantity = one("en", "SEK", "100");

		assertThat(quantity.unit(), is("sek"));
		assertThat(quantity.lower(), is(100L));
	}

	@Test
	public void testUnitGluedToTheNumberIsRead() {
		assertThat(one("en", "100kr").unit(), is("kr"));
		assertThat(one("en", "$100").unit(), is("$"));
		assertThat(one("en", "16GB").lower(), is(16L));
		assertThat(one("en", "16GB").unit(), is("gb"));
	}

	@Test
	public void testComparativeAndUnitReadTogether() {
		var quantity = one("en", "under", "100", "kr");

		assertThat(quantity.end(), is(3));
		assertThat(quantity.upper(), is(100L));
		assertThat(quantity.unit(), is("kr"));
	}

	@Test
	public void testNumberOnItsOwnIsAWord() {
		assertThat(read("en", "size", "44"), is(empty()));
		assertThat(read("en", "iphone", "15"), is(empty()));
	}

	@Test
	public void testNumberInsideAWordIsAWord() {
		assertThat(read("en", "4k", "tv"), is(empty()));
		assertThat(read("en", "mp3"), is(empty()));
		assertThat(read("en", "under", "h100"), is(empty()));
	}

	@Test
	public void testComparativeWithoutANumberIsAWord() {
		assertThat(read("en", "under", "armour"), is(empty()));
	}

	@Test
	public void testWordsAfterTheNumberAreLeftAlone() {
		var quantities = read("en", "under", "100", "shoes");

		assertThat(quantities, hasSize(1));
		assertThat(quantities.get(0).end(), is(2));
	}

	@Test
	public void testTwoNumbersJoinedMakeARange() {
		var quantity = one("en", "100", "to", "200", "kr");

		assertThat(quantity.lower(), is(100L));
		assertThat(quantity.lowerInclusive(), is(true));
		assertThat(quantity.upper(), is(200L));
		assertThat(quantity.upperInclusive(), is(true));
		assertThat(quantity.unit(), is("kr"));
	}

	@Test
	public void testTwoNumbersGluedMakeARange() {
		var quantity = one("en", "100-200", "kr");

		assertThat(quantity.lower(), is(100L));
		assertThat(quantity.upper(), is(200L));
		assertThat(quantity.unit(), is("kr"));
		assertThat(quantity.end(), is(2));
	}

	@Test
	public void testRangeOpenedByAWordIsRead() {
		var quantity = one("en", "between", "100", "and", "200");

		assertThat(quantity.lower(), is(100L));
		assertThat(quantity.upper(), is(200L));
		assertThat(quantity.end(), is(4));
	}

	@Test
	public void testFromToIsARangeAndFromAloneIsABound() {
		var range = one("en", "from", "100", "to", "200");
		assertThat(range.lower(), is(100L));
		assertThat(range.upper(), is(200L));

		var bound = one("en", "from", "100");
		assertThat(bound.lower(), is(100L));
		assertThat(bound.upper(), is(nullValue()));
	}

	@Test
	public void testRangeEndingBeforeItStartsIsNotARange() {
		assertThat(read("en", "200-100", "kr"), is(empty()));
	}

	@Test
	public void testRangeOfTwoUnitsIsNotARange() {
		assertThat(read("en", "100kr-200gb"), is(empty()));
	}

	@Test
	public void testGluedRangeTakesAUnitOnEitherSide() {
		assertThat(one("en", "$100-$200").unit(), is("$"));
		assertThat(one("en", "100-200kr").unit(), is("kr"));
		assertThat(one("en", "100-200").unit(), is(nullValue()));
	}

	@Test
	public void testJoinerWithoutASecondNumberIsAWord() {
		assertThat(read("en", "100", "to", "nike"), is(empty()));
	}

	@Test
	public void testDecimalsAreReadTheWayTheLocaleWritesThem() {
		assertThat(one("en", "1.5", "gb").lower(), is(1.5));
		assertThat(one("en", "1,000", "kr").lower(), is(1000L));
		assertThat(one("de", "1.000,00", "€").lower(), is(1000L));
		assertThat(one("de", "1,5", "gb").lower(), is(1.5));
		assertThat(one("sv", "1,5", "gb").lower(), is(1.5));
	}

	@Test
	public void testDecimalIsReadTheRootWayWhereTheLocaleFails() {
		assertThat(one("sv", "1.5", "gb").lower(), is(1.5));
	}

	@Test
	public void testGroupOfDigitsIsNotANumber() {
		// `1 000 kr` typed with a space: the `000` is never read as zero
		assertThat(read("sv", "1", "000", "kr"), is(empty()));
	}

	@Test
	public void testWordsAreReadFolded() {
		var quantity = one("en", "Under", "100", "KR");

		assertThat(quantity.upper(), is(100L));
		assertThat(quantity.unit(), is("kr"));
	}

	@Test
	public void testLocaleDecidesTheComparatives() {
		assertThat(one("sv", "högst", "100").upperInclusive(), is(true));
		assertThat(read("en", "högst", "100"), is(empty()));
	}

	@Test
	public void testLocaleWithoutALexiconStillReadsUnits() {
		var quantity = one("ja", "100", "kr");

		assertThat(quantity.lower(), is(100L));
		assertThat(quantity.unit(), is("kr"));
	}

	@Test
	public void testSeveralQuantitiesAreReadInOrder() {
		var quantities = read("en", "laptop", "16gb", "under", "1000", "kr");

		assertThat(quantities, hasSize(2));
		assertThat(quantities.get(0).unit(), is("gb"));
		assertThat(quantities.get(0).start(), is(1));
		assertThat(quantities.get(1).upper(), is(1000L));
		assertThat(quantities.get(1).start(), is(2));
		assertThat(quantities.get(1).end(), is(5));
	}

	private static QuantityReader.Quantity one(String locale, String... words) {
		var quantities = read(locale, words);
		assertThat(quantities, hasSize(1));
		return quantities.get(0);
	}

	private static List<QuantityReader.Quantity> read(String locale, String... words) {
		var reader = new QuantityReader(
			Locales.get(locale).orElseThrow(),
			Sets.immutable.ofAll(UNITS)
		);
		return reader.read(Lists.immutable.of(words)).toList();
	}
}
