package se.l4.exofind.engine.index.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

/**
 * RegistryStorage for a node keeping everything on its own disk, held as a file
 * beside the indexes.
 *
 * <p>Without a remote this node is the only one there is, so the conditional
 * write only has to hold against itself. The version is taken from the
 * contents of the file rather than kept in memory, so two registries opened
 * over the same file - which is how a test stands one node next to another -
 * still see each other's writes.
 */
public class LocalRegistryStorage implements RegistryStorage {
	/**
	 * Name the registry is written under before being moved into place, so an
	 * interrupted write can never leave a truncated registry behind.
	 */
	private static final String TEMP_SUFFIX = ".tmp";

	private final Path file;
	private final ReentrantLock lock;

	public LocalRegistryStorage(Path file) {
		this.file = file;
		this.lock = new ReentrantLock();
	}

	@Override
	public Read read(String knownVersion) throws IOException {
		lock.lock();
		try {
			byte[] contents;
			try {
				contents = Files.readAllBytes(file);
			} catch(NoSuchFileException e) {
				return new Read.Absent();
			}

			var version = versionOf(contents);
			if(version.equals(knownVersion)) {
				return new Read.Unchanged();
			}

			return new Read.Loaded(IndexRegistryStore.parseFrom(contents), version);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public String write(IndexRegistryStore indexes, String expectedVersion) throws IOException {
		lock.lock();
		try {
			String current;
			try {
				current = versionOf(Files.readAllBytes(file));
			} catch(NoSuchFileException e) {
				current = null;
			}

			if(expectedVersion == null ? current != null : !expectedVersion.equals(current)) {
				return null;
			}

			var contents = indexes.toByteArray();
			var temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);

			Files.createDirectories(file.getParent());
			Files.write(temp, contents);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);

			return versionOf(contents);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * The version of a stored registry, which only has to tell one set of
	 * contents from another.
	 */
	private static String versionOf(byte[] contents) {
		var crc = new CRC32C();
		crc.update(contents);

		return "\"" + Long.toHexString(crc.getValue()) + "-" + contents.length + "\"";
	}
}
