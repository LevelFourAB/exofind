package se.l4.exofind.engine.storage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class StorageDirectoryLockTest {
	@TempDir
	Path directory;

	@Test
	void testTheDirectoryIsCreatedIfItIsNotThere() throws Exception {
		var missing = directory.resolve("indexes");

		var lock = new StorageDirectoryLock(missing);
		try {
			assertThat(Files.isDirectory(missing), is(true));
		} finally {
			lock.release();
		}
	}

	/**
	 * Two nodes over one directory would open the same Lucene indexes and
	 * write over each other, so the second is refused where the mistake is
	 * made.
	 */
	@Test
	void testASecondClaimIsRefused() throws Exception {
		var lock = new StorageDirectoryLock(directory);
		try {
			var e = assertThrows(
				IOException.class,
				() -> new StorageDirectoryLock(directory)
			);

			assertThat(e.getMessage(), containsString("already in use"));
		} finally {
			lock.release();
		}
	}

	/**
	 * Releasing hands the directory back, so a node restarting into the same
	 * directory is not refused by the run before it.
	 */
	@Test
	void testTheDirectoryCanBeClaimedAgainAfterRelease() throws Exception {
		new StorageDirectoryLock(directory).release();

		new StorageDirectoryLock(directory).release();
	}
}
