package se.l4.exofind.engine.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

/**
 * Exclusive claim on the local storage directory, held for as long as the node
 * runs.
 *
 * <p>The directory holds Lucene indexes, which one writer at a time may touch.
 * Nothing else stops two nodes pointed at the same directory - the epochs and
 * conditional writes that fence a second writer live in the object storage and
 * say nothing about a directory two processes share, and in
 * {@link StorageMode#LOCAL} there is no storage to fence through at all. Both
 * would open the same Lucene directory and write over each other's commits.
 *
 * <p>So the second node is refused where the mistake is made rather than
 * discovered later as a corrupt index. This is the local counterpart of the
 * conditional write check an indexer makes against the object storage: the
 * property being relied upon is verified at startup instead of assumed.
 *
 * <p>The lock is taken by the operating system on behalf of the process and is
 * released when the process ends, however it ends, so a node that was killed
 * leaves nothing to clean up by hand. It is only as good as the file system
 * underneath: NFS and SMB implement locking unreliably, which is one reason
 * the local storage directory belongs on a disk attached to the node.
 */
@Startup
@Singleton
public class StorageDirectoryLock {
	/**
	 * File the lock is held on. Its contents are never read - the lock is on
	 * the file rather than in it.
	 */
	private static final String LOCK_FILE = "node.lock";

	private final Path file;
	private final FileChannel channel;
	private final FileLock lock;

	StorageDirectoryLock(
		@ConfigProperty(name = "exofind.storage.local.directory") Path directory
	) throws IOException {
		this.file = directory.resolve(LOCK_FILE);

		Files.createDirectories(directory);

		this.channel = FileChannel.open(
			file,
			StandardOpenOption.CREATE,
			StandardOpenOption.WRITE
		);

		FileLock acquired;
		try {
			acquired = channel.tryLock();
		} catch(OverlappingFileLockException e) {
			// Another node inside this same JVM already holds it
			acquired = null;
		} catch(IOException e) {
			channel.close();

			throw new IOException(
				"Unable to claim the local storage directory " + directory
					+ "; " + e.getMessage(),
				e
			);
		}

		if(acquired == null) {
			channel.close();

			throw new IOException(
				"The local storage directory " + directory + " is already in use by"
					+ " another node. Two nodes writing one directory overwrite each"
					+ " other's index commits, so give this node a directory of its"
					+ " own through EXOFIND_STORAGE_LOCAL_DIRECTORY"
			);
		}

		this.lock = acquired;
	}

	@PreDestroy
	void release() throws IOException {
		try {
			lock.release();
		} finally {
			channel.close();
		}
	}

	/**
	 * The file the claim is held on, for a caller that wants to say where it
	 * is.
	 */
	public Path file() {
		return file;
	}
}
