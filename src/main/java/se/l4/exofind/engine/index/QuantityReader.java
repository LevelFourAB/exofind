package se.l4.exofind.engine.index;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParsePosition;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.set.SetIterable;

import com.ibm.icu.text.NumberFormat;
import com.ibm.icu.util.ULocale;

import se.l4.exofind.engine.index.locales.Comparatives;
import se.l4.exofind.engine.index.locales.Comparison;
import se.l4.exofind.engine.index.locales.LocaleSupport;

/**
 * Reading a number, and what was typed around it, out of the words of a
 * search box.
 *
 * <p>A quantity is a number with something that says it is one: a unit
 * ({@code 100 kr}, {@code 2tb}, {@code $100}), a comparative word of the
 * search locale ({@code under 100}, {@code högst 100}), or a second number it
 * makes a range with ({@code 100-200}, {@code between 100 and 200}). A number
 * on its own is a word - {@code size 44} and {@code iphone 15} ask for text -
 * and so is anything a number sits in, such as {@code 4k} or {@code mp3}. A
 * number is one word: {@code 1 000} typed with a space is two words, neither
 * of which is read.
 *
 * <p>Numbers are read the way the search locale writes them, through ICU, and
 * the way the root locale writes them where that fails: {@code 1.000,00} in
 * German, {@code 1,000} and {@code 1.5} in English, {@code 1 000} in Swedish.
 * Units are matched against the spellings the caller gives, and comparatives
 * against the lexicon of the locale, both case folded.
 *
 * <p>Instances are cheap and hold no state between calls. Reading is greedy
 * from the left: at each word the longest span that reads as a quantity wins,
 * and reading goes on after it.
 */
final class QuantityReader {
	/**
	 * How many words one amount may take: a unit and a number, in either
	 * order.
	 */
	private static final int AMOUNT_WORDS = 2;

	/**
	 * One quantity read out of a run of words.
	 *
	 * @param start
	 *   index of the first word it was read from
	 * @param end
	 *   index after the last word it was read from
	 * @param lower
	 *   the value the range starts at, {@code null} for open
	 * @param lowerInclusive
	 *   if a value equal to {@code lower} is inside
	 * @param upper
	 *   the value the range ends at, {@code null} for open
	 * @param upperInclusive
	 *   if a value equal to {@code upper} is inside
	 * @param unit
	 *   the folded spelling of the unit that was typed, or {@code null} when
	 *   none was
	 */
	record Quantity(
		int start,
		int end,
		Number lower,
		boolean lowerInclusive,
		Number upper,
		boolean upperInclusive,
		String unit
	) {
		/**
		 * Get if this is one number rather than a range - a number written
		 * with its unit and nothing else.
		 */
		boolean isExact() {
			return lower != null
				&& lower.equals(upper)
				&& lowerInclusive
				&& upperInclusive;
		}
	}

	/**
	 * A number with the unit it was typed with, and how many words it took.
	 */
	private record Amount(Number value, String unit, int consumed) {
	}

	/**
	 * A word that holds a number, with the unit glued onto it if any -
	 * {@code $100}, {@code 100kr}, {@code 100}.
	 */
	private record NumberWord(Number value, String unit) {
	}

	private final Comparatives comparatives;
	private final SetIterable<String> units;
	private final NumberFormat localeFormat;
	private final NumberFormat rootFormat;

	/**
	 * @param locale
	 *   the locale the text was typed in
	 * @param units
	 *   every folded spelling that is the unit of some field
	 */
	QuantityReader(LocaleSupport locale, SetIterable<String> units) {
		this.comparatives = locale.getComparatives();
		this.units = units;

		this.localeFormat = NumberFormat.getInstance(ULocale.forLanguageTag(locale.getLocale()));
		this.localeFormat.setParseStrict(false);

		this.rootFormat = NumberFormat.getInstance(ULocale.ROOT);
		this.rootFormat.setParseStrict(false);
	}

	/**
	 * Read the quantities in a run of words that were typed next to each
	 * other.
	 *
	 * @param words
	 *   the words as typed
	 * @return
	 *   what was read, in the order it was typed and never overlapping
	 */
	ImmutableList<Quantity> read(ListIterable<String> words) {
		var folded = new String[words.size()];
		var i = 0;
		for(var word : words) {
			folded[i++] = Comparatives.fold(word);
		}

		var result = Lists.mutable.<Quantity>empty();
		var longest = comparatives.longestPhrase() * 2 + AMOUNT_WORDS * 2;

		var at = 0;
		while(at < folded.length) {
			Quantity found = null;
			for(var length = Math.min(longest, folded.length - at); length >= 1; length--) {
				found = readSpan(folded, at, at + length);
				if(found != null) {
					break;
				}
			}

			if(found == null) {
				at++;
			} else {
				result.add(found);
				at = found.end();
			}
		}

		return result.toImmutable();
	}

