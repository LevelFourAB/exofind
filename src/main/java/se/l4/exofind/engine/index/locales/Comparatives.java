package se.l4.exofind.engine.index.locales;

import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.map.ImmutableMap;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.MutableSet;

import com.ibm.icu.text.Normalizer2;

/**
 * The words of a locale that put a bound on a number: {@code under 100},
 * {@code högst 100}, {@code mehr als 100}, {@code between 100 and 200}.
 *
 * <p>A search in {@link se.l4.exofind.engine.query.matchers.TextMatcher.Match#USER
 * user} mode looks the words next to a number up here, in the lexicon of the
 * search locale, to read the number as a filter. A locale without a lexicon
 * still reads a number written with a unit, such as {@code 100 kr}; what it
 * cannot read is a bound without one.
 *
 * <p>Every phrase is kept case folded, and a caller looks phrases up with
 * text folded the same way through {@link #fold(String)}. A phrase may hold
 * several words separated by single spaces.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class Comparatives {
	private static final Normalizer2 FOLDING = Normalizer2.getNFKCCasefoldInstance();

	private static final Comparatives NONE = new Comparatives(
		Maps.immutable.empty(),
		Sets.immutable.empty(),
		Sets.immutable.empty()
	);

	private final ImmutableMap<String, Comparison> bounds;
	private final ImmutableSet<String> rangeOpeners;
	private final ImmutableSet<String> rangeJoiners;
	private final int longestPhrase;

	private Comparatives(
		ImmutableMap<String, Comparison> bounds,
		ImmutableSet<String> rangeOpeners,
		ImmutableSet<String> rangeJoiners
	) {
		this.bounds = bounds;
		this.rangeOpeners = rangeOpeners;
		this.rangeJoiners = rangeJoiners;

		var longest = 0;
		for(var phrase : bounds.keysView()) {
			longest = Math.max(longest, wordsIn(phrase));
		}
		for(var phrase : rangeOpeners) {
			longest = Math.max(longest, wordsIn(phrase));
		}
		for(var phrase : rangeJoiners) {
			longest = Math.max(longest, wordsIn(phrase));
		}
		this.longestPhrase = longest;
	}

	private static int wordsIn(String phrase) {
		var words = 1;
		for(var i = 0; i < phrase.length(); i++) {
			if(phrase.charAt(i) == ' ') {
				words++;
			}
		}
		return words;
	}

	/**
	 * Get a lexicon with no words in it.
	 *
	 * @return
	 */
	public static Comparatives none() {
		return NONE;
	}

	/**
	 * Start building a lexicon.
	 *
	 * @return
	 */
	public static Builder create() {
		return new Builder();
	}

	/**
	 * Fold text the way the phrases of a lexicon are kept, so that a typed
	 * word meets its entry whatever case it was typed in.
	 *
	 * @param text
	 * @return
	 */
	public static String fold(String text) {
		return FOLDING.normalize(text);
	}

	/**
	 * Get if this lexicon holds no words at all.
	 *
	 * @return
	 */
	public boolean isEmpty() {
		return bounds.isEmpty() && rangeOpeners.isEmpty() && rangeJoiners.isEmpty();
	}

	/**
	 * Get how many words the longest phrase of this lexicon holds, which is
	 * how many typed words a caller has to look at together.
	 *
	 * @return
	 */
	public int longestPhrase() {
		return longestPhrase;
	}

	/**
	 * Get the bound a phrase puts on a number, or {@code null} when the phrase
	 * is not one of this lexicon.
	 *
	 * @param phrase
	 *   folded words separated by single spaces
	 * @return
	 */
	public Comparison boundOf(String phrase) {
		return bounds.get(phrase);
	}

	/**
	 * Get if a phrase opens a range of two numbers, the way {@code between}
	 * does in {@code between 100 and 200}.
	 *
	 * @param phrase
	 *   folded words separated by single spaces
	 * @return
	 */
	public boolean opensRange(String phrase) {
		return rangeOpeners.contains(phrase);
	}

	/**
	 * Get if a phrase joins the two numbers of a range, the way {@code to}
	 * does in {@code 100 to 200}.
	 *
	 * @param phrase
	 *   folded words separated by single spaces
	 * @return
	 */
	public boolean joinsRange(String phrase) {
		return rangeJoiners.contains(phrase);
	}

	/**
	 * Builder of a lexicon. Phrases are given as a locale writes them and
	 * folded here.
	 */
	public static final class Builder {
		private final MutableMap<String, Comparison> bounds = Maps.mutable.empty();
		private final MutableSet<String> rangeOpeners = Sets.mutable.empty();
		private final MutableSet<String> rangeJoiners = Sets.mutable.empty();

		private Builder() {
		}

		private Builder bound(Comparison comparison, String... phrases) {
			for(var phrase : phrases) {
				bounds.put(fold(phrase), comparison);
			}
			return this;
		}

		/**
		 * Add phrases that ask for values below a number.
		 *
		 * @param phrases
		 * @return
		 */
		public Builder below(String... phrases) {
			return bound(Comparison.BELOW, phrases);
		}

		/**
		 * Add phrases that ask for values up to and including a number.
		 *
		 * @param phrases
		 * @return
		 */
		public Builder atMost(String... phrases) {
			return bound(Comparison.AT_MOST, phrases);
		}

		/**
		 * Add phrases that ask for values above a number.
		 *
		 * @param phrases
		 * @return
		 */
		public Builder above(String... phrases) {
			return bound(Comparison.ABOVE, phrases);
		}

		/**
		 * Add phrases that ask for values from a number and upwards.
		 *
		 * @param phrases
		 * @return
		 */
		public Builder atLeast(String... phrases) {
			return bound(Comparison.AT_LEAST, phrases);
		}

		/**
		 * Add phrases that open a range of two numbers.
		 *
		 * @param phrases
		 * @return
		 */
		public Builder between(String... phrases) {
			for(var phrase : phrases) {
				rangeOpeners.add(fold(phrase));
			}
			return this;
		}

		/**
		 * Add phrases that join the two numbers of a range.
		 *
		 * @param phrases
		 * @return
		 */
		public Builder to(String... phrases) {
			for(var phrase : phrases) {
				rangeJoiners.add(fold(phrase));
			}
			return this;
		}

		public Comparatives build() {
			return new Comparatives(
				bounds.toImmutable(),
				rangeOpeners.toImmutable(),
				rangeJoiners.toImmutable()
			);
		}
	}
}
