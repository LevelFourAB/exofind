package se.l4.exofind.engine.benchmark.grouping;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import se.l4.exofind.engine.benchmark.engine.BenchmarkIndexes;

/**
 * Keeps a built index per layout and size on disk, so that filling one is not
 * paid for once per benchmark.
 *
 * <p>An index is built into {@code target/benchmark-indexes} the first time it
 * is asked for and opened in place afterwards - nothing here writes to an index
 * that already exists, so several benchmarks may read the same one at once.
 * Deleting the directory is what rebuilds them.
 *
 * <p>Building is not safe to run twice at once over the same layout and size.
 */
public final class Shapes {
	private static final Path CACHE = Path.of("target", "benchmark-indexes");

	private Shapes() {
	}

	/**
	 * Open an index of a size in a shape, building it if it is not there yet.
	 *
	 * <p>Building writes several hundred thousand documents and takes minutes;
	 * the result is kept for later runs.
	 *
	 * <p>The caller owns what comes back and has to close it.
	 */
	public static ShapeIndex open(Shape shape, int size) throws IOException {
		return open(shape, size, true);
	}

	/**
	 * Open an index of a size in a shape, saying whether the searcher keeps what
	 * a narrowing clause matched.
	 */
	public static ShapeIndex open(Shape shape, int size, boolean cached) throws IOException {
		return shape.open(template(shape, size), cached);
	}

	/**
	 * Get the directory holding an index of the first {@code size} products of
	 * the catalogue in a shape, building it if it is not there yet.
	 */
	public static Path template(Shape shape, int size) throws IOException {
		var directory = directory(shape, size);
		if(Files.isDirectory(directory)) {
			return directory;
		}

		Files.createDirectories(CACHE);
		var building = CACHE.resolve(directory.getFileName() + ".building");
		BenchmarkIndexes.delete(building);
		Files.createDirectories(building);

		shape.build(building, new Catalog(), size);

		try {
			Files.move(building, directory, StandardCopyOption.ATOMIC_MOVE);
		} catch(FileAlreadyExistsException e) {
			BenchmarkIndexes.delete(building);
		}

		return directory;
	}

	/**
	 * Get where an index of a size in a shape is kept, whether or not it has
	 * been built. Shapes that read the same index share a directory.
	 */
	public static Path directory(Shape shape, int size) {
		return CACHE.resolve("grouping-" + shape.layout() + "-" + size);
	}
}