	/**
	 * Read the words from {@code start} up to {@code end} as one quantity,
	 * answering {@code null} unless they are exactly that.
	 */
	private Quantity readSpan(String[] words, int start, int end) {
		var quantity = readRange(words, start, end);
		if(quantity == null) {
			quantity = readBound(words, start, end);
		}
		return quantity;
	}

	/**
	 * Read a range opened by a word: {@code between 100 and 200}.
	 */
	private Quantity readRange(String[] words, int start, int end) {
		var opener = phraseLength(words, start, end, comparatives::opensRange);
		if(opener == 0) {
			return null;
		}

		var at = start + opener;
		var first = readAmount(words, at, end);
		if(first == null) {
			return null;
		}
		at += first.consumed();

		var joiner = phraseLength(words, at, end, comparatives::joinsRange);
		if(joiner == 0) {
			return null;
		}
		at += joiner;

		var second = readAmount(words, at, end);
		if(second == null || at + second.consumed() != end) {
			return null;
		}

		return between(start, end, first, second);
	}

	/**
	 * Read a number with a comparative word in front of it, a number with its
	 * unit, or two numbers joined into a range: {@code under 100},
	 * {@code 100 kr}, {@code 100 to 200}, {@code 100-200}.
	 */
	private Quantity readBound(String[] words, int start, int end) {
		var at = start;

		Comparison comparison = null;
		var longest = Math.min(comparatives.longestPhrase(), end - at);
		for(var length = longest; length >= 1; length--) {
			comparison = comparatives.boundOf(join(words, at, at + length));
			if(comparison != null) {
				at += length;
				break;
			}
		}

		if(comparison == null) {
			var glued = readGluedRange(words, start, at, end);
			if(glued != null) {
				return glued;
			}
		}

		var first = readAmount(words, at, end);
		if(first == null) {
			return null;
		}
		at += first.consumed();

		if(at == end) {
			if(comparison == null && first.unit() == null) {
				// A number on its own is a word
				return null;
			}

			return bound(start, end, comparison, first);
		}

		if(comparison != null) {
			return null;
		}

		var joiner = phraseLength(words, at, end, comparatives::joinsRange);
		if(joiner == 0) {
			return null;
		}
		at += joiner;

		var second = readAmount(words, at, end);
		if(second == null || at + second.consumed() != end) {
			return null;
		}

		return between(start, end, first, second);
	}

	/**
	 * Read two numbers written as one word with a dash between them, with
	 * a unit glued to either or written after: {@code 100-200},
	 * {@code 100-200 kr}, {@code $100-$200}.
	 */
	private Quantity readGluedRange(String[] words, int start, int at, int end) {
		var word = words[at];
		var dash = word.indexOf('-');
		if(dash < 0) {
			dash = word.indexOf('–');
		}

		if(dash <= 0 || dash >= word.length() - 1) {
			return null;
		}

		var first = numberWord(word.substring(0, dash));
		var second = numberWord(word.substring(dash + 1));
		if(first == null || second == null) {
			return null;
		}

		var consumed = 1;
		var unit = second.unit();
		if(unit == null
			&& first.unit() == null
			&& at + 1 < end
			&& units.contains(words[at + 1])) {
			unit = words[at + 1];
			consumed = 2;
		}

		if(at + consumed != end) {
			return null;
		}

		return between(
			start,
			end,
			new Amount(first.value(), first.unit(), 1),
			new Amount(second.value(), unit, consumed)
		);
	}

	private static Quantity bound(int start, int end, Comparison comparison, Amount amount) {
		var value = amount.value();
		return switch(comparison) {
			case null -> new Quantity(start, end, value, true, value, true, amount.unit());
			case BELOW -> new Quantity(start, end, null, false, value, false, amount.unit());
			case AT_MOST -> new Quantity(start, end, null, false, value, true, amount.unit());
			case ABOVE -> new Quantity(start, end, value, false, null, false, amount.unit());
			case AT_LEAST -> new Quantity(start, end, value, true, null, false, amount.unit());
		};
	}

	/**
	 * Make a range of two amounts, or {@code null} when they disagree - two
	 * units, or an end before the start.
	 */
	private static Quantity between(int start, int end, Amount first, Amount second) {
		var unit = first.unit();
		if(second.unit() != null) {
			if(unit != null && !unit.equals(second.unit())) {
				return null;
			}
			unit = second.unit();
		}

		if(first.value().doubleValue() > second.value().doubleValue()) {
			return null;
		}

		return new Quantity(start, end, first.value(), true, second.value(), true, unit);
	}

