package se.l4.exofind.engine.index.locales;

import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.StemmerOverrideFilter;
import org.apache.lucene.analysis.miscellaneous.StemmerOverrideFilter.StemmerOverrideMap;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.fst.ByteSequenceOutputs;

/**
 * Reduces the words of one language to their dictionary form by looking each
 * one up, for the languages whose inflection no rule of a useful size
 * describes.
 *
 * The lookup is {@code stemming.fst} of a {@link LocaleData} data set, a
 * transducer read through a memory map that answers each word form with the
 * form it is looked up under.
 *
 * A word the lookup does not hold is left as it was written, which is what a
 * word already in its dictionary form needs and what an unknown word gets. A
 * form that several words share is listed once, against one of them.
 *
 * A node without the file reports {@link #isAvailable()} false and passes
 * streams through unchanged. {@link Locales} only offers a locale that stems
 * this way once the data is there, so a definition naming such a locale is
 * refused on a node that would silently leave its words unreduced.
 *
 * Loading happens on first use and what was loaded is held until the node
 * stops, the file the transducer reads through included. An instance is safe
 * to use from several threads.
 */
public final class Lemmatizer {
	/*
	 * One instance per data set, so that locales reading the same file share
	 * what was built from it.
	 */
	private static final ConcurrentHashMap<String, Lemmatizer> INSTANCES = new ConcurrentHashMap<>();

	private final LocaleData source;
	private volatile StemmerOverrideMap lemmas;

	/**
	 * The transducer and the file it reads through, held for as long as the
	 * lookup built from it is.
	 */
	private LocaleData.Fst<BytesRef> open;

	private Lemmatizer(LocaleData source) {
		this.source = source;
	}

	/**
	 * Get the lemmatizer reading the data set of the given name.
	 *
	 * @param name
	 *   name of the data set, see {@link LocaleData}
	 * @return
	 */
	public static Lemmatizer forData(String name) {
		return INSTANCES.computeIfAbsent(name, key -> new Lemmatizer(LocaleData.forName(key)));
	}

	/**
	 * Get if this node has the data of this lemmatizer.
	 *
	 * @return
	 */
	public boolean isAvailable() {
		return source.has("stemming.fst");
	}

	/**
	 * Wrap a token stream so that every word the lookup knows comes out as its
	 * dictionary form. Streams pass through unchanged when
	 * {@link #isAvailable()} is false.
	 *
	 * @param stream
	 * @return
	 */
	public TokenStream stem(TokenStream stream) {
		if(!isAvailable()) {
			return stream;
		}

		return new StemmerOverrideFilter(stream, load());
	}

	private StemmerOverrideMap load() {
		var loaded = lemmas;
		if(loaded != null) {
			return loaded;
		}

		synchronized(this) {
			if(lemmas == null) {
				lemmas = build();
			}
			return lemmas;
		}
	}

	private StemmerOverrideMap build() {
		open = source.readFst("stemming.fst", ByteSequenceOutputs.getSingleton()).orElseThrow(
			() -> new IllegalStateException(
				"No `stemming.fst` in locale data `" + source.getName() + "`"
			)
		);

		return new StemmerOverrideMap(open.fst(), false);
	}
}
