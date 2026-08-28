package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.InMemoryRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.registry.RegistryHintsTestSupport;
import se.l4.exofind.engine.index.settings.InMemorySearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettingsStore;
import se.l4.exofind.engine.index.state.NoopSync;
import se.l4.exofind.engine.index.state.StateSync;
import se.l4.exofind.engine.index.state.StateSyncProvider;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * How registry entries that predate the hints get theirs: the index's writer
 * reads what the storage holds for entries the registry says nothing about,
 * and only for those - a filled entry is never read again.
 */
public class RegistryHintBackfillTest {
	IndexRegistry registry;
	InMemorySearchSettingsStorage settingsStorage;
	RegistryHints hints;

	/**
	 * A provider whose remote holds a manifest at version 7 for {@code books@1}
	 * and none for anything else.
	 */
	static class FixedVersions implements StateSyncProvider {
		@Override
		public StateSync createSync(IndexName generation, Path dataPath) {
			return new NoopSync();
		}

		@Override
		public OptionalLong remoteVersion(IndexName generation) {
			return generation.toString().equals("books@1")
				? OptionalLong.of(7)
				: OptionalLong.empty();
		}
	}

	@BeforeEach
	void setup() {
		registry = new IndexRegistry(new InMemoryRegistryStorage(), Duration.ofMinutes(5));
		registry.create("books", "1");
		registry.create("movies", "1");

		settingsStorage = new InMemorySearchSettingsStorage();
		settingsStorage.set("books", SearchSettingsStore.getDefaultInstance());

		hints = new RegistryHints(registry, StorageMode.OBJECT);
	}

	private RegistryHintBackfill newBackfill(NodeState nodeState) {
		return new RegistryHintBackfill(
			registry,
			hints,
			nodeState,
			settingsStorage,
			new FixedVersions(),
			StorageMode.OBJECT,
			Duration.ofMinutes(5)
		);
	}

	@Test
	public void testWriterFillsWhatTheRegistrySaysNothingAbout() {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		registry.refresh();
		newBackfill(nodeState).pass();
		RegistryHintsTestSupport.flush(hints);

		var books = registry.get("books").orElseThrow();
		var movies = registry.get("movies").orElseThrow();

		assertThat(books.settingsVersion(), is(settingsVersionOf("books")));
		assertThat(books.manifestVersion("1"), is(OptionalLong.of(7)));

		// No settings and no manifest are answers too, or they would be probed forever
		assertThat(movies.settingsVersion(), is(""));
		assertThat(movies.manifestVersion("1"), is(OptionalLong.of(0)));
	}

	@Test
	public void testFilledEntriesAreNotReadAgain() {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		registry.refresh();
		var backfill = newBackfill(nodeState);
		backfill.pass();
		RegistryHintsTestSupport.flush(hints);

		var reads = settingsStorage.reads;
		backfill.pass();

		assertThat(settingsStorage.reads, is(reads));
	}

	/**
	 * Filling runs where the index's writer is, so a node that holds nothing
	 * reads nothing - however many candidates there are, each entry is read
	 * once.
	 */
	@Test
	public void testANodeThatWritesNothingFillsNothing() {
		var nodeState = new NodeState(true);

		registry.refresh();
		newBackfill(nodeState).pass();
		RegistryHintsTestSupport.flush(hints);

		assertThat(registry.get("books").orElseThrow().settingsVersion(), is(nullValue()));
		assertThat(settingsStorage.reads, is(0));
	}

	private String settingsVersionOf(String index) {
		try {
			return switch(settingsStorage.read(index, null)) {
				case SearchSettingsStorage.Read.Loaded loaded -> loaded.version();
				default -> null;
			};
		} catch(IOException e) {
			throw new RuntimeException(e);
		}
	}
}
