package se.l4.exofind.engine.index.locales;

import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.MutableSet;

import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.NumberFormatter.UnitWidth;
import com.ibm.icu.text.ConstrainedFieldPosition;
import com.ibm.icu.text.NumberFormat;
import com.ibm.icu.util.Currency;
import com.ibm.icu.util.MeasureUnit;
import com.ibm.icu.util.ULocale;

/**
 * How a unit a field declares is written in a locale, for reading it in typed
 * text.
 *
 * <p>A field declares its unit once, as an ISO 4217 currency code such as
 * {@code SEK}, as a CLDR unit identifier such as {@code kilogram}, or as text
 * of its own. People type it many ways: {@code 100 kr}, {@code SEK 100},
 * {@code 100 kronor}, {@code 2 GB}, {@code 2 gigabytes}. This class answers
 * every spelling of a unit that a locale knows, so a search can tell whether
 * a typed word is the unit of some field.
 *
 * <p>The spellings of a currency and of a CLDR unit come from the locale data
 * ICU ships, in the search locale and in English - English abbreviations
 * such as {@code GB} are typed everywhere. A unit that is neither is matched
 * as it was declared and nothing else. Spellings are case folded, and only
 * single words are kept: a name written as several words, such as
 * {@code Swedish krona}, is not one a search box reads.
 *
 * <p>Answers are cached per unit and locale. Safe to call from any thread.
 */
public final class Units {
	private static final ConcurrentHashMap<Key, ImmutableSet<String>> SPELLINGS =
		new ConcurrentHashMap<>();

	private static final ULocale ENGLISH = ULocale.ENGLISH;

	private record Key(String unit, String locale) {
	}

	private Units() {
	}

	/**
	 * Get if a declared unit is a currency, which is what a bare number next
	 * to a comparative word is read as.
	 *
	 * @param unit
	 *   the unit as the definition declares it
	 * @return
	 */
	public static boolean isCurrency(String unit) {
		return currencyOf(unit) != null;
	}

	/**
	 * Get every spelling of a declared unit that a locale knows, folded the
	 * way {@link Comparatives#fold(String)} folds text.
	 *
	 * @param unit
	 *   the unit as the definition declares it
	 * @param locale
	 *   the locale the text was typed in
	 * @return
	 */
	public static ImmutableSet<String> spellingsOf(String unit, LocaleSupport locale) {
		return SPELLINGS.computeIfAbsent(
			new Key(unit, locale.getLocale()),
			key -> spellings(key.unit(), ULocale.forLanguageTag(key.locale()))
		);
	}

	private static ImmutableSet<String> spellings(String unit, ULocale locale) {
		var result = Sets.mutable.<String>empty();
		add(result, unit);

		var currency = currencyOf(unit);
		if(currency != null) {
			currencySpellings(currency, locale, result);
			currencySpellings(currency, ENGLISH, result);
			currencySpellings(currency, ULocale.ROOT, result);

			/*
			 * The Swedish way of writing a price on a sign, which no locale
			 * data spells out.
			 */
			if(unit.equals("SEK")) {
				add(result, ":-");
			}

			return result.toImmutable();
		}

		var measure = measureOf(unit);
		if(measure != null) {
			measureSpellings(measure, locale, result);
			measureSpellings(measure, ENGLISH, result);
		}

		return result.toImmutable();
	}

	/**
	 * Get the currency a unit names, or {@code null} when it is not a
	 * currency code. Only the code itself counts: a symbol such as {@code kr}
	 * names several currencies and is a spelling, not a declaration.
	 */
	private static Currency currencyOf(String unit) {
		if(unit.length() != 3) {
			return null;
		}

		for(var i = 0; i < 3; i++) {
			var c = unit.charAt(i);
			if(c < 'A' || c > 'Z') {
				return null;
			}
		}

		return Currency.isAvailable(unit, null, null) ? Currency.getInstance(unit) : null;
	}

	private static void currencySpellings(Currency currency, ULocale locale, MutableSet<String> into) {
		add(into, currency.getSymbol(locale));
		add(into, currency.getName(locale, Currency.NARROW_SYMBOL_NAME, null));
		add(into, currency.getName(locale, Currency.FORMAL_SYMBOL_NAME, null));
		add(into, currency.getName(locale, Currency.LONG_NAME, null));
		add(into, currency.getName(locale, Currency.PLURAL_LONG_NAME, "one", null));
		add(into, currency.getName(locale, Currency.PLURAL_LONG_NAME, "other", null));
	}

	/**
	 * Get the CLDR unit an identifier names, or {@code null} when it names
	 * none.
	 */
	private static MeasureUnit measureOf(String unit) {
		try {
			return MeasureUnit.forIdentifier(unit);
		} catch(IllegalArgumentException e) {
			return null;
		}
	}

	private static void measureSpellings(MeasureUnit unit, ULocale locale, MutableSet<String> into) {
		for(var width : new UnitWidth[] { UnitWidth.NARROW, UnitWidth.SHORT, UnitWidth.FULL_NAME }) {
			for(var count = 1; count <= 2; count++) {
				var formatted = NumberFormatter.with()
					.unit(unit)
					.unitWidth(width)
					.locale(locale)
					.format(count);

				var position = new ConstrainedFieldPosition();
				position.constrainField(NumberFormat.Field.MEASURE_UNIT);

				var text = formatted.toString();
				var found = false;
				while(formatted.nextPosition(position)) {
					add(into, text.substring(position.getStart(), position.getLimit()));
					found = true;
				}

				/*
				 * A unit written as a sign next to the number, such as a
				 * percent, is not marked as a unit by the formatter. What is
				 * left once the number goes is the sign.
				 */
				if(!found) {
					add(into, text.replace(Integer.toString(count), ""));
				}
			}
		}
	}

	/**
	 * Keep a spelling if it is a single word with something in it.
	 */
	private static void add(MutableSet<String> into, String spelling) {
		if(spelling == null) {
			return;
		}

		var folded = Comparatives.fold(spelling.strip());
		if(folded.isEmpty()) {
			return;
		}

		for(var i = 0; i < folded.length(); i++) {
			if(Character.isWhitespace(folded.charAt(i))) {
				return;
			}
		}

		into.add(folded);
	}
}
