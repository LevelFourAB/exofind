package se.l4.exofind.engine.index.locales;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/**
 * Regularizes the characters Ukrainian text writes in more than one way, so
 * that they compare as one term.
 *
 * The apostrophe inside words like {@code п'ять} is typed as {@code '},
 * {@code ’} or {@code ʼ} depending on the keyboard; the stemming dictionary
 * holds one of them, so the others are folded onto it. A combining accent is
 * dropped outright - it marks stress, which spelling does not carry. Unicode
 * normalization does neither, which is why this is a filter of its own rather
 * than part of the NFKC step.
 */
final class UkrainianNormalizeFilter extends TokenFilter {
	private final CharTermAttribute term = addAttribute(CharTermAttribute.class);

	UkrainianNormalizeFilter(TokenStream input) {
		super(input);
	}

	@Override
	public boolean incrementToken() throws IOException {
		if(!input.incrementToken()) {
			return false;
		}

		var buffer = term.buffer();
		var length = term.length();
		var out = 0;

		for(var i = 0; i < length; i++) {
			var c = buffer[i];
			switch(c) {
				// Right single quotation mark and modifier letter apostrophe
				case '\u2019', '\u02BC' -> buffer[out++] = '\'';
				// Combining acute accent, dropped - see above
				case '\u0301' -> {
				}
				default -> buffer[out++] = c;
			}
		}

		if(out != length) {
			term.setLength(out);
		}

		return true;
	}
}
