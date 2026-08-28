package se.l4.exofind.engine.index.registry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.time.Duration;
import java.util.OptionalLong;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class IndexRegistryHintsTest {
	InMemoryRegistryStorage storage;
	IndexRegistry registry;

	@BeforeEach
	void setup() {
		storage = new InMemoryRegistryStorage();
		registry = new IndexRegistry(storage, Duration.ofMinutes(5));
	}

	private String storedVersion() throws IOException {
		return switch(storage.read(null)) {
			case RegistryStorage.Read.Loaded loaded -> loaded.version();
			default -> null;
		};
	}

	@Test
	public void testSettingsHintIsStoredAndAnswered() {
		registry.create("books", "1");

		var updated = registry.updateHints(Lists.immutable.of(
			new VersionHint.Settings("books", "\"v1\"")
		));

		assertThat(updated, is(true));
		assertThat(registry.get("books").orElseThrow().settingsVersion(), is("\"v1\""));
	}

	@Test
	public void testManifestHintIsStoredAndAnswered() {
		registry.create("books", "1");

		registry.updateHints(Lists.immutable.of(
			new VersionHint.Manifest("books", "1", 4)
		));

		assertThat(
			registry.get("books").orElseThrow().manifestVersion("1"),
			is(OptionalLong.of(4))
		);
	}

	/**
	 * Manifest versions only grow, so a hint arriving out of order - an old
	 * writer reporting late around a handover - cannot lower what a newer
	 * writer reported.
	 */
	@Test
	public void testManifestHintNeverLowersTheStoredVersion() {
		registry.create("books", "1");
		registry.updateHints(Lists.immutable.of(new VersionHint.Manifest("books", "1", 4)));

		registry.updateHints(Lists.immutable.of(new VersionHint.Manifest("books", "1", 2)));

		assertThat(
			registry.get("books").orElseThrow().manifestVersion("1"),
			is(OptionalLong.of(4))
		);
	}

	/**
	 * A hint that says nothing new must not cost a write - the whole point of
	 * the hints is fewer requests, and a no-op write on every report would
	 * hand the cost back.
	 */
	@Test
	public void testHintTheRegistryAlreadyCarriesWritesNothing() throws IOException {
		registry.create("books", "1");
		registry.updateHints(Lists.immutable.of(
			new VersionHint.Settings("books", "\"v1\""),
			new VersionHint.Manifest("books", "1", 3)
		));

		var before = storedVersion();
		var updated = registry.updateHints(Lists.immutable.of(
			new VersionHint.Settings("books", "\"v1\""),
			new VersionHint.Manifest("books", "1", 3)
		));

		assertThat(updated, is(true));
		assertThat(storedVersion(), is(before));
	}

	/**
	 * A hint can outlive what it points at - a report queued while the index
	 * was deleted - and is then passed over rather than resurrecting anything
	 * or failing the batch.
	 */
	@Test
	public void testHintForSomethingUnknownIsPassedOver() throws IOException {
		registry.create("books", "1");

		var before = storedVersion();
		var updated = registry.updateHints(Lists.immutable.of(
			new VersionHint.Settings("movies", "\"v1\""),
			new VersionHint.Manifest("books", "2", 7)
		));

		assertThat(updated, is(true));
		assertThat(storedVersion(), is(before));
		assertThat(registry.get("books").orElseThrow().manifestVersion("2"), is(OptionalLong.empty()));
	}

	/**
	 * Hints are advisory, so losing every race is an answer rather than an
	 * exception - the caller decides whether to try again.
	 */
	@Test
	public void testHintsThatKeepLosingAreReportedNotThrown() {
		registry.create("books", "1");
		storage.refuseEveryWrite = true;

		var updated = registry.updateHints(Lists.immutable.of(
			new VersionHint.Settings("books", "\"v1\"")
		));

		assertThat(updated, is(false));
	}

	/**
	 * The registry operations rebuild entries, and an entry rebuilt without
	 * its hints would put every node back to polling - so each operation has
	 * to carry them.
	 */
	@Test
	public void testRegistryChangesCarryHintsOn() {
		registry.create("books", "1");
		registry.updateHints(Lists.immutable.of(
			new VersionHint.Settings("books", "\"v1\""),
			new VersionHint.Manifest("books", "1", 4)
		));

		registry.addGeneration("books", "2");
		registry.promote("books", "2");
		registry.removeGeneration("books", "1");

		var entry = registry.get("books").orElseThrow();
		assertThat(entry.settingsVersion(), is("\"v1\""));
	}

	/**
	 * A generation the deployment has not written yet carries no hint, and
	 * an index created fresh says nothing about settings that may survive
	 * from an earlier index of the same name.
	 */
	@Test
	public void testNewEntriesSayNothing() {
		registry.create("books", "1");
		registry.addGeneration("books", "2");

		var entry = registry.get("books").orElseThrow();
		assertThat(entry.settingsVersion(), is(nullValue()));
		assertThat(entry.manifestVersion("1"), is(OptionalLong.empty()));
		assertThat(entry.manifestVersion("2"), is(OptionalLong.empty()));
	}
}
