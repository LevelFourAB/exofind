package se.l4.exofind.engine.index.locales;

import java.util.Locale;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.icu.segmentation.ICUTokenizer;
import org.apache.lucene.util.BytesRef;

/**
 * LocaleSupport provides the locale specific pieces of indexing and searching.
 *
 * Analysis chains are assembled by
 * {@link se.l4.exofind.engine.index.analysis.Analyzers}, which is the same for
 * every locale; what differs per locale - how case folds, which words carry no
 * meaning, how words reduce to a shared root, how values order - is what lives
 * here.
 */
public interface LocaleSupport {
	/**
	 * Get the locale that this support is for, as a BCP-47 tag.
	 *
	 * @return
	 */
	String getLocale();

	/**
	 * Get this locale as the JDK knows it, for the platform APIs that take
	 * one.
	 *
	 * @return
	 */
	Locale getJavaLocale();

	/**
	 * Get the words that appear too often in this locale to tell documents
	 * apart, such as `the` and `and` in English.
	 *
	 * @return
	 */
	CharArraySet getStopWords();

	/**
	 * Create the tokenizer that splits this locale's text into words, used
	 * when a chain does not pick one itself.
	 *
	 * Segmenting on the rules of Unicode is right for nearly every locale.
	 * The ones that override this are those whose words Unicode alone cannot
	 * find - Chinese, Japanese and Korean, where a dictionary or a
	 * morphological model decides where a word ends.
	 *
	 * A tokenizer carries the stream it reads, so a new one is created per
	 * call rather than shared.
	 *
	 * @return
	 */
	default Tokenizer createTokenizer() {
		return new ICUTokenizer();
	}

	/**
	 * Wrap a token stream with what this locale needs on top of Unicode case
	 * folding for two spellings of a word to become the same term.
	 *
	 * Runs as part of the normalize component of a chain, before the Unicode
	 * normalization and before stopwords are dropped. Most locales need
	 * nothing; the ones that do are the ones Unicode alone gets wrong - a
	 * dotless `ı` that plain case folding would merge with `i`, an accent
	 * that the locale's stopword list is written without, an elided article
	 * glued onto the front of a word.
	 *
	 * @param stream
	 * @return
	 */
	default TokenStream normalize(TokenStream stream) {
		return stream;
	}

	/**
	 * Wrap a token stream so that words are reduced to a shared root the way
	 * this locale spells its forms.
	 *
	 * @param stream
	 * @return
	 */
	TokenStream stem(TokenStream stream);

	/**
	 * Get if this locale splits compound words, which takes both a locale
	 * that glues words together and this build carrying the data that splits
	 * them.
	 *
	 * @return
	 */
	default boolean isDecompoundingSupported() {
		return false;
	}

	/**
	 * Wrap a token stream so that compound words come out followed by their
	 * parts, at the same position, so a search for a part finds the compounds
	 * built from it. Streams pass through unchanged when
	 * {@link #isDecompoundingSupported()} is false.
	 *
	 * @param stream
	 * @return
	 */
	default TokenStream decompound(TokenStream stream) {
		return stream;
	}

	/**
	 * Get the key that orders a value the way a reader of this locale expects.
	 *
	 * Comparing the bytes of a string puts `å` after `z` and `é` after `z`,
	 * which is wrong in most languages that use them. Comparing collation keys
	 * instead gives the order of the locale. The key is only good for ordering,
	 * the original value can not be read back out of it.
	 *
	 * @param value
	 * @return
	 */
	BytesRef getCollationKey(String value);
}