	/**
	 * Read a number and its unit at a position: a word holding both, a number
	 * followed by a unit word, a unit word followed by a number, or a number
	 * alone.
	 */
	private Amount readAmount(String[] words, int at, int end) {
		if(at >= end) {
			return null;
		}

		var number = numberWord(words[at]);
		if(number != null) {
			if(number.unit() != null) {
				return new Amount(number.value(), number.unit(), 1);
			}

			if(at + 1 < end && units.contains(words[at + 1])) {
				return new Amount(number.value(), words[at + 1], 2);
			}

			return new Amount(number.value(), null, 1);
		}

		if(units.contains(words[at]) && at + 1 < end) {
			var after = numberWord(words[at + 1]);
			if(after != null && after.unit() == null) {
				return new Amount(after.value(), words[at], 2);
			}
		}

		return null;
	}

	/**
	 * Read one word as a number, with a unit glued to either side of it or
	 * none. A word holding anything else is not a number.
	 */
	private NumberWord numberWord(String word) {
		var first = -1;
		var last = -1;
		for(var i = 0; i < word.length(); i++) {
			if(Character.isDigit(word.charAt(i))) {
				if(first < 0) {
					first = i;
				}
				last = i;
			}
		}

		if(first < 0) {
			return null;
		}

		for(var i = first; i <= last; i++) {
			var c = word.charAt(i);
			if(!Character.isDigit(c) && !isSeparator(c)) {
				return null;
			}
		}

		/*
		 * A word of digits starting with a zero is a group of a number typed
		 * with spaces - the 000 of `1 000` - and never a number of its own.
		 * Reading it as zero would filter on a value nobody typed.
		 */
		if(last > first && word.charAt(first) == '0' && Character.isDigit(word.charAt(first + 1))) {
			return null;
		}

		var before = word.substring(0, first);
		var after = word.substring(last + 1);

		String unit = null;
		if(!before.isEmpty() && !after.isEmpty()) {
			return null;
		} else if(!before.isEmpty()) {
			if(!units.contains(before)) {
				return null;
			}
			unit = before;
		} else if(!after.isEmpty()) {
			if(!units.contains(after)) {
				return null;
			}
			unit = after;
		}

		var value = parseNumber(word.substring(first, last + 1));
		return value == null ? null : new NumberWord(value, unit);
	}

	/**
	 * What may sit between the digits of a number: the grouping and decimal
	 * separators of any locale.
	 */
	private static boolean isSeparator(char c) {
		return c == '.'
			|| c == ','
			|| c == '\''
			|| c == ' '
			|| c == ' '
			|| c == ' ';
	}

	/**
	 * Read digits and separators as a number, the way the locale writes one
	 * and failing that the way the root locale does. Only text read in whole
	 * counts: {@code 1.5} in a locale where the point groups thousands reads
	 * as {@code 1} and stops, and is read again as the root locale writes it.
	 */
	private Number parseNumber(String text) {
		var value = parseWhole(localeFormat, text);
		if(value == null) {
			value = parseWhole(rootFormat, text);
		}
		return value == null ? null : normalize(value);
	}

	private static Number parseWhole(NumberFormat format, String text) {
		var position = new ParsePosition(0);
		Number value;
		synchronized(format) {
			value = format.parse(text, position);
		}

		return position.getIndex() == text.length() ? value : null;
	}

	/**
	 * Narrow what ICU parsed to a long for a whole number and a double for
	 * anything else, which is what the field types read.
	 */
	private static Number normalize(Number value) {
		if(value instanceof Long || value instanceof Integer) {
			return value.longValue();
		}

		if(value instanceof BigInteger || value instanceof BigDecimal) {
			var decimal = new BigDecimal(value.toString());
			if(decimal.stripTrailingZeros().scale() <= 0
				&& decimal.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0) {
				return decimal.longValue();
			}
			return decimal.doubleValue();
		}

		var d = value.doubleValue();
		if(!Double.isFinite(d)) {
			return null;
		}

		if(d == Math.rint(d) && Math.abs(d) < 1e15) {
			return (long) d;
		}

		return d;
	}

	/**
	 * Get how many words from {@code at} make a phrase the test accepts, the
	 * longest first, or zero when none does.
	 */
	private int phraseLength(
		String[] words,
		int at,
		int end,
		java.util.function.Predicate<String> test
	) {
		var longest = Math.min(comparatives.longestPhrase(), end - at);
		for(var length = longest; length >= 1; length--) {
			if(test.test(join(words, at, at + length))) {
				return length;
			}
		}
		return 0;
	}

	private static String join(String[] words, int from, int to) {
		if(to - from == 1) {
			return words[from];
		}

		var joined = new StringBuilder();
		for(var i = from; i < to; i++) {
			if(i > from) {
				joined.append(' ');
			}
			joined.append(words[i]);
		}
		return joined.toString();
	}
}
