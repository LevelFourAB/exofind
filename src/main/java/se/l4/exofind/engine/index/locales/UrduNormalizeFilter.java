package se.l4.exofind.engine.index.locales;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/**
 * Regularizes the letters Urdu text writes in more than one way, so that
 * they compare as one term.
 *
 * Urdu writes the Perso-Arabic script, and its keyboards and fonts mix three
 * traditions of encoding the letters it shares with Arabic and Persian. The
 * yeh is typed as the Arabic {@code ي}, the Persian {@code ی} or the dotless
 * {@code ى}; the kaf as the Arabic {@code ك} or the Persian {@code ک}; the
 * heh as the Arabic {@code ه}, the Urdu {@code ہ} or the {@code ة} of an
 * Arabic loan. Each is folded onto the form Urdu's own orthography uses, so
 * the stopword list and the stemmer are written as Urdu is. The yeh barree
 * {@code ے} is a letter of its own in Urdu, unlike in Persian, and is kept.
 *
 * On top of that come the regularizations Arabic needs as well: the alef
 * with a madda or a hamza is written bare as often as not, the tatweel
 * stretches a word without changing it, and the vowel marks and Quranic
 * annotations are written in a few texts and left out of most.
 *
 * {@link #normalize(char[], int)} is the rule itself, so that a word list
 * can be folded the same way when it is read.
 */
final class UrduNormalizeFilter extends TokenFilter {
	private final CharTermAttribute term = addAttribute(CharTermAttribute.class);

	UrduNormalizeFilter(TokenStream input) {
		super(input);
	}

	@Override
	public boolean incrementToken() throws IOException {
		if(!input.incrementToken()) {
			return false;
		}

		var length = normalize(term.buffer(), term.length());
		if(length != term.length()) {
			term.setLength(length);
		}

		return true;
	}

	/**
	 * Normalize a word in place.
	 *
	 * @param buffer
	 *   the characters of the word
	 * @param length
	 *   how many of them the word is
	 * @return
	 *   the length of the normalized word, which is at most {@code length}
	 */
	static int normalize(char[] buffer, int length) {
		var out = 0;

		for(var i = 0; i < length; i++) {
			var c = buffer[i];
			switch(c) {
				// Alef with madda, hamza above, hamza below and wasla
				case 'آ', 'أ', 'إ', 'ٱ' -> buffer[out++] = 'ا';
				// Arabic yeh and dotless yeh, onto the Farsi yeh
				case 'ي', 'ى' -> buffer[out++] = 'ی';
				// Arabic kaf, onto the keheh
				case 'ك' -> buffer[out++] = 'ک';
				// Arabic heh, teh marbuta, and the heh forms with a hamza or yeh, onto the heh goal
				case 'ه', 'ة', 'ۀ', 'ۂ' -> buffer[out++] = 'ہ';
				// Yeh barree with hamza, onto the yeh barree
				case 'ۓ' -> buffer[out++] = 'ے';
				// Tatweel
				case 'ـ' -> {
				}
				default -> {
					if(!isMark(c)) {
						buffer[out++] = c;
					}
				}
			}
		}

		return out;
	}

	/**
	 * The vowel marks and annotations of the Arabic block: the harakat, the
	 * hamza and madda marks, the superscript alef and the Quranic signs.
	 */
	private static boolean isMark(char c) {
		return (c >= 'ؐ' && c <= 'ؚ')
			|| (c >= 'ً' && c <= 'ٟ')
			|| c == 'ٰ'
			|| (c >= 'ۖ' && c <= 'ۜ')
			|| (c >= '۟' && c <= 'ۤ')
			|| (c >= 'ۧ' && c <= 'ۨ')
			|| (c >= '۪' && c <= 'ۭ');
	}
}
