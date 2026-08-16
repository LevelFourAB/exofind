package se.l4.exofind.engine.benchmark.corpus;

import java.util.HashSet;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * A vocabulary whose words appear at the frequencies words appear at in real
 * text, so that a term drawn from it is worth searching for.
 *
 * <p>The words are made up rather than taken from a language: they only have to
 * be distinct, stable and unevenly common. Words are ranked, rank {@code 0}
 * being the most common, and {@link #sample(RandomGenerator)} draws from a Zipf
 * distribution over the ranks - so a document holds a few very common words and
 * a long tail of rare ones, and how many documents a query matches follows from
 * the rank of the word it looks for.
 *
 * <p>The same seed always produces the same words in the same order, on any
 * machine. Instances are immutable and safe for concurrent use; the
 * {@link RandomGenerator} passed to a draw is not.
 */
public final class Words {
	/**
	 * How many draws the sampling table holds. Larger makes the tail of the
	 * distribution finer rather than the sampling slower, as a draw is one
	 * lookup whatever the size.
	 */
	private static final int TABLE_SIZE = 1 << 16;

	private static final String[] ONSETS = {
		"b", "br", "c", "ch", "cl", "d", "dr", "f", "fl", "g", "gr", "h", "j",
		"k", "kr", "l", "m", "n", "p", "pl", "pr", "r", "s", "sk", "sl", "sn",
		"sp", "st", "t", "tr", "v", "w", "z"
	};

	private static final String[] NUCLEI = {
		"a", "e", "i", "o", "u", "y", "ai", "au", "ea", "ee", "ei", "ie", "oa",
		"oo", "ou", "ue"
	};

	private static final String[] CODAS = {
		"", "", "b", "ck", "d", "ft", "g", "l", "ld", "lm", "m", "n", "nd",
		"ng", "nt", "p", "r", "rd", "rk", "rn", "s", "sh", "sk", "st", "t",
		"th", "x"
	};

	private final String[] words;
	private final int[] table;

	private Words(String[] words, int[] table) {
		this.words = words;
		this.table = table;
	}

	/**
	 * Build a vocabulary of {@code size} distinct words.
	 *
	 * @param seed
	 *   decides the words and their order, and nothing else
	 * @param size
	 *   how many words the vocabulary holds
	 */
	public static Words of(long seed, int size) {
		if(size < 1) {
			throw new IllegalArgumentException("A vocabulary needs at least one word");
		}

		var random = new SplittableRandom(seed);
		var words = new String[size];
		var seen = new HashSet<String>(size * 2);

		for(var i = 0; i < size; i++) {
			String word;
			do {
				word = word(random);
			} while(!seen.add(word));

			words[i] = word;
		}

		return new Words(words, table(size));
	}

	/**
	 * Get the word of the given rank, {@code 0} being the most common.
	 *
	 * <p>Rank is how a benchmark asks for a term of a known selectivity: a low
	 * rank is in a large share of documents, a high rank in a handful.
	 *
	 * @throws IndexOutOfBoundsException
	 *   if {@code rank} is outside the vocabulary
	 */
	public String byRank(int rank) {
		return words[rank];
	}

	/**
	 * Get the first word at or after a rank that is at least {@code minLength}
	 * characters long, for a benchmark whose term has to be long enough to
	 * carry a typo or a prefix.
	 *
	 * @throws IllegalArgumentException
	 *   if no word from {@code rank} on is that long
	 */
	public String byRankAtLeast(int rank, int minLength) {
		for(var i = rank; i < words.length; i++) {
			if(words[i].length() >= minLength) {
				return words[i];
			}
		}

		throw new IllegalArgumentException(
			"No word of " + minLength + " characters or more at rank " + rank + " or later"
		);
	}

	/**
	 * Get how many words the vocabulary holds.
	 */
	public int size() {
		return words.length;
	}

	/**
	 * Draw one word, the common ones far more often than the rare ones.
	 */
	public String sample(RandomGenerator random) {
		return words[table[random.nextInt(table.length)]];
	}

	/**
	 * Draw {@code count} words joined by spaces, as the text of a field.
	 *
	 * <p>Words may repeat, the way they do in a sentence.
	 */
	public String sentence(RandomGenerator random, int count) {
		var builder = new StringBuilder(count * 8);

		for(var i = 0; i < count; i++) {
			if(i > 0) {
				builder.append(' ');
			}

			builder.append(sample(random));
		}

		return builder.toString();
	}

	/**
	 * Build the lookup a draw indexes into, holding each rank as many times as
	 * its share of a Zipf distribution with exponent one.
	 */
	private static int[] table(int size) {
		var weights = new double[size];
		var total = 0d;
		for(var i = 0; i < size; i++) {
			weights[i] = 1d / (i + 1);
			total += weights[i];
		}

		var table = new int[TABLE_SIZE];
		var rank = 0;
		var filled = 0;
		var carried = 0d;

		for(var i = 0; i < size && filled < TABLE_SIZE; i++) {
			carried += weights[i] / total * TABLE_SIZE;

			while(filled < carried && filled < TABLE_SIZE) {
				table[filled++] = i;
			}

			rank = i;
		}

		// Rounding can leave the last slots unwritten, which would read as rank 0
		while(filled < TABLE_SIZE) {
			table[filled++] = rank;
		}

		return table;
	}

	private static String word(RandomGenerator random) {
		var syllables = 1 + random.nextInt(3);
		var builder = new StringBuilder(syllables * 4);

		for(var i = 0; i < syllables; i++) {
			builder.append(ONSETS[random.nextInt(ONSETS.length)]);
			builder.append(NUCLEI[random.nextInt(NUCLEI.length)]);
			builder.append(CODAS[random.nextInt(CODAS.length)]);
		}

		return builder.toString();
	}
}
