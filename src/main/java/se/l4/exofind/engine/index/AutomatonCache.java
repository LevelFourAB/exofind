package se.l4.exofind.engine.index;

import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automata;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.CompiledAutomaton;
import org.apache.lucene.util.automaton.LevenshteinAutomata;
import org.apache.lucene.util.automaton.Operations;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * Cache over the compiled automata a text search walks term dictionaries
 * with, shared by every index of the node.
 *
 * <p>A word that forgives typos and a word still being typed are each turned
 * into an automaton, and Lucene compiles the automaton into a table before it
 * walks a term dictionary with it. Compiling costs several times what building
 * the automaton did. What the table accepts depends on the word alone, so one
 * table serves every field, every index and every later request that asks for
 * the same word.
 *
 * <p>Both caches are bounded by entry count. The words are text somebody
 * typed, so what is kept has to have a ceiling; past it the entries asked for
 * least often go first. A prefix table holds a state per byte of the prefix
 * and is far smaller than a typo tolerant one, so the prefix cache holds more
 * entries for the same memory.
 *
 * <p>Safe for concurrent use. A word asked for by two threads at once is
 * compiled once, and the second thread waits for that compile only; a word
 * being compiled holds up no other word.
 */
public final class AutomatonCache {
	/**
	 * The most mistakes a typo tolerant automaton forgives.
	 */
	public static final int MAX_EDITS = 2;

	/**
	 * How many typo tolerant automata a cache holds when nothing says
	 * otherwise.
	 */
	public static final int DEFAULT_TYPO_ENTRIES = 1024;

	/**
	 * How many prefix automata a cache holds when nothing says otherwise.
	 */
	public static final int DEFAULT_PREFIX_ENTRIES = 8192;

	private static final AutomatonCache SHARED = sized(
		DEFAULT_TYPO_ENTRIES,
		DEFAULT_PREFIX_ENTRIES
	);

	/**
	 * What a compiled typo tolerant automaton is decided by, and so what one
	 * is kept under.
	 */
	private record TypoKey(
		String text,
		int edits,
		int prefixLength,
		boolean prefix
	) {
	}

	private final Cache<TypoKey, CompiledAutomaton> typo;
	private final Cache<BytesRef, CompiledAutomaton> prefix;

	private AutomatonCache(int typoEntries, int prefixEntries) {
		this.typo = Caffeine.newBuilder()
			.maximumSize(typoEntries)
			.recordStats()
			.build();

		this.prefix = Caffeine.newBuilder()
			.maximumSize(prefixEntries)
			.recordStats()
			.build();
	}

	/**
	 * Get a cache holding at most the given number of automata of each kind.
	 *
	 * @param typoEntries
	 *   how many typo tolerant automata to keep, at least zero
	 * @param prefixEntries
	 *   how many prefix automata to keep, at least zero
	 * @throws IllegalArgumentException
	 *   if either count is negative
	 */
	public static AutomatonCache sized(int typoEntries, int prefixEntries) {
		if(typoEntries < 0 || prefixEntries < 0) {
			throw new IllegalArgumentException(
				"An automaton cache can not hold a negative number of entries"
			);
		}

		return new AutomatonCache(typoEntries, prefixEntries);
	}

	/**
	 * Get the cache of default size that queries compiled outside a node
	 * share, such as those of a test or a benchmark. A node builds its own
	 * through {@link SearchCaches} and never reads this one.
	 */
	public static AutomatonCache shared() {
		return SHARED;
	}

