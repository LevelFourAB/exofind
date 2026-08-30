package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
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
		public boolean pull() {
			pulls.incrementAndGet();
			return false;
		}

		@Override
		public OptionalLong syncedVersion() {
			return synced;
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
			registry.updateHints(Lists.immutable.of(new VersionHint.Manifest("books", "1", 4)));
			sync.synced = OptionalLong.of(4);
			before = sync.pulls.get();
			reader.refresh();
			assertThat(sync.pulls.get(), is(before));

			// A version past the copy is what a pass acts on
			registry.updateHints(Lists.immutable.of(new VersionHint.Manifest("books", "1", 5)));
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

		registry.updateHints(Lists.immutable.of(new VersionHint.Manifest("books", "1", 4)));

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
			registry.updateHints(Lists.immutable.of(new VersionHint.Manifest("books", "1", 5)));
			reader.refresh();
			reader.refresh();

			assertThat(sync.pulls.get(), is(before));
		} finally {
			reader.close();
		}
	}
}
