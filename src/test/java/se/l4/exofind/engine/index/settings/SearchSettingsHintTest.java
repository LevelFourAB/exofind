package se.l4.exofind.engine.index.settings;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.time.Duration;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.InMemoryRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.registry.VersionHint;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * How the refresh uses the registry's version hints: the settings object of an
 * index is read when the hint moved or says nothing, and skipped - up to the
 * verify interval - when it stands where it stood at the last read.
 */
public class SearchSettingsHintTest {
	InMemorySearchSettingsStorage storage;
	IndexRegistry registry;
	SearchSettings settings;

	@BeforeEach
	void setup() {
		storage = new InMemorySearchSettingsStorage();
		registry = new IndexRegistry(new InMemoryRegistryStorage(), Duration.ofMinutes(5));
		registry.create("books", "1");

		settings = newSettings(Duration.ofMinutes(10));
	}

	private SearchSettings newSettings(Duration verifyInterval) {
		return new SearchSettings(
			storage,
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			Duration.ofSeconds(10),
			verifyInterval
		);
	}

	private void hint(String version) {
		registry.updateHints(Lists.immutable.of(new VersionHint.Settings("books", version)));
	}

	@Test
	public void testUnmovedHintSkipsTheRead() {
		storage.set("books", SearchSettingsStore.getDefaultInstance());
		var version = settings.get("books").orElseThrow().version();
		hint(version);

		// The first refresh reads once, to tie the copy to the hint it read under
		settings.refresh();
		var reads = storage.reads;

		settings.refresh();
		settings.refresh();

		assertThat(storage.reads, is(reads));
	}

	@Test
	public void testMovedHintIsWhatCausesTheRead() {
		storage.set("books", SearchSettingsStore.getDefaultInstance());
		settings.get("books");
		settings.refresh();

		storage.set("books", SearchSettingsStore.getDefaultInstance());
		var stored = readVersion();
		hint(stored);

		settings.refresh();

		assertThat(settings.get("books").orElseThrow().version(), is(stored));
	}

	/**
	 * An index the registry says nothing about is read every interval, the
	 * way every index was before the hints - a deployment where nothing
	 * reports must stay exactly as current as it was.
	 */
	@Test
	public void testWithoutAHintEveryRefreshReads() {
		storage.set("books", SearchSettingsStore.getDefaultInstance());
		settings.get("books");
		var reads = storage.reads;

		settings.refresh();
		settings.refresh();

		assertThat(storage.reads, is(reads + 2));
	}

	/**
	 * A hint can be stale - lost between a write and its report - so an
	 * unmoved hint only defers the read, it never removes it. The verify
	 * interval is where the deferral ends.
	 */
	@Test
	public void testVerifyIntervalReadsPastAnUnmovedHint() {
		var verifying = newSettings(Duration.ZERO);

		storage.set("books", SearchSettingsStore.getDefaultInstance());
		var version = verifying.get("books").orElseThrow().version();
		hint(version);
		verifying.refresh();
		var reads = storage.reads;

		verifying.refresh();

		assertThat(storage.reads, is(reads + 1));
	}

	private String readVersion() {
		try {
			return switch(storage.read("books", null)) {
				case SearchSettingsStorage.Read.Loaded loaded -> loaded.version();
				default -> null;
			};
		} catch(IOException e) {
			throw new RuntimeException(e);
		}
	}
}
