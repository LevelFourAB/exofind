package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.InMemoryRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.registry.RegistryHintsTestSupport;
import se.l4.exofind.engine.index.registry.VersionHint;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.index.state.StateSync;
import se.l4.exofind.engine.index.state.StateSyncProvider;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * How the refresh uses the registry's version hints: an open generation is
 * pulled when the hint says its manifest moved past what this node holds, or
 * says nothing, and skipped - up to the verify interval - when the copy is
 * already at the hinted version.
 */
public class IndexesManifestHintTest {
	@TempDir
	Path storageDirectory;

	/**
	 * A sync that counts its pulls and answers whatever version the test says
	 * the copy is at, standing in for the storage requests being gated.
	 */
	static class CountingSync implements StateSync {
		final AtomicInteger pulls = new AtomicInteger();
		volatile OptionalLong synced = OptionalLong.empty();

		@Override
		public void push(Set<String> files) {
		}

		@Override
		public void claimWriter() {
		}

		@Override
		public boolean pull() {
			pulls.incrementAndGet();
			return false;
		}

		@Override
		public OptionalLong syncedVersion() {
			return synced;
		}

		@Override
		public boolean hasSyncedCommit() {
			return false;
		}

		@Override
		public OptionalInt luceneCreatedMajor() {
			return OptionalInt.empty();
		}
	}

	static class CountingSyncProvider implements StateSyncProvider {
		final ConcurrentHashMap<String, CountingSync> syncs = new ConcurrentHashMap<>();

		@Override
		public StateSync createSync(IndexName generation, Path dataPath) {
			return syncs.computeIfAbsent(generation.toString(), name -> new CountingSync());
		}

		@Override
		public OptionalLong remoteVersion(IndexName generation) {
			return OptionalLong.empty();
		}
	}

	private static NodeState nodeState(boolean indexer) {
		var state = new NodeState(indexer);
		state.updateOwnership(indexer);
		return state;
	}

	/**
	 * @param refreshInterval
	 *   shortest time between two manifest requests for one generation; zero
	 *   for the tests that ask what the hints alone decide
	 */
	private Indexes newNode(
		boolean indexer,
		IndexRegistry registry,
		StateSyncProvider syncProvider,
		Duration refreshInterval,
		Duration verifyInterval
	) throws IOException {
		return new Indexes(
			nodeState(indexer),
			syncProvider,
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			storageDirectory,
			OptionalInt.empty(),
			refreshInterval,
			verifyInterval,
			4,
			Duration.ofMillis(100),
			0,
			Duration.ZERO,
			Optional.empty(),
			Optional.empty(),
			Duration.ofHours(24),
			Duration.ofHours(168),
			Duration.ofHours(1)
		);
	}

	@Test
	public void testHintsDecideWhetherAnOpenGenerationIsPulled() throws Exception {
		var registryStorage = new InMemoryRegistryStorage();
		var registry = new IndexRegistry(registryStorage, Duration.ofMinutes(5));

		var writer = newNode(
			true,
			registry,
			new NoopSyncProvider(),
			Duration.ZERO,
			Duration.ofMinutes(10)
		);
		writer.create("books", IndexDef.getDefaultInstance());
		writer.close();

		var provider = new CountingSyncProvider();
		var reader = newNode(false, registry, provider, Duration.ZERO, Duration.ofMinutes(10));
		try {
			reader.getOrThrow("books");
			var sync = provider.syncs.get("books@1");

			// Nothing is said about the generation yet, so a pass pulls as it always did
			var before = sync.pulls.get();
			reader.refresh();
			assertThat(sync.pulls.get(), is(before + 1));

			// The copy stands where the hint says the remote does, so a pass asks nothing
			registry.updateHints(Lists.immutable.of(new VersionHint.Manifest("books", "1", 4, null)));
			sync.synced = OptionalLong.of(4);
			before = sync.pulls.get();
			reader.refresh();
			assertThat(sync.pulls.get(), is(before));

			// A version past the copy is what a pass acts on
			registry.updateHints(Lists.immutable.of(new VersionHint.Manifest("books", "1", 5, null)));
			before = sync.pulls.get();
			reader.refresh();
			assertThat(sync.pulls.get(), is(before + 1));

			/*
			 * The pull did not reach the hinted version - a failed pull looks
			 * the same - so the next pass tries again rather than trusting
			 * the hint it could not catch up to.
			 */
			before = sync.pulls.get();
			reader.refresh();
			assertThat(sync.pulls.get(), is(before + 1));
		} finally {
			reader.close();
		}
	}

	/**
	 * A hint under the copy says the generation was replaced: a name deleted
	 * and created again writes a first manifest whose version is below the one
	 * the copy of the deleted index reached. Waiting that out would leave the
	 * copy answering with the deleted documents until the verify interval, so
	 * a pass acts on it.
	 */
	@Test
	public void testHintUnderTheCopyIsPulled() throws Exception {
		var registryStorage = new InMemoryRegistryStorage();
		var registry = new IndexRegistry(registryStorage, Duration.ofMinutes(5));

		var writer = newNode(
			true,
			registry,
			new NoopSyncProvider(),
			Duration.ZERO,
			Duration.ofMinutes(10)
		);
		writer.create("books", IndexDef.getDefaultInstance());
		writer.close();

		registry.updateHints(Lists.immutable.of(new VersionHint.Manifest("books", "1", 12, null)));

		var provider = new CountingSyncProvider();
		var reader = newNode(false, registry, provider, Duration.ZERO, Duration.ofMinutes(10));
		try {
			reader.getOrThrow("books");
			var sync = provider.syncs.get("books@1");

			// The copy stands where the hint does, so a pass asks nothing
			sync.synced = OptionalLong.of(12);
			var before = sync.pulls.get();
			reader.refresh();
			assertThat(sync.pulls.get(), is(before));

			// The copy is of the generation that was replaced, and stands over the new one
			sync.synced = OptionalLong.of(40);
			reader.refresh();
			assertThat(sync.pulls.get(), is(before + 1));
		} finally {
			reader.close();
		}
	}

