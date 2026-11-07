package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;

import org.apache.lucene.index.SegmentInfos;

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
import se.l4.exofind.engine.index.state.LocalCopy;
import se.l4.exofind.engine.index.state.Manifest;
import se.l4.exofind.engine.index.state.ManifestFile;
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
	 * A refresh pass queues a pull of every index that is not in step with
	 * the remote, which includes an index that holds documents that were
	 * acknowledged and not committed. When the node then starts to hand that
	 * index over, its flush is queued behind that pull. The pull must not
	 * drop the documents, and the flush must push them before it lets the
	 * claim move.
	 */
	@Test
	@Timeout(60)
	void aRefreshPullQueuedAheadOfAHandoverFlushKeepsTheDocuments() throws Exception {
		var holdB = new AtomicBoolean();
		var bReached = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var pushesOfA = new AtomicInteger();

		var provider = new StateSyncProvider() {
			@Override
			public StateSync createSync(IndexName generation, Path dataPath) {
				return new NoopSync() {
					@Override
					public boolean pull() throws IOException {
						if(generation.index().equals("b") && holdB.get()) {
							bReached.countDown();
							try {
								release.await();
							} catch(InterruptedException e) {
								Thread.currentThread().interrupt();
							}
						}

						return false;
					}

					@Override
					public void push(Set<String> files) throws IOException {
						describePushed(dataPath, files);
						if(generation.index().equals("a")) {
							pushesOfA.incrementAndGet();
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

		// Given: a node with one pull thread, whose refresh passes check every index that is not in step
		var indexes = newNode(state, provider, OptionalInt.empty(), Duration.ofMillis(100), 1, Duration.ofMillis(1));
		Thread refresh = null;
		try {
			var a = indexes.create("a", definition());
			indexes.create("b", definition());

			// And: "a" holds a document that was acknowledged and not committed
			a.addDocument(new Document(new Document.Value("id", "1")));
			var pushesBefore = pushesOfA.get();

			// And: the only pull thread is busy with a slow pull of "b"
			holdB.set(true);
			state.revokeOwnership("b");
			assertThat("the pull of b holds the pull thread", bReached.await(10, TimeUnit.SECONDS), is(true));

			// And: a refresh pass has queued its pulls behind it
			refresh = new Thread(indexes::refresh);
			refresh.start();
			awaitQueuedPulls(indexes, 2);

			// When: the node starts to hand "a" over and asks for the flush
			state.updateOwnership("a", false);
			var flushed = indexes.flushForHandover("a");
			release.countDown();

			Throwable failure = null;
			try {
				flushed.get(30, TimeUnit.SECONDS);
			} catch(ExecutionException e) {
				failure = e.getCause();
			}

			// Then: the flush pushed the document before it let the handover go ahead
			assertThat("failure of the handover flush", failure, is(nullValue()));
			assertThat("pushed by the handover flush", pushesOfA.get() > pushesBefore, is(true));
		} finally {
			release.countDown();
			if(refresh != null) {
				refresh.join(10_000);
			}
			indexes.close();
		}
	}

	/**
	 * Describe the pushed files in a local manifest, the way a push that got
	 * them to the remote does, so that the copy does not read as holding
	 * changes the remote never got.
	 */
	private static void describePushed(Path directory, Set<String> files) throws IOException {
		var builder = Manifest.newBuilder()
			.setLatestSegment(SegmentInfos.getLastCommitGeneration(files.toArray(String[]::new)))
			.setVersion(1);

		for(var name : files) {
			var path = directory.resolve(name);
			if(!Files.exists(path)) {
				continue;
			}

			var checksum = new CRC32C();
			checksum.update(Files.readAllBytes(path));
			builder.addFiles(
				ManifestFile.newBuilder()
					.setName(name)
					.setSize(Files.size(path))
					.setChecksum((int) checksum.getValue())
					.build()
			);
		}

		try(var out = Files.newOutputStream(directory.resolve(LocalCopy.MANIFEST_FILE))) {
			builder.build().writeTo(out);
		}
	}

	/**
	 * Wait until at least the given number of tasks wait on the pull
	 * executor. Reads private state, because nothing public says what a
	 * refresh pass has queued.
	 */
	private static void awaitQueuedPulls(Indexes indexes, int count) throws Exception {
		var field = Indexes.class.getDeclaredField("pullExecutor");
		field.setAccessible(true);
		var executor = (ThreadPoolExecutor) field.get(indexes);

		var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while(System.nanoTime() < deadline) {
			if(executor.getQueue().size() >= count) {
				return;
			}

			Thread.sleep(10);
		}

		throw new AssertionError("The refresh queued " + executor.getQueue().size() + " pulls");
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
		return newNode(state, provider, maxOpen, closeGracePeriod, 4, Duration.ofMinutes(5));
	}

	private Indexes newNode(
		NodeState state,
		StateSyncProvider provider,
		OptionalInt maxOpen,
		Duration closeGracePeriod,
		int refreshConcurrency,
		Duration refreshInterval
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
			refreshInterval,
			Duration.ofMinutes(10),
			refreshConcurrency,
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
