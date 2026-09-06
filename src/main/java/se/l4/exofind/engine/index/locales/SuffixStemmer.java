package se.l4.exofind.engine.index.locales;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

/**
 * A light stemmer that takes known endings off a word, for the languages
 * Lucene ships no stemmer for.
 *
 * Light stemming does not try to find the dictionary form of a word. It cuts
 * the endings that inflection adds - case, number, tense - so that the forms
 * of one word end at the same string, which is all a search needs. This is
 * the approach of Lucene's own Hindi, Bengali and Telugu stemmers, and of the
 * languages that share it here: an ending is cut when the word carries it and
 * enough of the word is left for the cut to be an ending rather than the word
 * itself.
 *
 * The endings are grouped in steps. Each step cuts at most one ending, the
 * longest of its that fits, and the steps run in order over what is left. Two
 * steps let a case ending be cut before the plural marker it sits on without
 * listing every combination of the two.
 *
 * Instances are immutable and safe to use from several threads.
 */
public final class SuffixStemmer {
	/**
	 * One ending, and how many characters must remain in front of it for
	 * it to be cut.
	 */
	private record Rule(char[] suffix, int minStem) {
	}

	private final Rule[][] steps;

	private SuffixStemmer(Rule[][] steps) {
		this.steps = steps;
	}

	/**
	 * Stem a word in place.
	 *
	 * @param buffer
	 *   the characters of the word
	 * @param length
	 *   how many of them the word is
	 * @return
	 *   the length of the stem, which is at most {@code length}
	 */
	public int stem(char[] buffer, int length) {
		for(var step : steps) {
			for(var rule : step) {
				var suffix = rule.suffix;
				if(length - suffix.length < rule.minStem) {
					continue;
				}

				if(endsWith(buffer, length, suffix)) {
					length -= suffix.length;
					break;
				}
			}
		}

		return length;
	}

	private static boolean endsWith(char[] buffer, int length, char[] suffix) {
		var offset = length - suffix.length;
		for(var i = 0; i < suffix.length; i++) {
			if(buffer[offset + i] != suffix[i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Wrap this stemmer as a filter over a token stream. A token marked as a
	 * keyword is passed through untouched.
	 *
	 * @param stream
	 * @return
	 */
	public TokenStream filter(TokenStream stream) {
		return new Filter(stream, this);
	}

	/**
	 * Start describing a stemmer.
	 *
	 * @return
	 */
	public static Builder create() {
		return new Builder();
	}

	public static final class Builder {
		private final List<Rule[]> steps = new ArrayList<>();

		private Builder() {
		}

		/**
		 * Add a step. The endings are tried longest first, and the first one
		 * the word carries with at least {@code minStem} characters in front
		 * of it is cut.
		 *
		 * @param minStem
		 *   how many characters have to remain in front of an ending
		 * @param suffixes
		 *   the endings of the step, in any order
		 * @return
		 */
		public Builder step(int minStem, String... suffixes) {
			var rules = new ArrayList<Rule>();
			for(var suffix : suffixes) {
				rules.add(new Rule(suffix.toCharArray(), minStem));
			}
			rules.sort(Comparator.comparingInt((Rule rule) -> rule.suffix.length).reversed());
			steps.add(rules.toArray(Rule[]::new));
			return this;
		}

		public SuffixStemmer build() {
			return new SuffixStemmer(steps.toArray(Rule[][]::new));
		}
	}

	private static final class Filter extends TokenFilter {
		private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
		private final KeywordAttribute keyword = addAttribute(KeywordAttribute.class);
		private final SuffixStemmer stemmer;

		Filter(TokenStream input, SuffixStemmer stemmer) {
			super(input);
			this.stemmer = stemmer;
		}

		@Override
		public boolean incrementToken() throws IOException {
			if(!input.incrementToken()) {
				return false;
			}

			if(!keyword.isKeyword()) {
				var length = stemmer.stem(term.buffer(), term.length());
				if(length != term.length()) {
					term.setLength(length);
				}
			}

			return true;
		}
	}
}