	/**
	 * Get the automaton accepting every term within the given number of
	 * mistakes of a word, compiling it on the first ask.
	 *
	 * @param text
	 *   the word, already folded the way the dictionary it walks is
	 * @param edits
	 *   how many mistakes to forgive, between zero and {@link #MAX_EDITS}
	 * @param prefixLength
	 *   how many leading code points are matched as they stand
	 * @param prefix
	 *   whether the word may still be half typed, so a term is accepted as
	 *   soon as some prefix of it is within the mistakes
	 * @return
	 *   the automaton, safe to share between threads
	 * @throws IllegalArgumentException
	 *   if {@code edits} is outside what a word may forgive
	 */
	public CompiledAutomaton typo(
		String text,
		int edits,
		int prefixLength,
		boolean prefix
	) {
		if(edits < 0 || edits > MAX_EDITS) {
			throw new IllegalArgumentException(
				"A word forgives between 0 and " + MAX_EDITS + " mistakes"
			);
		}

		return typo.get(
			new TypoKey(text, edits, prefixLength, prefix),
			AutomatonCache::compileTypo
		);
	}

	/**
	 * Get the automaton accepting every term that starts with the given
	 * bytes, compiling it on the first ask.
	 *
	 * <p>The bytes are only read here, and what is kept is a copy of them, so
	 * a caller may hand over a window onto a buffer it goes on writing into.
	 *
	 * @return
	 *   the automaton, safe to share between threads
	 */
	public CompiledAutomaton prefix(BytesRef bytes) {
		return prefix.get(BytesRef.deepCopyOf(bytes), AutomatonCache::compilePrefix);
	}

	/**
	 * Get how the typo tolerant automata have been answered so far - hits,
	 * misses, evictions.
	 */
	public CacheStats typoStats() {
		return typo.stats();
	}

	/**
	 * Get how the prefix automata have been answered so far - hits, misses,
	 * evictions.
	 */
	public CacheStats prefixStats() {
		return prefix.stats();
	}

	/**
	 * Compile the table for one reading of a word.
	 *
	 * The Levenshtein automaton of the word accepts a term close enough to it;
	 * a half typed word has "anything after" concatenated onto that, so a term
	 * is accepted as soon as some prefix of it is close enough. The leading
	 * characters the definition wants matched exactly are kept out of the fuzzy
	 * part and counted in code points, so a word of characters outside the
	 * basic plane keeps as much of itself fixed as one of ASCII.
	 *
	 * Whether the automaton accepts finitely many terms is told rather than
	 * left to be found out. A word with an end is near finitely many others
	 * however many mistakes are forgiven, and a word still being typed stands
	 * for every term some reading of it starts, which is endless. Both follow
	 * from the shape asked for, while Lucene would walk the automaton again to
	 * learn what this already knows.
	 */
	private static CompiledAutomaton compileTypo(TypoKey reading) {
		var text = reading.text();

		var codePoints = text.codePointCount(0, text.length());
		var prefixEnd = text.offsetByCodePoints(0, Math.min(reading.prefixLength(), codePoints));

		var automaton = levenshtein(text, prefixEnd, reading.edits(), reading.prefix());

		return new CompiledAutomaton(
			Operations.determinize(automaton, Operations.DEFAULT_DETERMINIZE_WORK_LIMIT),
			!reading.prefix(),
			true,
			false
		);
	}

	/**
	 * The automaton accepting every term within the given number of edits of
	 * the word - or, when the word may still be half typed, every term some
	 * such reading of it starts.
	 */
	private static Automaton levenshtein(
		String text,
		int prefixEnd,
		int edits,
		boolean prefix
	) {
		var automaton = new LevenshteinAutomata(text.substring(prefixEnd), true)
			.toAutomaton(edits, text.substring(0, prefixEnd));

		if(prefix) {
			automaton = Operations.concatenate(automaton, Automata.makeAnyString());
		}

		return automaton;
	}

	/**
	 * Compile the table for one prefix.
	 *
	 * The automaton is the one {@link PrefixQuery} builds - the prefix as a
	 * chain of bytes with anything at all after it - and it is compiled the way
	 * {@link org.apache.lucene.search.AutomatonQuery} compiles the automata it
	 * is handed: as bytes rather than code points, and as endless, which a
	 * prefix always is because anything may follow it.
	 */
	private static CompiledAutomaton compilePrefix(BytesRef prefix) {
		return new CompiledAutomaton(
			PrefixQuery.toAutomaton(prefix),
			false,
			true,
			true
		);
	}
}
