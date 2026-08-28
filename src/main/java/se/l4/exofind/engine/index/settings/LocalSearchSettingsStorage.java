package se.l4.exofind.engine.index.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

/**
 * SearchSettingsStorage for a node keeping everything on its own disk, held as
 * one file per index beside the local copies of the indexes.
 *
 * <p>Without a remote this node is the only one there is, so the conditional
 * write only has to hold against itself. The version is taken from the
 * contents of the file rather than kept in memory, so two stores opened over
 * the same directory still see each other's writes.
 *
 * <p>The files keep whatever the umask gives them: unlike keys, settings hold
 * nothing secret - only how searches of an index are answered.
 */
public class LocalSearchSettingsStorage implements SearchSettingsStorage {
	/**
	 * Name settings are written under before being moved into place, so an
	 * interrupted write can never leave truncated settings behind.
	 */
	private static final String TEMP_SUFFIX = ".tmp";

	private static final String FILE_SUFFIX = ".ef.bin";

	private final Path directory;
	private final ReentrantLock lock;

	public LocalSearchSettingsStorage(Path directory) {
		this.directory = directory;
		this.lock = new ReentrantLock();
	}

	@Override
	public boolean isAvailable() {
		return true;
	}

	private Path fileOf(String index) {
		return directory.resolve(index + FILE_SUFFIX);
	}

	@Override
	public Read read(String index, String knownVersion) throws IOException {
		lock.lock();
		try {
			byte[] contents;
			try {
				contents = Files.readAllBytes(fileOf(index));
			} catch(NoSuchFileException e) {
				return new Read.Absent();
			}

			var version = versionOf(contents);
			if(version.equals(knownVersion)) {
				return new Read.Unchanged();
			}

			return new Read.Loaded(SearchSettingsStore.parseFrom(contents), version);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public String write(String index, SearchSettingsStore settings, String expectedVersion)
		throws IOException
	{
		lock.lock();
		try {
			var file = fileOf(index);

			String current;
			try {
				current = versionOf(Files.readAllBytes(file));
			} catch(NoSuchFileException e) {
				current = null;
			}

			if(expectedVersion == null ? current != null : !expectedVersion.equals(current)) {
				return null;
			}

			var contents = settings.toByteArray();
			var temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);

			Files.createDirectories(directory);
			Files.write(temp, contents);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);

			return versionOf(contents);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void delete(String index) throws IOException {
		lock.lock();
		try {
			Files.deleteIfExists(fileOf(index));
		} finally {
			lock.unlock();
		}
	}

	/**
	 * The version of stored settings, which only has to tell one set of
	 * contents from another.
	 */
	private static String versionOf(byte[] contents) {
		var crc = new CRC32C();
		crc.update(contents);

		return "\"" + Long.toHexString(crc.getValue()) + "-" + contents.length + "\"";
	}
}
