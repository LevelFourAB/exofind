package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.registry.InMemoryRegistryStorage;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.IndexRegistryStore;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.registry.RegistryStorage;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopRemoteSyncProvider;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * That a refresh pass does not remove the local copy of a generation that a
 * create made after the pass read the registry.
 *
 * <p>The pass finds the copies the registry does not name before it takes the
 * lifecycle lock, and removes them under it. A create of the same name can hold
 * the lock in between. Only object mode removes copies here, as in local mode
 * the directories are the only copy of the data.
 */
public class IndexesRefreshDuringCreateTest {
	@TempDir
	Path storageDirectory;

	private Indexes newNode(IndexRegistry registry) throws IOException {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		return new Indexes(
			nodeState,
			new NoopRemoteSyncProvider(),
			registry,
			new RegistryHints(registry, StorageMode.OBJECT),
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

	private static IndexDef keyed() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				FieldDef.newBuilder()
					.setPrimaryKey(true)
					.setType(
						FieldTypeDef.newBuilder()
							.setString(StringFieldTypeDef.getDefaultInstance())
					)
					.build()
			)
			.build();
	}

	@Test
	void refreshPassDoesNotRemoveACopyCreatedAfterItLookedAtTheRegistry() throws Exception {
		// Given a node that holds two indexes
		var inner = new InMemoryRegistryStorage();
		var gated = new GatedRegistryStorage(inner);
		var registryA = new IndexRegistry(gated, Duration.ofMinutes(5));
		var registryB = new IndexRegistry(inner, Duration.ofMinutes(5));

		var nodeA = newNode(registryA);
		try {
			nodeA.create("keep", keyed());
			nodeA.create("books", keyed());
			nodeA.refresh();

			// And another node deletes one of them
			registryB.refresh();
			registryB.remove("books");

			// And node A reads that the index is gone
			registryA.refresh();

			// And a request on node A creates it again, held at its registry write
			var created = new AtomicReference<Index>();
			var createFailure = new AtomicReference<Throwable>();
			var creator = new Thread(() -> {
				try {
					created.set(nodeA.create("books", keyed()));
				} catch(Throwable t) {
					createFailure.set(t);
				}
			});
			gated.gatedThread = creator;
			creator.start();
			assertThat(gated.writeReached.await(10, TimeUnit.SECONDS), is(true));

			// When the refresh pass runs while the create holds the lifecycle lock
			var refresher = new Thread(() -> nodeA.refresh(true));
			refresher.start();
			awaitParkedIn(refresher, "removeVanished");

			gated.releaseWrite.countDown();
			creator.join(10_000);
			refresher.join(10_000);

			// Then the create succeeded
			assertThat(createFailure.get(), is(nullValue()));

			// And the definition the create wrote is still there
			assertThat(nodeA.getOrThrow("books").getDefinition().containsFields("id"), is(true));

			// And the index the create returned is still the one the name answers from
			assertThat(nodeA.getOrThrow("books"), is(sameInstance(created.get())));
		} finally {
			nodeA.close();
		}
	}

	/**
	 * Wait until a thread parks inside a method, which is where a refresh
	 * pass waits for the lifecycle lock.
	 */
	private static void awaitParkedIn(Thread thread, String method) throws InterruptedException {
		var deadline = System.currentTimeMillis() + 10_000;
		while(System.currentTimeMillis() < deadline) {
			var state = thread.getState();
			if(state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
				var inMethod = Arrays.stream(thread.getStackTrace())
					.anyMatch(frame -> frame.getMethodName().equals(method));
				if(inMethod) {
					return;
				}
			}

			if(state == Thread.State.TERMINATED) {
				throw new AssertionError("The thread ended before it reached " + method);
			}

			Thread.sleep(10);
		}

		throw new AssertionError("The thread did not park in " + method + " within the wait");
	}

	/**
	 * Registry storage that can hold one write on a chosen thread, so a test
	 * can stop a create inside the lifecycle lock.
	 */
	static class GatedRegistryStorage implements RegistryStorage {
		final RegistryStorage delegate;
		volatile Thread gatedThread;
		final CountDownLatch writeReached = new CountDownLatch(1);
		final CountDownLatch releaseWrite = new CountDownLatch(1);

		GatedRegistryStorage(RegistryStorage delegate) {
			this.delegate = delegate;
		}

		@Override
		public Read read(String knownVersion) throws IOException {
			return delegate.read(knownVersion);
		}

		@Override
		public String write(IndexRegistryStore indexes, String expectedVersion) throws IOException {
			if(Thread.currentThread() == gatedThread) {
				gatedThread = null;
				writeReached.countDown();
				try {
					releaseWrite.await(10, TimeUnit.SECONDS);
				} catch(InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}

			return delegate.write(indexes, expectedVersion);
		}
	}
}
