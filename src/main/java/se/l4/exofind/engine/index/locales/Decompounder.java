package se.l4.exofind.engine.index.locales;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.util.fst.NoOutputs;

import se.l4.exofind.engine.index.decompound.DecompoundTokenFilter;
import se.l4.exofind.engine.index.decompound.Hyphenator;

/**
 * Splits the compound words of one language into their parts, so that a search
 * for a part finds the compounds built from it.
 *
 * A word is split where the language's hyphenation patterns allow and a part is
 * only kept when the word list knows it, which is what keeps arbitrary
 * substrings out of the index. The whole token always stays alongside the
 * parts.
 *
 * The hyphenation grammar is {@code patterns.txt} of a {@link LocaleData} data
 * set and the word list {@code words.fst}, a transducer read through a memory
 * map. A node without both reports {@link #isAvailable()} false and passes
 * streams through unchanged, so a locale can name its data before the data is
 * installed.
 *
 * Loading happens on first use and what was loaded is held until the node
 * stops, the file the transducer reads through included. An instance is safe
 * to use from several threads.
 */
public final class Decompounder {
	/**
	 * Words shorter than this are never split.
	 */
	private static final int MIN_WORD_SIZE = 5;

	/**
	 * The shortest and longest part that is kept. Three keeps the short roots
	 * the Nordic languages compound with, such as `hus` and `bil`.
	 */
	private static final int MIN_PART_SIZE = 3;
	private static final int MAX_PART_SIZE = 30;

	/*
	 * One instance per data set rather than per locale, because Norwegian's
	 * `no` and `nb` read the same Bokmål files and should share what is loaded
	 * from them.
	 */
	private static final ConcurrentHashMap<String, Decompounder> INSTANCES = new ConcurrentHashMap<>();

	private final LocaleData source;
	private volatile Data data;

	private record Data(Hyphenator hyphenator, LocaleData.Fst<Object> words) {
	}

	private Decompounder(LocaleData source) {
		this.source = source;
	}

	/**
	 * Get the decompounder reading the data set of the given name.
	 *
	 * @param name
	 *   name of the data set, see {@link LocaleData}
	 * @return
	 */
	public static Decompounder forData(String name) {
		return INSTANCES.computeIfAbsent(name, key -> new Decompounder(LocaleData.forName(key)));
	}

	/**
	 * Get if this node has the data of this decompounder.
	 *
	 * @return
	 */
	public boolean isAvailable() {
		return source.has("patterns.txt") && source.has("words.fst");
	}

	/**
	 * Wrap a token stream so that compound words come out followed by their
	 * parts, at the same position. Streams pass through unchanged when
	 * {@link #isAvailable()} is false.
	 *
	 * @param stream
	 * @return
	 */
	public TokenStream decompound(TokenStream stream) {
		if(!isAvailable()) {
			return stream;
		}

		var data = load();

		return new DecompoundTokenFilter(
			stream,
			data.hyphenator(),
			data.words().fst(),
			MIN_WORD_SIZE,
			MIN_PART_SIZE,
			MAX_PART_SIZE
		);
	}

	private Data load() {
		var loaded = data;
		if(loaded != null) {
			return loaded;
		}

		synchronized(this) {
			if(data == null) {
				data = new Data(
					new Hyphenator(loadPatterns()),
					source.readFst("words.fst", NoOutputs.getSingleton()).orElseThrow(
						() -> new IllegalStateException(
							"No `words.fst` in locale data `" + source.getName() + "`"
						)
					)
				);
			}
			return data;
		}
	}

	private List<String> loadPatterns() {
		var patterns = new ArrayList<String>();
		source.read("patterns.txt", patterns::add);
		return patterns;
	}
}
