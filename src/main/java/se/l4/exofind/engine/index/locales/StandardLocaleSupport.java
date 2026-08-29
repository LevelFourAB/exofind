package se.l4.exofind.engine.index.locales;

import java.util.Locale;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.util.BytesRef;

import com.ibm.icu.text.Collator;
import com.ibm.icu.text.Normalizer2;
import com.ibm.icu.util.ULocale;

/**
 * A {@link LocaleSupport} assembled from the pieces that differ per locale.
 *
 * Every supported locale is one of these, built in {@link Locales}: collation
 * always comes from ICU for the locale's tag, while stopwords, normalization
 * and stemming are handed in because they are what Lucene ships differently
 * per language.
 *
 * An instance is meant to be shared and is safe to use from several threads,
 * which is also the contract for the operators handed in - they are applied
 * once per token stream and the filters they create carry any state.
 */
public final class StandardLocaleSupport implements LocaleSupport {
	private final String tag;
	private final Locale javaLocale;

	/*
	 * Collators carry state while they compare, so one is kept per thread
	 * rather than shared.
	 */
	private final ThreadLocal<Collator> collator;

	private final Supplier<CharArraySet> stopWords;
	private final Supplier<Tokenizer> tokenizer;
	private final UnaryOperator<TokenStream> normalizer;
	private final UnaryOperator<TokenStream> stemmer;
	private final Decompounder decompounder;

	private StandardLocaleSupport(
		String tag,
		Supplier<CharArraySet> stopWords,
		Supplier<Tokenizer> tokenizer,
		UnaryOperator<TokenStream> normalizer,
		UnaryOperator<TokenStream> stemmer,
		Decompounder decompounder
	) {
		this.tag = tag;
		this.javaLocale = Locale.forLanguageTag(tag);
		this.collator = ThreadLocal.withInitial(
			() -> Collator.getInstance(ULocale.forLanguageTag(tag))
		);
		/*
		 * Held behind a supplier because a list read from locale data is only
		 * on disk when the locale is offered at all, and reading it belongs to
		 * the first use of the locale rather than to loading this class.
		 */
		this.stopWords = once(() -> fold(stopWords.get()));
		this.tokenizer = tokenizer;
		this.normalizer = normalizer;
		this.stemmer = stemmer;
		this.decompounder = decompounder;
	}

	/**
	 * Wrap a supplier so that it runs once however many threads ask for the
	 * value.
	 */
	private static <T> Supplier<T> once(Supplier<T> supplier) {
		return new Supplier<>() {
			private volatile T value;

			@Override
			public T get() {
				var current = value;
				if(current != null) {
					return current;
				}

				synchronized(this) {
					if(value == null) {
						value = supplier.get();
					}
					return value;
				}
			}
		};
	}

	/**
	 * Fold the stopword list the way the chain folds tokens before it drops
	 * stopwords. Lucene ships its lists spelled the way the language writes
	 * them, and a spelling that case folding rewrites - the German {@code daß}
	 * becomes {@code dass} - would otherwise never meet its own entry.
	 */
	private static CharArraySet fold(CharArraySet stopWords) {
		var normalizer = Normalizer2.getNFKCCasefoldInstance();

		var folded = new CharArraySet(stopWords.size(), true);
		for(var entry : stopWords) {
			folded.add(normalizer.normalize(new String((char[]) entry)));
		}

		return CharArraySet.unmodifiableSet(folded);
	}

	@Override
	public String getLocale() {
		return tag;
	}

	@Override
	public Locale getJavaLocale() {
		return javaLocale;
	}

	@Override
	public CharArraySet getStopWords() {
		return stopWords.get();
	}

	@Override
	public Tokenizer createTokenizer() {
		return tokenizer == null
			? LocaleSupport.super.createTokenizer()
			: tokenizer.get();
	}

	@Override
	public TokenStream normalize(TokenStream stream) {
		return normalizer == null ? stream : normalizer.apply(stream);
	}

	@Override
	public TokenStream stem(TokenStream stream) {
		return stemmer == null ? stream : stemmer.apply(stream);
	}

	@Override
	public boolean isDecompoundingSupported() {
		return decompounder != null && decompounder.isAvailable();
	}

	@Override
	public TokenStream decompound(TokenStream stream) {
		return decompounder == null ? stream : decompounder.decompound(stream);
	}

	@Override
	public BytesRef getCollationKey(String value) {
		return new BytesRef(collator.get().getCollationKey(value).toByteArray());
	}

	/**
	 * Start building the support for a locale.
	 *
	 * @param tag
	 *   BCP-47 tag
	 * @return
	 */
	public static Builder of(String tag) {
		return new Builder(tag, () -> CharArraySet.EMPTY_SET, null, null, null, null);
	}

	public record Builder(
		String tag,
		Supplier<CharArraySet> stopWords,
		Supplier<Tokenizer> tokenizer,
		UnaryOperator<TokenStream> normalizer,
		UnaryOperator<TokenStream> stemmer,
		Decompounder decompounder
	) {
		/**
		 * Set the words that carry no meaning in this locale.
		 *
		 * @param stopWords
		 * @return
		 */
		public Builder withStopWords(CharArraySet stopWords) {
			return withStopWords(() -> stopWords);
		}

		/**
		 * Set the words that carry no meaning in this locale, read when the
		 * locale is first used rather than now. For a list that comes from
		 * {@link LocaleData} instead of from the jar.
		 *
		 * @param stopWords
		 * @return
		 */
		public Builder withStopWords(Supplier<CharArraySet> stopWords) {
			return new Builder(tag, stopWords, tokenizer, normalizer, stemmer, decompounder);
		}

		/**
		 * Set how this locale's text splits into words, see
		 * {@link LocaleSupport#createTokenizer()}.
		 *
		 * @param tokenizer
		 * @return
		 */
		public Builder withTokenizer(Supplier<Tokenizer> tokenizer) {
			return new Builder(tag, stopWords, tokenizer, normalizer, stemmer, decompounder);
		}

		/**
		 * Set what this locale needs on top of Unicode case folding, see
		 * {@link LocaleSupport#normalize(TokenStream)}.
		 *
		 * @param normalizer
		 * @return
		 */
		public Builder withNormalizer(UnaryOperator<TokenStream> normalizer) {
			return new Builder(tag, stopWords, tokenizer, normalizer, stemmer, decompounder);
		}

		/**
		 * Set how words reduce to a shared root in this locale.
		 *
		 * @param stemmer
		 * @return
		 */
		public Builder withStemmer(UnaryOperator<TokenStream> stemmer) {
			return new Builder(tag, stopWords, tokenizer, normalizer, stemmer, decompounder);
		}

		/**
		 * Set how this locale's compound words split into their parts, see
		 * {@link LocaleSupport#decompound(TokenStream)}.
		 *
		 * @param decompounder
		 * @return
		 */
		public Builder withDecompounder(Decompounder decompounder) {
			return new Builder(tag, stopWords, tokenizer, normalizer, stemmer, decompounder);
		}

		public StandardLocaleSupport build() {
			return new StandardLocaleSupport(
				tag, stopWords, tokenizer, normalizer, stemmer, decompounder
			);
		}
	}
}
