package se.l4.exofind.engine.index.settings;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.schema.RankingConfig;

public class LocalSearchSettingsStorageTest {
	@TempDir
	Path directory;

	Path settingsDirectory;
	LocalSearchSettingsStorage storage;

	@BeforeEach
	void setup() {
		settingsDirectory = directory.resolve("settings");
		storage = new LocalSearchSettingsStorage(settingsDirectory);
	}

	private static SearchSettingsStore storeWith(String tieBreakerField) {
		return SearchSettingsStore.newBuilder()
			.setRanking(RankingConfig.newBuilder()
				.addTieBreakers(RankingConfig.TieBreaker.newBuilder()
					.setField(tieBreakerField)))
			.build();
	}

	/**
	 * An index that never had settings has no file, which is not the same as
	 * one whose settings could not be read.
	 */
	@Test
	void testReadingBeforeAnythingIsWritten() throws Exception {
		assertThat(
			storage.read("books", null),
			instanceOf(SearchSettingsStorage.Read.Absent.class)
		);
	}

	@Test
	void testStoreIsAvailable() {
		assertThat(storage.isAvailable(), is(true));
	}

	@Test
	void testWriteThenRead() throws Exception {
		var version = storage.write("books", storeWith("sales"), null);
		assertThat(version, not(nullValue()));

		var read = storage.read("books", null);
		assertThat(read, instanceOf(SearchSettingsStorage.Read.Loaded.class));

		var loaded = (SearchSettingsStorage.Read.Loaded) read;
		assertThat(
			loaded.settings().getRanking().getTieBreakers(0).getField(),
			is("sales")
		);
		assertThat(loaded.version(), is(version));
	}

	/**
	 * A reader holding the current version is told nothing changed rather than
	 * handed the same settings again, which is what lets a node re-read on an
	 * interval cheaply.
	 */
	@Test
	void testReadWithCurrentVersionIsUnchanged() throws Exception {
		var version = storage.write("books", storeWith("sales"), null);

		assertThat(
			storage.read("books", version),
			instanceOf(SearchSettingsStorage.Read.Unchanged.class)
		);
	}

	@Test
	void testReadWithStaleVersionLoads() throws Exception {
		var first = storage.write("books", storeWith("sales"), null);
		storage.write("books", storeWith("published"), first);

		assertThat(
			storage.read("books", first),
			instanceOf(SearchSettingsStorage.Read.Loaded.class)
		);
	}

	/**
	 * The first write demands that there is nothing there, so it is refused
	 * once something is.
	 */
	@Test
	void testFirstWriteIsRefusedWhenSettingsExist() throws Exception {
		storage.write("books", storeWith("sales"), null);

		assertThat(storage.write("books", storeWith("published"), null), is(nullValue()));
	}

	@Test
	void testWriteIsRefusedOnAStaleVersion() throws Exception {
		var first = storage.write("books", storeWith("sales"), null);
		storage.write("books", storeWith("published"), first);

		assertThat(storage.write("books", storeWith("rating"), first), is(nullValue()));
	}

	/**
	 * Each index has its own file, so the settings of one say nothing about
	 * another's.
	 */
	@Test
	void testIndexesAreKeptApart() throws Exception {
		storage.write("books", storeWith("sales"), null);

		assertThat(
			storage.read("games", null),
			instanceOf(SearchSettingsStorage.Read.Absent.class)
		);
		assertThat(storage.write("games", storeWith("published"), null), not(nullValue()));
	}

	/**
	 * The version is taken from what the file holds rather than remembered, so
	 * a second store over the same directory sees writes made through the
	 * first.
	 */
	@Test
	void testASecondStoreSeesTheFirstStoresWrites() throws Exception {
		var other = new LocalSearchSettingsStorage(settingsDirectory);

		var version = storage.write("books", storeWith("sales"), null);

		assertThat(
			other.read("books", null),
			instanceOf(SearchSettingsStorage.Read.Loaded.class)
		);
		assertThat(other.write("books", storeWith("published"), null), is(nullValue()));
		assertThat(other.write("books", storeWith("published"), version), not(nullValue()));
	}

	@Test
	void testDeleteRemovesTheSettings() throws Exception {
		storage.write("books", storeWith("sales"), null);
		storage.delete("books");

		assertThat(
			storage.read("books", null),
			instanceOf(SearchSettingsStorage.Read.Absent.class)
		);
		assertThat(storage.write("books", storeWith("published"), null), not(nullValue()));
	}

	/**
	 * Removing what is not there changes nothing, so the call can be repeated.
	 */
	@Test
	void testDeleteOfNothingIsAllowed() throws Exception {
		storage.delete("books");
	}

	/**
	 * A write lands whole or not at all, so an interrupted one can never leave
	 * settings that cannot be parsed.
	 */
	@Test
	void testNoTemporaryFileIsLeftBehind() throws Exception {
		storage.write("books", storeWith("sales"), null);

		try(var contents = Files.list(settingsDirectory)) {
			assertThat(
				contents.map(p -> p.getFileName().toString()).toList(),
				contains("books.ef.bin")
			);
		}
	}
}