	/**
	 * A hint can be stale, so an unmoved hint only defers the pull - the
	 * verify interval is where the deferral ends.
	 */
	@Test
	public void testVerifyIntervalPullsPastAnUnmovedHint() throws Exception {
		var registryStorage = new InMemoryRegistryStorage();
		var registry = new IndexRegistry(registryStorage, Duration.ofMinutes(5));

		var writer = newNode(
			true,
			registry,
			new NoopSyncProvider(),
			Duration.ZERO,
			Duration.ofMinutes(10)
		);
		writer.create("books", IndexDef.getDefaultInstance());
		writer.close();

		registry.updateHints(Lists.immutable.of(new VersionHint.Manifest("books", "1", 4, null)));

		var provider = new CountingSyncProvider();
		var reader = newNode(false, registry, provider, Duration.ZERO, Duration.ZERO);
		try {
			reader.getOrThrow("books");
			var sync = provider.syncs.get("books@1");
			sync.synced = OptionalLong.of(4);

			var before = sync.pulls.get();
			reader.refresh();
			assertThat(sync.pulls.get(), is(before + 1));
		} finally {
			reader.close();
		}
	}

	/**
	 * The writer of a deleted generation still holds a report when another
	 * node deletes the index and creates it again. The report must not stick
	 * to the new generation: hints only go up, and a reader that holds a copy
	 * of the deleted generation at that version would skip pulling the new
	 * one until the verify interval, answering with deleted documents.
	 */
	@Test
	public void testAHintOfADeletedGenerationDoesNotStopAReaderFromPullingTheNewOne()
		throws Exception
	{
		var registryStorage = new InMemoryRegistryStorage();
		var registry = new IndexRegistry(registryStorage, Duration.ofMinutes(5));

		var writer = newNode(
			true,
			registry,
			new NoopSyncProvider(),
			Duration.ZERO,
			Duration.ofMinutes(10)
		);
		writer.create("books", IndexDef.getDefaultInstance());
		writer.close();

		var deleted = createdAt(registry, "books", "1");
		registry.updateHints(
			Lists.immutable.of(new VersionHint.Manifest("books", "1", 58, deleted))
		);

		var provider = new CountingSyncProvider();
		var reader = newNode(
			false,
			registry,
			provider,
			Duration.ZERO,
			Duration.ofMinutes(10)
		);
		try {
			// The reader holds a copy of the generation at version 58
			reader.getOrThrow("books");
			var sync = provider.syncs.get("books@1");
			sync.synced = OptionalLong.of(58);

			// The old writer has a report of version 58 it has not flushed
			var oldWriter = new RegistryHints(registry, StorageMode.OBJECT);
			oldWriter.reportManifest("books", "1", deleted, 58);

			// Another node deletes the index and creates it again
			registry.remove("books");
			awaitNextMillisecond();
			registry.create("books", "1");
			var created = createdAt(registry, "books", "1");

			// The old writer flushes, then the new writer reports its first push
			RegistryHintsTestSupport.flush(oldWriter);
			var newWriter = new RegistryHints(registry, StorageMode.OBJECT);
			newWriter.reportManifest("books", "1", created, 1);
			RegistryHintsTestSupport.flush(newWriter);

			var before = sync.pulls.get();
			reader.refresh(true);

			assertThat(sync.pulls.get(), is(before + 1));
		} finally {
			reader.close();
		}
	}

	private static Instant createdAt(IndexRegistry registry, String index, String generation) {
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
	 * The refresh interval is the shortest time between two manifest requests
	 * for one generation, so a node polling faster than it - because something
	 * else on the node wants the registry more often - does not pull a
	 * continuously written index every time.
	 */
	@Test
	public void testRefreshIntervalHoldsBackARepeatedPull() throws Exception {
		var registryStorage = new InMemoryRegistryStorage();
		var registry = new IndexRegistry(registryStorage, Duration.ofMinutes(5));

		var writer = newNode(
			true,
			registry,
			new NoopSyncProvider(),
			Duration.ZERO,
			Duration.ofMinutes(10)
		);
		writer.create("books", IndexDef.getDefaultInstance());
		writer.close();

		var provider = new CountingSyncProvider();
		var reader = newNode(
			false,
			registry,
			provider,
			Duration.ofMinutes(5),
			Duration.ofMinutes(10)
		);
		try {
			reader.getOrThrow("books");
			var sync = provider.syncs.get("books@1");

			// The first pass has nothing to hold it back and ties the generation to a time
			reader.refresh();
			var before = sync.pulls.get();

			// Even a hint past the copy waits out the interval
			registry.updateHints(Lists.immutable.of(new VersionHint.Manifest("books", "1", 5, null)));
			reader.refresh();
			reader.refresh();

			assertThat(sync.pulls.get(), is(before));
		} finally {
			reader.close();
		}
	}
}
