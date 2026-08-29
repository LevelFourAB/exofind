package se.l4.exofind.engine.index.locales;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.OffHeapFSTStore;
import org.apache.lucene.util.fst.Outputs;

/**
 * The language data a locale reads from outside the application - the word
 * lists and grammars that are too large, or licensed too differently, to sit
 * in the jar.
 *
 * The files live in a data directory holding one folder per data set, named
 * for the locale that reads it. The {@code exofind.locale-data.directory}
 * system property or the {@code EXOFIND_LOCALE_DATA_DIRECTORY} environment
 * variable names the directory, falling back to {@code locale-data} under the
 * working directory. A file is read plain or gzipped, whichever is present,
 * and the classpath location {@code /locale-data/<name>/} serves as a last
 * resort for data bundled with tests.
 *
 * Each file is a list of lines in UTF-8. Blank lines and lines starting with
 * {@code #} are left out, and what remains is trimmed.
 *
 * Nothing is held between calls - {@link #has(String)} and
 * {@link #read(String, Consumer)} go to the filesystem every time, so the
 * directory can be named before the data is first touched without racing class
 * initialization. Whoever reads a file is what keeps the result.
 *
 * {@link #readFst(String, Outputs)} is the exception: a transducer on disk is
 * answered from the file rather than from the heap, so the {@link Fst} it
 * returns keeps that file open until it is closed.
 *
 * Instances are immutable and safe to use from several threads.
 */
public final class LocaleData {
	private final String name;

	private LocaleData(String name) {
		this.name = name;
	}

	/**
	 * Get the data set of the given name.
	 *
	 * @param name
	 *   name of the folder the files sit in
	 * @return
	 */
	public static LocaleData forName(String name) {
		return new LocaleData(name);
	}

	/**
	 * Get the name of this data set.
	 *
	 * @return
	 */
	public String getName() {
		return name;
	}

	/**
	 * Get if this node has a file of this data set, plain or gzipped.
	 *
	 * @param file
	 *   name of the file, without a {@code .gz} suffix
	 * @return
	 */
	public boolean has(String file) {
		var directory = root().resolve(name);
		return Files.exists(directory.resolve(file + ".gz"))
			|| Files.exists(directory.resolve(file))
			|| LocaleData.class.getResource(classpathResource(file)) != null;
	}

	/**
	 * Read a file of this data set, handing every line that carries something
	 * to the given consumer in the order the file holds them.
	 *
	 * @param file
	 *   name of the file, without a {@code .gz} suffix
	 * @param line
	 * @throws UncheckedIOException
	 *   if the file cannot be read, which includes it not being there - call
	 *   {@link #has(String)} first for data a node is allowed to be without
	 */
	public void read(String file, Consumer<String> line) {
		try(var reader = new BufferedReader(new InputStreamReader(
			open(file),
			StandardCharsets.UTF_8
		), 1 << 16)) {
			String value;
			while((value = reader.readLine()) != null) {
				var trimmed = value.trim();
				if(!trimmed.isEmpty() && !trimmed.startsWith("#")) {
					line.accept(trimmed);
				}
			}
		} catch(IOException e) {
			throw new UncheckedIOException(
				"Unable to read `" + file + "` of locale data `" + name + "`", e
			);
		}
	}

	/**
	 * A transducer of a data set, and whatever it is being read through.
	 *
	 * Closing releases the file. A transducer read from an open file answers
	 * nothing afterwards, so it is closed only once the analysis using it is
	 * finished with.
	 *
	 * @param fst
	 * @param source
	 *   what {@link #close()} releases
	 */
	public record Fst<T>(FST<T> fst, Closeable source) implements Closeable {
		@Override
		public void close() throws IOException {
			source.close();
		}
	}

	/**
	 * Read a file of this data set holding a transducer saved by
	 * {@link FST#save(Path)}, empty when the data set does not have one.
	 *
	 * A file on disk is read through a memory map and costs almost no heap,
	 * so the returned {@link Fst} holds the file open; one on the classpath is
	 * read onto the heap and holds nothing.
	 *
	 * A transducer written by a different version of Lucene than the one
	 * reading it is not readable, and is reported rather than silently
	 * skipped.
	 *
	 * @param file
	 *   name of the file, which is never gzipped
	 * @param outputs
	 *   what the values on the transducer's arcs were written as
	 * @return
	 * @throws UncheckedIOException
	 *   if the file is there but cannot be read as a transducer
	 */
	public <T> Optional<Fst<T>> readFst(String file, Outputs<T> outputs) {
		var directory = root().resolve(name);

		try {
			if(Files.exists(directory.resolve(file))) {
				/*
				 * The directory, the input and the mapping all stay open: the
				 * transducer reads through them for as long as it answers.
				 */
				var mmap = new MMapDirectory(directory);
				try {
					var in = mmap.openInput(file, IOContext.DEFAULT);
					var metadata = FST.readMetadata(in, outputs);
					var fst = FST.fromFSTReader(
						metadata, new OffHeapFSTStore(in, in.getFilePointer(), metadata)
					);

					return Optional.of(new Fst<>(fst, () -> {
						in.close();
						mmap.close();
					}));
				} catch(IOException | RuntimeException e) {
					mmap.close();
					throw e;
				}
			}

			try(var resource = LocaleData.class.getResourceAsStream(classpathResource(file))) {
				if(resource == null) {
					return Optional.empty();
				}

				var bytes = resource.readAllBytes();
				var in = new ByteArrayDataInput(bytes);
				var metadata = FST.readMetadata(in, outputs);
				return Optional.of(new Fst<>(new FST<>(metadata, in), () -> {}));
			}
		} catch(IOException e) {
			throw new UncheckedIOException(
				"Unable to read `" + file + "` of locale data `" + name + "` as a transducer", e
			);
		}
	}

	/**
	 * The directory holding the data sets.
	 */
	private static Path root() {
		var configured = System.getProperty("exofind.locale-data.directory");
		if(configured == null) {
			configured = System.getenv("EXOFIND_LOCALE_DATA_DIRECTORY");
		}
		return Path.of(configured == null ? "locale-data" : configured);
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

		var resource = LocaleData.class.getResourceAsStream(classpathResource(file));
		if(resource == null) {
			throw new IOException("No file `" + file + "` in locale data `" + name + "`");
		}
		return resource;
	}

	private String classpathResource(String file) {
		return "/locale-data/" + name + "/" + file;
	}
}
