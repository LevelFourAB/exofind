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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.InMemoryRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.state.StateSync;
import se.l4.exofind.engine.index.state.StateSyncProvider;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * What a pass of the sweeper asks to sweep: every open generation the node
 * writes, and nothing a node only reads.
 */
public class IndexOrphanSweeperTest {
	@TempDir
	Path storageDirectory;

	/**
	 * A sync that counts how many times it was asked to sweep.
	 */
	static class CountingSync implements StateSync {
		final AtomicInteger sweeps = new AtomicInteger();

		@Override
		public void push(Set<String> files) {
		}

		@Override
		public void claimWriter() {
		}

		@Override
		public void sweep() {
			sweeps.incrementAndGet();
		}

		@Override
		public boolean pull() {
			return false;
		}

		@Override
		public OptionalLong syncedVersion() {
			return OptionalLong.empty();
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

	private Indexes newNode(
		NodeState nodeState,
		IndexRegistry registry,
		StateSyncProvider syncProvider
	) throws IOException {
		return new Indexes(
			nodeState,
			syncProvider,
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			storageDirectory,
			OptionalInt.empty(),
			Duration.ofMinutes(5),
			Duration.ofMinutes(10),
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

	private IndexOrphanSweeper newSweeper(Indexes indexes, NodeState nodeState) {
		return new IndexOrphanSweeper(
			indexes,
			nodeState,
			StorageMode.OBJECT,
			Duration.ofMinutes(10)
		);
	}

	@Test
	public void testPassSweepsTheGenerationsTheNodeWrites() throws Exception {
		var registry = new IndexRegistry(new InMemoryRegistryStorage(), Duration.ofMinutes(5));
		var provider = new CountingSyncProvider();
		var nodeState = nodeState(true);

		var writer = newNode(nodeState, registry, provider);
		try {
			writer.create("books", IndexDef.getDefaultInstance());
			var sync = provider.syncs.get("books@1");

			newSweeper(writer, nodeState).pass();

			assertThat(sync.sweeps.get(), is(1));
		} finally {
			writer.close();
		}
	}

	/**
	 * A node that only reads an index holds no claim on it, so its copy may
	 * describe a manifest the writer has moved past. Nothing on it sweeps.
	 */
	@Test
	public void testPassSkipsTheGenerationsTheNodeOnlyReads() throws Exception {
		var registry = new IndexRegistry(new InMemoryRegistryStorage(), Duration.ofMinutes(5));

		var creator = newNode(nodeState(true), registry, new CountingSyncProvider());
		creator.create("books", IndexDef.getDefaultInstance());
		creator.close();

		var provider = new CountingSyncProvider();
		var nodeState = nodeState(false);
		var reader = newNode(nodeState, registry, provider);
		try {
			reader.getOrThrow("books");
			var sync = provider.syncs.get("books@1");

			newSweeper(reader, nodeState).pass();

			assertThat(sync.sweeps.get(), is(0));
		} finally {
			reader.close();
		}
	}
}
