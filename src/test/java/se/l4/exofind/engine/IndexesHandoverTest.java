package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.github.benmanes.caffeine.cache.LoadingCache;

import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSync;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.index.state.StateSync;
import se.l4.exofind.engine.index.state.StateSyncProvider;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * Tests for {@link Indexes#flushForHandover}: the answer it gives decides
 * whether the claim moves to a successor.
 */
public class IndexesHandoverTest {
	@TempDir
	Path storageDirectory;

	/**
	 * An index was evicted from the cache and its instance is still
	 * closing in the grace period. A handover is decided, and the close of
	 * that instance can not push the documents it acknowledged. The flush
	 * must report the failure, so the claim stays with this node.
	 */
	@Test
	@Timeout(60)
	void aFailedPushOfAnEvictedInstanceFailsTheHandoverFlush() throws Exception {
		var failPushes = new AtomicBoolean();
		Set<String> pushAttempts = ConcurrentHashMap.newKeySet();

		var provider = new StateSyncProvider() {
			@Override
			public StateSync createSync(IndexName generation, Path dataPath) {
				return new NoopSync() {
					@Override
					public void push(Set<String> files) throws IOException {
						if(failPushes.get()) {
							pushAttempts.add(generation.toString());
							throw new IOException("Simulated storage failure for " + generation);
						}
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

		// Given: a node that keeps one index open and closes evicted ones after a long grace
		var indexes = newNode(state, provider, OptionalInt.of(1), Duration.ofMinutes(5));
		try {
			// And: both indexes hold a document that was acknowledged but not pushed
			var a = indexes.create("a", definition());
			a.addDocument(new Document(new Document.Value("id", "1")));

			var b = indexes.create("b", definition());
			b.addDocument(new Document(new Document.Value("id", "2")));

			// And: one of them was evicted and is waiting in the grace period
			var retired = awaitRetired(indexes);
			var name = IndexName.parse(retired).index();

			// And: the storage refuses every push from now on
			failPushes.set(true);

			// When: the node hands that index over
			state.updateOwnership(name, false);
			var flushed = indexes.flushForHandover(name);

			// Then: the close of the evicted instance tried to push
			Throwable failure = null;
			try {
				flushed.get(30, TimeUnit.SECONDS);
			} catch(ExecutionException e) {
				failure = e.getCause();
			}

			assertThat(
				"push attempted by the evicted instance " + retired,
				pushAttempts.contains(retired),
				is(true)
			);

			// And: the flush says the handover must not go ahead
			assertThat(
				"the handover flush reports that " + retired + " could not be pushed",
				failure != null,
				is(true)
			);
		} finally {
			failPushes.set(false);
			indexes.close();
		}
	}

	/**
	 * The close of an evicted instance could not push, so the documents
	 * it acknowledged are only in a local commit. The node still holds the
	 * index. The storage recovers, and the node then hands the index over
	 * (an idle index is the one a node sheds first). The flush must push the
	 * stranded commit before the claim moves.
	 */
	@Test
	@Timeout(60)
	void aCommitStrandedByAFailedClosePushIsPushedBeforeTheHandover() throws Exception {
		Set<String> failFor = ConcurrentHashMap.newKeySet();
		Set<String> failedPushes = ConcurrentHashMap.newKeySet();
		Set<String> pushedAfterRecovery = ConcurrentHashMap.newKeySet();
		var recovered = new AtomicBoolean();

		var provider = new StateSyncProvider() {
			@Override
			public StateSync createSync(IndexName generation, Path dataPath) {
				return new NoopSync() {
					@Override
					public void push(Set<String> files) throws IOException {
						var name = generation.toString();
						if(failFor.contains(name)) {
							failedPushes.add(name);
							throw new IOException("Simulated storage failure for " + name);
						}

						if(recovered.get()) {
							pushedAfterRecovery.add(name);
						}
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

		// Given: a node that keeps one index open and closes evicted ones after a short grace
		var indexes = newNode(state, provider, OptionalInt.of(1), Duration.ofSeconds(2));
		try {
			// And: both indexes hold a document that was acknowledged but not pushed
			var a = indexes.create("a", definition());
			a.addDocument(new Document(new Document.Value("id", "1")));

			var b = indexes.create("b", definition());
			b.addDocument(new Document(new Document.Value("id", "2")));

			// And: one of them was evicted, and the storage refuses its pushes
			var retired = awaitRetired(indexes);
			var name = IndexName.parse(retired).index();
			failFor.add(retired);

			// And: its close ran after the grace and could not push
			awaitClosed(indexes, retired);
			assertThat("failed close push of " + retired, failedPushes.contains(retired), is(true));

			// And: the storage recovers
			failFor.clear();
			recovered.set(true);

			// When: the node hands that index over
			state.updateOwnership(name, false);
			var flushed = indexes.flushForHandover(name);

			Throwable failure = null;
			try {
				flushed.get(30, TimeUnit.SECONDS);
			} catch(ExecutionException e) {
				failure = e.getCause();
			}

			// Then: the stranded commit was pushed, and the handover may go ahead
			assertThat(
				"the handover of " + retired + " pushed the stranded commit",
				pushedAfterRecovery.contains(retired),
				is(true)
			);
			assertThat("the handover flush of " + retired + " failed", failure, is(nullValue()));
		} finally {
			failFor.clear();
			indexes.close();
		}
	}

	/**
	 * Wait until the evicted generation has finished closing.
	 */
	@SuppressWarnings("unchecked")
	private static void awaitClosed(Indexes indexes, String name) throws Exception {
		var retiringField = Indexes.class.getDeclaredField("retiring");
		retiringField.setAccessible(true);
		var retiring = (Map<String, ?>) retiringField.get(indexes);

		var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while(System.nanoTime() < deadline) {
			if(!retiring.containsKey(name)) {
				return;
			}

			Thread.sleep(25);
		}

		throw new AssertionError(name + " did not finish closing");
	}

	private static IndexDef definition() {
		return IndexDef.newBuilder()
			.putFields(
				"id",
				FieldDef.newBuilder()
					.setType(FieldTypeDef.newBuilder().setString(StringFieldTypeDef.getDefaultInstance()))
					.setPrimaryKey(true)
					.build()
			)
			.build();
	}

	/**
	 * Wait until the cache has evicted one generation into the grace period,
	 * and return its name. Reads private state, because nothing public says
	 * which generation is closing.
	 */
	@SuppressWarnings("unchecked")
	private static String awaitRetired(Indexes indexes) throws Exception {
		var cacheField = Indexes.class.getDeclaredField("indexes");
		cacheField.setAccessible(true);
		var cache = (LoadingCache<String, Index>) cacheField.get(indexes);

		var retiringField = Indexes.class.getDeclaredField("retiring");
		retiringField.setAccessible(true);
		var retiring = (Map<String, ?>) retiringField.get(indexes);

		var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while(System.nanoTime() < deadline) {
			cache.cleanUp();
			if(retiring.size() == 1) {
				return retiring.keySet().iterator().next();
			}

			Thread.sleep(10);
		}

		throw new AssertionError("No generation was evicted, retiring holds " + retiring.keySet());
	}

	private Indexes newNode(
		NodeState state,
		StateSyncProvider provider,
		OptionalInt maxOpen,
		Duration closeGracePeriod
	) throws IOException {
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
			maxOpen,
			Duration.ofMinutes(5),
			Duration.ofMinutes(10),
			4,
			closeGracePeriod,
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
