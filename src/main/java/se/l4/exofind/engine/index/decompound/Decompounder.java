package se.l4.exofind.engine.index.decompound;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;

import com.ibm.icu.text.Normalizer2;

/**
 * Splits the compound words of one language into their parts, so that a search
 * for a part finds the compounds built from it.
 *
 * A word is split where the language's hyphenation patterns allow and a part is
 * only kept when the word list knows it, which is what keeps arbitrary
 * substrings out of the index. The whole token always stays alongside the
 * parts.
 *
 * The patterns and the word list live outside the application, in a data
 * directory holding one folder per data set - the {@code exofind.decompound.directory}
 * system property or the {@code EXOFIND_DECOMPOUND_DIRECTORY} environment
 * variable names it, falling back to {@code decompound-data} under the working
 * directory. A file is read plain or gzipped, whichever is present, and the
 * classpath location {@code /decompound/<name>/} serves as a last resort for
 * data bundled with tests. Loading happens on first use and the result is
 * shared from there on; an instance is safe to use from several threads. A
 * node without the files for a name reports {@link #isAvailable()} false and
 * passes streams through unchanged, so a locale can name its data before the
 * data is installed.
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

	private final String name;
	private volatile Data data;

	private record Data(Hyphenator hyphenator, CharArraySet words) {
	}

	private Decompounder(String name) {
		this.name = name;
	}

	/**
	 * Get the decompounder reading the data set of the given name.
	 *
	 * @param name
	 *   name of the data set under {@code /decompound/} on the classpath
	 * @return
	 */
	public static Decompounder forData(String name) {
		return INSTANCES.computeIfAbsent(name, Decompounder::new);
	}

	/**
	 * Get if this node has the data of this decompounder.
	 *
	 * @return
	 */
	public boolean isAvailable() {
		return exists("patterns.txt") && exists("words.txt");
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
			data.words(),
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
				data = new Data(new Hyphenator(loadPatterns()), loadWords());
			}
			return data;
		}
	}

	private List<String> loadPatterns() {
		var patterns = new ArrayList<String>();
		read("patterns.txt", patterns::add);
		return patterns;
	}

	/**
	 * Load the word list, folded the way the chain folds tokens before this
	 * filter sees them - a list spelled the way the language writes, with a
	 * `daß` or a `Fuß`, would otherwise never meet its own entries.
	 */
	private CharArraySet loadWords() {
		var normalizer = Normalizer2.getNFKCCasefoldInstance();
		var words = new CharArraySet(1 << 16, false);
		read("words.txt", word -> words.add(normalizer.normalize(word)));
		return CharArraySet.unmodifiableSet(words);
	}

	private void read(String file, Consumer<String> line) {
		try(var reader = new BufferedReader(new InputStreamReader(
			open(file),
			StandardCharsets.UTF_8
		))) {
			String value;
			while((value = reader.readLine()) != null) {
				var trimmed = value.trim();
				if(!trimmed.isEmpty() && !trimmed.startsWith("#")) {
					line.accept(trimmed);
				}
			}
		} catch(IOException e) {
			throw new UncheckedIOException(
				"Unable to load " + file + " of decompounding data `" + name + "`", e
			);
		}
	}

	/**
	 * The directory holding the data sets. Resolved on every use rather than
	 * held, so the property can be set before the data is first touched
	 * without racing class initialization.
	 */
	private static Path root() {
		var configured = System.getProperty("exofind.decompound.directory");
		if(configured == null) {
			configured = System.getenv("EXOFIND_DECOMPOUND_DIRECTORY");
		}
		return Path.of(configured == null ? "decompound-data" : configured);
	}

	private boolean exists(String file) {
		var directory = root().resolve(name);
		return Files.exists(directory.resolve(file + ".gz"))
			|| Files.exists(directory.resolve(file))
			|| Decompounder.class.getResource(classpathResource(file)) != null;
	}

	private InputStream open(String file) throws IOException {
		var directory = root().resolve(name);

		var gzipped = directory.resolve(file + ".gz");
		if(Files.exists(gzipped)) {
			return new GZIPInputStream(Files.newInputStream(gzipped), 1 << 16);
		}

		var plain = directory.resolve(file);
		if(Files.exists(plain)) {
			return Files.newInputStream(plain);
		}

		return Decompounder.class.getResourceAsStream(classpathResource(file));
	}

	private String classpathResource(String file) {
		return "/decompound/" + name + "/" + file;
	}
}
