package se.l4.exofind.engine.index.registry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.time.Duration;
import java.util.OptionalLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.settings.InMemorySearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.settings.SearchSettingsStore;
import se.l4.exofind.engine.storage.StorageMode;

public class RegistryHintsTest {
	InMemoryRegistryStorage storage;
	IndexRegistry registry;
	RegistryHints hints;

	@BeforeEach
	void setup() {
		storage = new InMemoryRegistryStorage();
		registry = new IndexRegistry(storage, Duration.ofMinutes(5));
		hints = new RegistryHints(registry, StorageMode.OBJECT);
	}

	@Test
	public void testReportedVersionsReachTheRegistryOnFlush() {
		registry.create("books", "1");

		hints.reportSettings("books", "\"v1\"");
		hints.reportManifest("books", "1", 3);
		hints.flush();

		var entry = registry.get("books").orElseThrow();
		assertThat(entry.settingsVersion(), is("\"v1\""));
		assertThat(entry.manifestVersion("1"), is(OptionalLong.of(3)));
	}

	@Test
	public void testRemovedSettingsAreReportedAsNone() {
		registry.create("books", "1");

		hints.reportSettings("books", null);
		hints.flush();

		assertThat(registry.get("books").orElseThrow().settingsVersion(), is(""));
	}

	/**
	 * Reports gather between flushes, and gathering keeps the largest
	 * manifest version rather than the last - versions only grow, so the
	 * largest is the newest whatever order the reports arrived in.
	 */
	@Test
	public void testGatheredManifestReportsKeepTheLargestVersion() {
		registry.create("books", "1");

		hints.reportManifest("books", "1", 5);
		hints.reportManifest("books", "1", 3);
		hints.flush();

		assertThat(
			registry.get("books").orElseThrow().manifestVersion("1"),
			is(OptionalLong.of(5))
		);
	}

	/**
	 * A flush that could not write keeps its hints for the next one, so a
	 * registry that is briefly contended delays a hint rather than losing it.
	 */
	@Test
	public void testHintsThatCouldNotBeWrittenAreKeptForTheNextFlush() {
		registry.create("books", "1");

		hints.reportSettings("books", "\"v1\"");
		storage.refuseEveryWrite = true;
		hints.flush();

		assertThat(registry.get("books").orElseThrow().settingsVersion(), is(nullValue()));

		storage.refuseEveryWrite = false;
		hints.flush();

		assertThat(registry.get("books").orElseThrow().settingsVersion(), is("\"v1\""));
	}

	/**
	 * A node storing locally is the only node there is, so reports go nowhere
	 * rather than churning the registry for nobody.
	 */
	@Test
	public void testLocalStorageReportsNothing() {
		registry.create("books", "1");

		var local = new RegistryHints(registry, StorageMode.LOCAL);
		local.reportSettings("books", "\"v1\"");
		local.flush();

		assertThat(registry.get("books").orElseThrow().settingsVersion(), is(nullValue()));
	}

	/**
	 * Storing and removing settings is what reports the settings version, so
	 * the other nodes hear of a change without any new call sites having to
	 * remember to say so.
	 */
	@Test
	public void testStoringSettingsReportsTheVersion() {
		registry.create("books", "1");

		var settingsStorage = new InMemorySearchSettingsStorage();
		var settings = new SearchSettings(
			settingsStorage,
			registry,
			hints,
			Duration.ofSeconds(10),
			Duration.ofMinutes(10)
		);

		var stored = settings.put("books", SearchSettingsStore.getDefaultInstance(), null);
		hints.flush();

		assertThat(
			registry.get("books").orElseThrow().settingsVersion(),
			is(stored.version())
		);

		settings.delete("books");
		hints.flush();

		assertThat(registry.get("books").orElseThrow().settingsVersion(), is(""));
	}
}
