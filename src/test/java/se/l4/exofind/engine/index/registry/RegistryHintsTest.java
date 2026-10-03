package se.l4.exofind.engine.index.registry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.time.Duration;
import java.time.Instant;
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
		hints.reportManifest("books", "1", null, 3);
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

		hints.reportManifest("books", "1", null, 5);
		hints.reportManifest("books", "1", null, 3);
		hints.flush();

		assertThat(
			registry.get("books").orElseThrow().manifestVersion("1"),
			is(OptionalLong.of(5))
		);
	}

	/**
	 * The writer of a deleted generation still holds a report when the name
	 * is created again. Its flush must not land on the new generation, or the
	 * lower versions of the new writer are never taken and readers skip its
	 * pulls.
	 */
	@Test
	public void testAReportOfADeletedGenerationDoesNotStickToTheRecreatedOne() {
		registry.create("books", "1");

		var oldWriter = new RegistryHints(registry, StorageMode.OBJECT);
		oldWriter.reportManifest("books", "1", createdAt("books", "1"), 58);

		// The index is deleted and created again before the old writer flushes
		registry.remove("books");
		awaitNextMillisecond();
		registry.create("books", "1");

		oldWriter.flush();
		hints.reportManifest("books", "1", createdAt("books", "1"), 1);
		hints.flush();

		assertThat(
			registry.get("books").orElseThrow().manifestVersion("1"),
			is(OptionalLong.of(1))
		);
	}

	/**
	 * One node writes the deleted generation and then the new one before a
	 * flush. The reports are kept apart, rather than merged into the larger
	 * version of the deleted generation.
	 */
	@Test
	public void testReportsGatheredAcrossARecreateDoNotKeepTheDeletedVersion() {
		registry.create("books", "1");
		hints.reportManifest("books", "1", createdAt("books", "1"), 58);

		registry.remove("books");
		awaitNextMillisecond();
		registry.create("books", "1");
		hints.reportManifest("books", "1", createdAt("books", "1"), 1);

		hints.flush();

		assertThat(
			registry.get("books").orElseThrow().manifestVersion("1"),
			is(OptionalLong.of(1))
		);
	}

	/**
	 * A repair registers the generations it finds with no creation time, so
	 * the registry can not tell which one a report is for. The report is
	 * taken, or the writers that are open would report into nothing until
	 * they open again.
	 */
	@Test
	public void testAReportIsTakenForAGenerationWithNoCreationTime() {
		storage.set(
			IndexRegistryStore.newBuilder()
				.addIndexes(
					IndexEntry.newBuilder()
						.setName("books")
						.addGenerations(GenerationEntry.newBuilder().setName("1"))
						.setLive("1")
				)
				.build()
		);
		registry.refresh();

		hints.reportManifest("books", "1", Instant.ofEpochMilli(1_000), 3);
		hints.flush();

		assertThat(
			registry.get("books").orElseThrow().manifestVersion("1"),
			is(OptionalLong.of(3))
		);
	}

	private Instant createdAt(String index, String generation) {
		return registry.get(index)
			.flatMap(entry -> entry.generation(generation))
			.orElseThrow()
			.createdAt();
	}

	/**
	 * The registry keeps creation times in milliseconds, so a generation
	 * created again within the same one could not be told apart. That takes
	 * a delete and a create inside one millisecond, which only a test does.
	 */
	private static void awaitNextMillisecond() {
		var start = Instant.now().toEpochMilli();
		while(Instant.now().toEpochMilli() == start) {
			Thread.onSpinWait();
		}
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
