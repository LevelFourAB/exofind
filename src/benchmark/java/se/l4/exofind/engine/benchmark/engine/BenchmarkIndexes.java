package se.l4.exofind.engine.benchmark.engine;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.benchmark.corpus.Corpus;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.state.NoopSync;

/**
 * Opens indexes for the benchmarks, and keeps the ones they search on disk so
 * that filling an index is not paid for once per benchmark.
 *
 * <p>An index of a corpus at a size is built once into
 * {@code target/benchmark-indexes} and copied from there for each trial, so
 * every trial starts from the same bytes and none of them can leave the
 * template changed. Deleting that directory is what rebuilds them; it is not
 * keyed on the version of the engine that wrote it.
 *
 * <p>Building is not safe to run twice at once over the same corpus and size.
 */
public final class BenchmarkIndexes {
	private static final Path CACHE = Path.of("target", "benchmark-indexes");
	private static final Path WORK = Path.of("target", "benchmark-work");

	private static final AtomicLong NEXT = new AtomicLong();

	private BenchmarkIndexes() {
	}

	/**
	 * Make a fresh empty directory for an index that lasts as long as one
	 * benchmark, under {@code target} rather than the system temporary
	 * directory so that a run leaves nothing behind a build cannot clean.
	 *
	 * <p>The caller owns it and has to remove it with {@link #delete(Path)}.
	 */
	public static Path workDirectory() throws IOException {
		Files.createDirectories(WORK);

		return Files.createDirectory(
			WORK.resolve(ProcessHandle.current().pid() + "-" + NEXT.incrementAndGet())
		);
	}

	/**
	 * Open the index in a directory, whether or not it holds one already, as
	 * the node that writes it.
	 *
	 * <p>The caller owns the returned index and has to close it.
	 */
	public static Index open(String name, Path directory) throws IOException {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		var index = new Index(nodeState, name, directory, new NoopSync());
		index.pull();
		return index;
	}

	/**
	 * Open an empty index of a corpus in a directory of its own, ready to be
	 * written to.
	 *
	 * <p>The caller owns the returned index and has to close it, and to remove
	 * the directory with {@link #delete(Path)} after.
	 */
	public static Index empty(Corpus corpus, Path directory) throws IOException {
		Files.createDirectories(directory);

		var index = open(corpus.name(), directory);
		index.updateDefinition(corpus.definition());
		return index;
	}

	/**
	 * Get the directory holding a committed index of the first {@code size}
	 * documents of a corpus, building it if it is not there yet.
	 *
	 * <p>Building indexes several hundred thousand documents and takes minutes;
	 * the result is kept for later runs.
	 */
	public static Path template(Corpus corpus, int size) throws IOException {
		var directory = CACHE.resolve(slug(corpus.name()) + "-" + size);
		if(Files.isDirectory(directory)) {
			return directory;
		}

		Files.createDirectories(CACHE);
		var building = CACHE.resolve(directory.getFileName() + ".building");
		delete(building);
		Files.createDirectories(building);

		var index = open(corpus.name(), building);
		try {
			index.updateDefinition(corpus.definition());

			for(var ordinal = 0; ordinal < size; ordinal++) {
				index.addDocument(corpus.document(ordinal));
			}

			index.commit();
		} finally {
			index.close(false);
		}

		try {
			Files.move(building, directory, StandardCopyOption.ATOMIC_MOVE);
		} catch(FileAlreadyExistsException e) {
			delete(building);
		}

		return directory;
	}

	/**
	 * Copy an index directory into a fresh temporary directory, to be opened
	 * and thrown away without the original being touched.
	 */
	public static Path copyOf(Path template) throws IOException {
		var copy = workDirectory();

		try(var files = Files.list(template)) {
			for(var file : files.toList()) {
				Files.copy(file, copy.resolve(file.getFileName()));
			}
		}

		return copy;
	}

	/**
	 * Remove a directory and everything below it. Does nothing when it is not
	 * there.
	 */
	public static void delete(Path directory) throws IOException {
		if(!Files.exists(directory)) {
			return;
		}

		try(var paths = Files.walk(directory)) {
			for(var path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static String slug(String name) {
		return name.replace(':', '-');
	}
}
