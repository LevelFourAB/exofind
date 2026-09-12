package se.l4.exofind.engine.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

import se.l4.exofind.engine.storage.DurableFiles;

/**
 * KeyStorage for a node keeping everything on its own disk, held as a file
 * beside the local copies of the indexes.
 *
 * <p>Without a remote this node is the only one there is, so the conditional
 * write only has to hold against itself. The version is taken from the
 * contents of the file rather than kept in memory, so two stores opened over
 * the same file still see each other's writes.
 *
 * <p>The file is created so that only the user running the node can read it.
 * A bucket has access rules of its own deciding who may read the keys; a file
 * has whatever the umask gave it, which on a shared host is often everyone.
 */
public class LocalKeyStorage implements KeyStorage {
	private final Path file;
	private final ReentrantLock lock;

	public LocalKeyStorage(Path file) {
		this.file = file;
		this.lock = new ReentrantLock();
	}

	@Override
	public boolean isAvailable() {
		return true;
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

			return new Read.Loaded(KeyStore.parseFrom(contents), version);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public String write(KeyStore keys, String expectedVersion) throws IOException {
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

			var contents = keys.toByteArray();

			Files.createDirectories(file.getParent());
			DurableFiles.replaceOwnerOnly(file, contents);

			return versionOf(contents);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * The version of a stored key store, which only has to tell one set of
	 * contents from another.
	 */
	private static String versionOf(byte[] contents) {
		var crc = new CRC32C();
		crc.update(contents);

		return "\"" + Long.toHexString(crc.getValue()) + "-" + contents.length + "\"";
	}
}
