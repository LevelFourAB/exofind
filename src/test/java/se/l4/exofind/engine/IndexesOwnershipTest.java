package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.state.NoopSync;
import se.l4.exofind.engine.index.state.StateSync;
import se.l4.exofind.engine.index.state.StateSyncProvider;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * Tests for how {@link Indexes} follows the indexes this node is given and
 * has taken away.
 */
public class IndexesOwnershipTest {
	@TempDir
	Path storageDirectory;

	/**
	 * Reopening an index the node no longer writes goes to the storage, and
	 * one that is handed over commits and pushes first. The reopen of another
	 * index must not wait behind that, or one index that is slow to reach the
	 * storage delays the failover of every other.
	 */
	@Test
	@Timeout(30)
	public void testSlowReopenDoesNotHoldUpAnother() throws Exception {
		var reopening = new AtomicBoolean();
		var reachedStorage = new CountDownLatch(1);
		var released = new CountDownLatch(1);
		var pulled = ConcurrentHashMap.<String>newKeySet();

		var provider = new StateSyncProvider() {
			@Override
			public StateSync createSync(IndexName generation, Path dataPath) {
				return new NoopSync() {
					@Override
					public boolean pull() throws IOException {
						if(!reopening.get()) {
							return false;
						}

						pulled.add(generation.toString());
						if(generation.toString().equals("a@1")) {
							reachedStorage.countDown();
							try {
								released.await();
							} catch(InterruptedException e) {
								Thread.currentThread().interrupt();
							}
						}

						return false;
					}
				};
			}

			@Override
			public OptionalLong remoteVersion(IndexName generation) {
				return OptionalLong.empty();
			}
		};

		var state = new NodeState(true);
		state.updateOwnership("a", true);
		state.updateOwnership("b", true);

		var indexes = newNode(state, provider);
		try {
			indexes.create("a", IndexDef.getDefaultInstance());
			indexes.create("b", IndexDef.getDefaultInstance());

			reopening.set(true);

			state.revokeOwnership("a");
			assertThat(reachedStorage.await(10, TimeUnit.SECONDS), is(true));

			state.revokeOwnership("b");
			awaitPulled(pulled, "b@1");

			assertThat(pulled, hasItem("b@1"));
		} finally {
			released.countDown();
			indexes.close();
		}
	}

	private static void awaitPulled(Set<String> pulled, String generation)
		throws InterruptedException {
		var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while(!pulled.contains(generation) && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
	}

	private Indexes newNode(NodeState state, StateSyncProvider provider) throws IOException {
		var registry = new IndexRegistry(
			new LocalRegistryStorage(storageDirectory.resolve("registry.ef.bin")),
			Duration.ofMinutes(5)
		);

		return new Indexes(
			state,
			provider,
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			new RecordingIndexRemovals(),
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
}
