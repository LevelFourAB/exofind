package se.l4.exofind.engine.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LocalKeyStorageTest {
	@TempDir
	Path directory;

	Path file;
	LocalKeyStorage storage;

	@BeforeEach
	void setup() {
		file = directory.resolve("keys.ef.bin");
		storage = new LocalKeyStorage(file);
	}

	private static KeyStore storeWith(String... ids) {
		var builder = KeyStore.newBuilder();
		for(var id : ids) {
			builder.addKeys(KeyDef.newBuilder().setId(id));
		}

		return builder.build();
	}

	/**
	 * A deployment that has never created a key has no file, which is not the
	 * same as one whose keys could not be read.
	 */
	@Test
	void testReadingBeforeAnythingIsWritten() throws Exception {
		assertThat(storage.read(null), instanceOf(KeyStorage.Read.Absent.class));
	}

	/**
	 * There is a store and it works, unlike the node that has nowhere to keep
	 * keys at all.
	 */
	@Test
	void testStoreIsAvailable() {
		assertThat(storage.isAvailable(), is(true));
	}

	@Test
	void testWriteThenRead() throws Exception {
		var version = storage.write(storeWith("a"), null);
		assertThat(version, not(nullValue()));

		var read = storage.read(null);
		assertThat(read, instanceOf(KeyStorage.Read.Loaded.class));

		var loaded = (KeyStorage.Read.Loaded) read;
		assertThat(
			loaded.keys().getKeysList().stream().map(KeyDef::getId).toList(),
			contains("a")
		);
		assertThat(loaded.version(), is(version));
	}

	/**
	 * A reader holding the current version is told nothing changed rather than
	 * handed the same keys again, which is what lets a node re-read on an
	 * interval cheaply.
	 */
	@Test
	void testReadWithCurrentVersionIsUnchanged() throws Exception {
		var version = storage.write(storeWith("a"), null);

		assertThat(storage.read(version), instanceOf(KeyStorage.Read.Unchanged.class));
	}

	@Test
	void testReadWithStaleVersionLoads() throws Exception {
		var first = storage.write(storeWith("a"), null);
		storage.write(storeWith("a", "b"), first);

		assertThat(storage.read(first), instanceOf(KeyStorage.Read.Loaded.class));
	}

	/**
	 * The first write demands that there is nothing there, so it is refused
	 * once something is.
	 */
	@Test
	void testFirstWriteIsRefusedWhenTheStoreExists() throws Exception {
		storage.write(storeWith("a"), null);

		assertThat(storage.write(storeWith("b"), null), is(nullValue()));
	}

	@Test
	void testWriteIsRefusedOnAStaleVersion() throws Exception {
		var first = storage.write(storeWith("a"), null);
		storage.write(storeWith("a", "b"), first);

		assertThat(storage.write(storeWith("c"), first), is(nullValue()));
	}

	/**
	 * The version is taken from what the file holds rather than remembered, so
	 * a second store over the same file sees writes made through the first.
	 */
	@Test
	void testASecondStoreSeesTheFirstStoresWrites() throws Exception {
		var other = new LocalKeyStorage(file);

		var version = storage.write(storeWith("a"), null);

		assertThat(other.read(null), instanceOf(KeyStorage.Read.Loaded.class));
		assertThat(other.write(storeWith("b"), null), is(nullValue()));
		assertThat(other.write(storeWith("b"), version), not(nullValue()));
	}

	/**
	 * Keys are secrets, and a file gets whatever the umask gave it unless it is
	 * told otherwise.
	 */
	@Test
	void testTheFileIsReadableOnlyByItsOwner() throws Exception {
		storage.write(storeWith("a"), null);

		if(Files.getFileAttributeView(file, PosixFileAttributeView.class) == null) {
			// The file system decides who may read on its own
			return;
		}

		assertThat(
			Files.getPosixFilePermissions(file),
			is(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
		);
	}

	/**
	 * A write lands whole or not at all, so an interrupted one can never leave
	 * a store that cannot be parsed.
	 */
	@Test
	void testNoTemporaryFileIsLeftBehind() throws Exception {
		storage.write(storeWith("a"), null);

		try(var contents = Files.list(directory)) {
			assertThat(
				contents.map(p -> p.getFileName().toString()).toList(),
				contains("keys.ef.bin")
			);
		}
	}
}
