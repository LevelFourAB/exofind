package se.l4.exofind.engine.index.locales;

import java.io.IOException;
import java.io.Reader;

import org.apache.lucene.analysis.CharFilter;

import com.ibm.icu.text.Transliterator;

/**
 * Rewrites the Traditional Han characters of a value as the Simplified forms
 * the Chinese segmenter knows.
 *
 * The segmenter finds a word by looking it up in a model trained on Simplified
 * Chinese, so this has to run over the text rather than over the tokens. Given
 * Traditional characters the model matches nothing and falls back to one token
 * per character.
 *
 * Offsets pass through unchanged. A character is rewritten only where its
 * Simplified form takes the same number of {@code char}s, so a position in the
 * rewritten text is that position in the text as it was given, and a highlight
 * points at what was written. That leaves alone the archaic Han whose
 * Simplified form sits outside the Basic Multilingual Plane, which the model
 * holds no entry for either way.
 *
 * An instance reads one stream and is not safe for concurrent use. The table
 * behind it is built once, when the first instance is created, and is shared
 * from then on.
 */
final class SimplifiedHanCharFilter extends CharFilter {
	SimplifiedHanCharFilter(Reader input) {
		super(input);
	}

	@Override
	public int read(char[] cbuf, int off, int len) throws IOException {
		var read = input.read(cbuf, off, len);
		if(read < 0) {
			return read;
		}

		var simplified = Table.SIMPLIFIED;
		for(var i = off; i < off + read; i++) {
			cbuf[i] = simplified[cbuf[i]];
		}

		return read;
	}

	@Override
	protected int correct(int currentOff) {
		return currentOff;
	}

	/**
	 * The Simplified form of every character of the Basic Multilingual Plane,
	 * read once by ICU and kept as a table so that rewriting a value costs one
	 * array lookup per character. A character that no rule rewrites, and one
	 * whose Simplified form is a different number of {@code char}s, maps to
	 * itself.
	 */
	private static final class Table {
		static final char[] SIMPLIFIED = build();

		private static char[] build() {
			var table = new char[Character.MIN_SUPPLEMENTARY_CODE_POINT];
			for(var c = 0; c < table.length; c++) {
				table[c] = (char) c;
			}

			var transliterator = Transliterator.getInstance("Traditional-Simplified");
			for(var range : transliterator.getSourceSet().ranges()) {
				for(var cp = range.codepoint; cp <= range.codepointEnd; cp++) {
					if(cp >= table.length) {
						continue;
					}

					var simplified = transliterator.transliterate(
						String.valueOf((char) cp)
					);
					if(simplified.length() == 1) {
						table[cp] = simplified.charAt(0);
					}
				}
			}

			return table;
		}
	}
}
