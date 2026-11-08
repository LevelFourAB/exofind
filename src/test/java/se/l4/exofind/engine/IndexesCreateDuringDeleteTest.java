package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.InMemoryRegistryStorage;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.RegistryException;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * That a create which another node's delete overlaps leaves the registry and
 * the removal marks the way the delete left them.
 *
 * <p>The create clears what an earlier delete left under the prefix, mark and
 * all. A delete on another node can remove the index from the registry and
 * leave its mark before that clearing runs. Without the mark nothing removes
 * the objects, and a later create of the name finds them held.
 *
 * <p>A create that fails after it registered the name takes its registration
 * back. Another node can delete the name and create it again before the
 * failure, and that registration is the other node's to keep.
 */
public class IndexesCreateDuringDeleteTest {
	@TempDir
	Path storageDirectory;

	private static Indexes newNode(
		Path directory,
		IndexRegistry registry,
		RecordingIndexRemovals removals
	) throws IOException {
		var nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		return new Indexes(
			nodeState,
			new NoopSyncProvider(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			removals,
			directory,
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
	void aCreateDoesNotTakeTheMarkOfADeleteThatCameAfterIt() throws IOException {
		// Given two nodes over one registry and one storage
		var storage = new InMemoryRegistryStorage();
		var registryA = new IndexRegistry(storage, Duration.ofMinutes(5));
		var registryB = new IndexRegistry(storage, Duration.ofMinutes(5));

		var hook = new AtomicReference<Runnable>();
		var removals = new RecordingIndexRemovals() {
			@Override
			public void prepareForIndex(String index) {
				var run = hook.getAndSet(null);
				if(run != null) {
					run.run();
				}

				super.prepareForIndex(index);
			}
		};

		var nodeA = newNode(storageDirectory.resolve("a"), registryA, removals);
		var nodeB = newNode(storageDirectory.resolve("b"), registryB, removals);
		try {
			// And node B deletes the index right after node A registered it
			hook.set(() -> {
				try {
					nodeB.delete("books");
				} catch(IOException e) {
					throw new RuntimeException(e);
				}
			});

			// When node A goes on with the create, it answers with a conflict
			assertThrows(RegistryException.class, () -> nodeA.create("books", keyed()));

			// Then the registry does not name the index
			registryB.refresh();
			assertThat(registryB.get("books").isPresent(), is(false));

			// And a mark of the delete still stands over it
			assertThat(removals.markedAt(IndexName.of("books")).isPresent(), is(true));
		} finally {
			nodeA.close();
			nodeB.close();
		}
	}

	@Test
	void aCreateWithNoDeleteMeanwhileOpensTheIndex() throws IOException {
		var storage = new InMemoryRegistryStorage();
		var registry = new IndexRegistry(storage, Duration.ofMinutes(5));
		var removals = new RecordingIndexRemovals();

		var node = newNode(storageDirectory.resolve("a"), registry, removals);
		try {
			var created = node.create("books", keyed());

			assertThat(created.getId(), is("books@1"));
			assertThat(removals.markedAt(IndexName.of("books")).isPresent(), is(false));
		} finally {
			node.close();
		}
	}

	@Test
	void aFailedCreateDoesNotUnregisterAnIndexAnotherNodeCreatedMeanwhile() throws IOException {
		// Given two nodes over one registry
		var storage = new InMemoryRegistryStorage();
		var registryA = new IndexRegistry(storage, Duration.ofMinutes(5));
		var registryB = new IndexRegistry(storage, Duration.ofMinutes(5));

		var removalsB = new RecordingIndexRemovals();
		var hook = new AtomicReference<Runnable>();
		var removalsA = new RecordingIndexRemovals() {
			@Override
			public void prepareForIndex(String index) {
				var run = hook.getAndSet(null);
				if(run != null) {
					run.run();
				}

				throw new UncheckedIOException(new IOException("Storage could not be cleared"));
			}
		};

		var nodeA = newNode(storageDirectory.resolve("a"), registryA, removalsA);
		var nodeB = newNode(storageDirectory.resolve("b"), registryB, removalsB);
		try {
			// And node B deletes the name and creates it again after node A registered it
			hook.set(() -> {
				try {
					nodeB.delete("books");
					nodeB.create("books", keyed());
				} catch(IOException e) {
					throw new RuntimeException(e);
				}
			});

			// When the create on node A fails after the registration
			assertThrows(UncheckedIOException.class, () -> nodeA.create("books", keyed()));

			// Then the index node B created is still registered
			registryB.refresh();
			assertThat(registryB.get("books").isPresent(), is(true));

			// And no removal mark stands over it
			assertThat(removalsB.markedAt(IndexName.of("books")).isPresent(), is(false));
		} finally {
			nodeA.close();
			nodeB.close();
		}
	}

	@Test
	void aFailedCreateTakesItsOwnRegistrationBack() throws IOException {
		var storage = new InMemoryRegistryStorage();
		var registry = new IndexRegistry(storage, Duration.ofMinutes(5));
		var removals = new RecordingIndexRemovals() {
			@Override
			public void prepareForIndex(String index) {
				throw new UncheckedIOException(new IOException("Storage could not be cleared"));
			}
		};

		var node = newNode(storageDirectory.resolve("a"), registry, removals);
		try {
			assertThrows(UncheckedIOException.class, () -> node.create("books", keyed()));

			registry.refresh();
			assertThat(registry.get("books").isPresent(), is(false));
		} finally {
			node.close();
		}
	}

	@Test
	void aCreateWhoseReReadFailsDoesNotTakeTheMarkOfADeleteThatCameAfterIt() throws IOException {
		// Given two nodes over one registry and one storage, where the next read can be made to fail
		var failNextRead = new java.util.concurrent.atomic.AtomicBoolean();
		var storage = new InMemoryRegistryStorage() {
			@Override
			public Read read(String knownVersion) throws IOException {
				if(failNextRead.compareAndSet(true, false)) {
					throw new IOException("Storage could not be reached");
				}

				return super.read(knownVersion);
			}
		};
		var registryA = new IndexRegistry(storage, Duration.ofMinutes(5));
		var registryB = new IndexRegistry(storage, Duration.ofMinutes(5));

		var hook = new AtomicReference<Runnable>();
		var removals = new RecordingIndexRemovals() {
			@Override
			public void prepareForIndex(String index) {
				var run = hook.getAndSet(null);
				if(run != null) {
					run.run();
				}

				super.prepareForIndex(index);
			}
		};

		var nodeA = newNode(storageDirectory.resolve("a"), registryA, removals);
		var nodeB = newNode(storageDirectory.resolve("b"), registryB, removals);
		try {
			// And node B deletes the index right after node A registered it, and the read after that fails
			hook.set(() -> {
				try {
					nodeB.delete("books");
				} catch(IOException e) {
					throw new RuntimeException(e);
				}

				failNextRead.set(true);
			});

			// When node A goes on with the create
			RuntimeException failure = null;
			try {
				nodeA.create("books", keyed());
			} catch(RuntimeException e) {
				failure = e;
			}

			// Then the registry does not name the index
			assertThat("the re-read did not fail", failNextRead.get(), is(false));
			registryB.refresh();
			assertThat(registryB.get("books").isPresent(), is(false));

			// And a mark of the delete still stands over it
			assertThat(
				"mark of the delete taken by the create, create answered " + failure,
				removals.markedAt(IndexName.of("books")).isPresent(),
				is(true)
			);

			// And the create failed
			assertThat("create answered as done", failure != null, is(true));
		} finally {
			nodeA.close();
			nodeB.close();
		}
	}

	/**
	 * A delete takes the name out of the registry, removes the local copies
	 * and then leaves its mark. Removing a large copy takes time, and another
	 * node can create the name again before the mark is written. The mark
	 * then stands over the new index, which nothing deleted.
	 */
	@Test
	void aDeleteDoesNotLeaveItsMarkOverAnIndexCreatedAgainBeforeTheMarkWasWritten()
		throws IOException
	{
		// Given two nodes over one registry and one storage, and books on node A
		var storage = new InMemoryRegistryStorage();
		var registryA = new IndexRegistry(storage, Duration.ofMinutes(5));
		var registryB = new IndexRegistry(storage, Duration.ofMinutes(5));

		var hook = new AtomicReference<Runnable>();
		var removals = new RecordingIndexRemovals() {
			@Override
			public void mark(IndexName target) throws IOException {
				var run = hook.getAndSet(null);
				if(run != null) {
					run.run();
				}

				super.mark(target);
			}
		};

		var nodeA = newNode(storageDirectory.resolve("a"), registryA, removals);
		var nodeB = newNode(storageDirectory.resolve("b"), registryB, removals);
		try {
			nodeA.create("books", keyed());

			// And node B creates books again after node A's registry write and before its mark
			hook.set(() -> nodeB.create("books", keyed()));

			// When node A deletes books
			nodeA.delete("books");

			// Then the registry names the books node B created
			registryB.refresh();
			assertThat(registryB.get("books").isPresent(), is(true));

			// And no mark of the delete stands over it
			assertThat(hook.get() == null, is(true));
			assertThat(
				"mark of the delete stands over the index created again",
				removals.markedAt(IndexName.of("books")).isPresent(),
				is(false)
			);
		} finally {
			nodeA.close();
			nodeB.close();
		}
	}

	@Test
	void aGenerationCreateDoesNotTakeTheMarkOfADeleteThatCameAfterIt() throws IOException {
		// Given two nodes over one registry and one storage, and books live on its first generation
		var storage = new InMemoryRegistryStorage();
		var registryA = new IndexRegistry(storage, Duration.ofMinutes(5));
		var registryB = new IndexRegistry(storage, Duration.ofMinutes(5));

		var hook = new AtomicReference<Runnable>();
		var removals = new RecordingIndexRemovals() {
			@Override
			public void prepareForGeneration(IndexName generation) {
				var run = hook.getAndSet(null);
				if(run != null) {
					run.run();
				}

				super.prepareForGeneration(generation);
			}
		};

		var nodeA = newNode(storageDirectory.resolve("a"), registryA, removals);
		var nodeB = newNode(storageDirectory.resolve("b"), registryB, removals);
		try {
			nodeA.create("books", keyed());

			// And node B deletes books@2 right after node A registered it
			hook.set(() -> {
				try {
					registryB.refresh();
					nodeB.delete("books@2");
				} catch(IOException e) {
					throw new RuntimeException(e);
				}
			});

			// When node A goes on with the create of books@2
			RuntimeException failure = null;
			try {
				nodeA.createGeneration("books@2", keyed());
			} catch(RuntimeException e) {
				failure = e;
			}

			// Then the registry does not name the generation
			registryB.refresh();
			assertThat(registryB.get("books").orElseThrow().hasGeneration("2"), is(false));

			// And a mark of the delete still stands over it
			assertThat(
				"mark of the delete taken by the create",
				removals.markedAt(IndexName.of("books", "2")).isPresent(),
				is(true)
			);

			// And the create answered with a conflict
			assertThat(
				"create of a generation deleted meanwhile answered " + failure,
				failure instanceof RegistryException,
				is(true)
			);
		} finally {
			nodeA.close();
			nodeB.close();
		}
	}

	@Test
	void aFailedGenerationCreateDoesNotUnregisterAGenerationAnotherNodeCreatedMeanwhile()
		throws IOException
	{
		// Given two nodes over one registry, and books live on its first generation
		var storage = new InMemoryRegistryStorage();
		var registryA = new IndexRegistry(storage, Duration.ofMinutes(5));
		var registryB = new IndexRegistry(storage, Duration.ofMinutes(5));

		var removalsB = new RecordingIndexRemovals();
		var hook = new AtomicReference<Runnable>();
		var removalsA = new RecordingIndexRemovals() {
			@Override
			public void prepareForGeneration(IndexName generation) {
				var run = hook.getAndSet(null);
				if(run != null) {
					run.run();
				}

				throw new UncheckedIOException(new IOException("Storage could not be cleared"));
			}
		};

		var nodeA = newNode(storageDirectory.resolve("a"), registryA, removalsA);
		var nodeB = newNode(storageDirectory.resolve("b"), registryB, removalsB);
		try {
			nodeB.create("books", keyed());
			registryA.refresh();

			// And node B deletes books@2 and creates it again, in a later millisecond, after node A registered it
			hook.set(() -> {
				var registeredByA = Instant.now().toEpochMilli();
				while(Instant.now().toEpochMilli() <= registeredByA) {
					Thread.onSpinWait();
				}

				try {
					registryB.refresh();
					nodeB.delete("books@2");
					nodeB.createGeneration("books@2", keyed());
				} catch(IOException e) {
					throw new RuntimeException(e);
				}
			});

			// When the create of books@2 on node A fails after the registration
			assertThrows(UncheckedIOException.class, () -> nodeA.createGeneration("books@2", keyed()));

			// Then the generation node B created is still registered
			registryB.refresh();
			assertThat(
				"failed create removed the generation of another node",
				registryB.get("books").orElseThrow().hasGeneration("2"),
				is(true)
			);
		} finally {
			nodeA.close();
			nodeB.close();
		}
	}

	/**
	 * Two nodes can give their registrations of a name the same millisecond:
	 * their clocks are not the same clock, and one fast node does a delete
	 * and a create within a millisecond. The registration of the other node
	 * is still not the one the failed create made.
	 */
	@Test
	void aFailedCreateDoesNotUnregisterAnIndexAnotherNodeCreatedInTheSameMillisecond()
		throws IOException
	{
		// Given two nodes over one registry
		var storage = new InMemoryRegistryStorage();
		var registryA = new IndexRegistry(storage, Duration.ofMinutes(5));
		var registryB = new IndexRegistry(storage, Duration.ofMinutes(5));

		var removalsB = new RecordingIndexRemovals();
		var hook = new AtomicReference<Runnable>();
		var removalsA = new RecordingIndexRemovals() {
			@Override
			public void prepareForIndex(String index) {
				var run = hook.getAndSet(null);
				if(run != null) {
					run.run();
				}

				throw new UncheckedIOException(new IOException("Storage could not be cleared"));
			}
		};

		var nodeA = newNode(storageDirectory.resolve("a"), registryA, removalsA);
		var nodeB = newNode(storageDirectory.resolve("b"), registryB, removalsB);
		try {
			// And node B deletes the name and creates it again in the millisecond node A registered it
			hook.set(() -> {
				var registeredByA = storage.stored().getIndexesList().stream()
					.filter(entry -> entry.getName().equals("books"))
					.findFirst()
					.orElseThrow()
					.getCreatedAt();

				try {
					nodeB.delete("books");
					nodeB.create("books", keyed());
				} catch(IOException e) {
					throw new RuntimeException(e);
				}

				var store = storage.stored().toBuilder();
				for(int i = 0; i < store.getIndexesCount(); i++) {
					if(store.getIndexes(i).getName().equals("books")) {
						store.setIndexes(i, store.getIndexes(i).toBuilder().setCreatedAt(registeredByA));
					}
				}
				storage.set(store.build());
			});

			// When the create on node A fails after the registration
			assertThrows(UncheckedIOException.class, () -> nodeA.create("books", keyed()));

			// Then the index node B created is still registered
			registryB.refresh();
			assertThat(
				"failed create removed the registration of another node",
				registryB.get("books").isPresent(),
				is(true)
			);
		} finally {
			nodeA.close();
			nodeB.close();
		}
	}
}
