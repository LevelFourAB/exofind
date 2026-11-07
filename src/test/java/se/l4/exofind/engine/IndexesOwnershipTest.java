package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;

import org.apache.lucene.index.SegmentInfos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

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

	/**
	 * The node loses an index and takes it back before the reopen that the
	 * loss queued has run, for example when the lease watch gives up every
	 * claim while a round that renews them is still writing the table. The
	 * index that the node holds again must push what it acknowledges.
	 */
	@Test
	@Timeout(30)
	public void anIndexTakenBackBeforeItsReopenRanPushesItsCommits() throws Exception {
		var fixture = new RegainFixture();
		var indexes = fixture.start();
		try {
			// Given: the node lost "a" and took it back while the reopens waited
			var a = fixture.loseAndTakeBack(indexes);

			// When: a document is acknowledged and committed
			a.addDocument(new Document(new Document.Value("id", "1")));
			var pushesBefore = fixture.pushesOfA.get();

			Throwable failure = null;
			try {
				a.commit();
			} catch(IOException | RuntimeException e) {
				failure = e;
			}

			// Then: the commit reached the remote
			assertThat("failure of the commit", failure, is(nullValue()));
			assertThat("pushes of a", fixture.pushesOfA.get(), greaterThan(pushesBefore));
		} finally {
			fixture.release.countDown();
			indexes.close();
		}
	}

	/**
	 * The node takes an index back before the reopen that its loss queued has
	 * run, acknowledges a document, and then hands the index over. The
	 * handover must push that document before it lets the claim move.
	 */
	@Test
	@Timeout(30)
	public void aHandoverOfAnIndexTakenBackPushesWhatItAcknowledged() throws Exception {
		var fixture = new RegainFixture();
		var indexes = fixture.start();
		try {
			// Given: the node lost "a" and took it back while the reopens waited
			var a = fixture.loseAndTakeBack(indexes);

			// And: a document was acknowledged after the node took "a" back
			a.addDocument(new Document(new Document.Value("id", "1")));
			var pushesBefore = fixture.pushesOfA.get();

			// When: the node hands "a" over
			fixture.state.updateOwnership("a", false);

			Throwable failure = null;
			try {
				indexes.flushForHandover("a").get(10, TimeUnit.SECONDS);
			} catch(ExecutionException e) {
				failure = e.getCause();
			}

			// Then: the flush pushed the document and lets the handover go ahead
			assertThat("failure of the handover flush", failure, is(nullValue()));
			assertThat("pushes of a", fixture.pushesOfA.get(), greaterThan(pushesBefore));
		} finally {
			fixture.release.countDown();
			indexes.close();
		}
	}

	/**
	 * The node takes an index back before the reopen that its loss queued has
	 * run, acknowledges a document and then shuts down while it holds the
	 * index. The close must push that document.
	 */
	@Test
	@Timeout(30)
	public void aCloseOfAnIndexTakenBackPushesWhatItAcknowledged() throws Exception {
		var fixture = new RegainFixture();
		var indexes = fixture.start();
		try {
			// Given: the node lost "a" and took it back while the reopens waited
			var a = fixture.loseAndTakeBack(indexes);

			// And: a document was acknowledged after the node took "a" back
			a.addDocument(new Document(new Document.Value("id", "1")));
			var pushesBefore = fixture.pushesOfA.get();

			// When: the node shuts down while it holds "a"
			fixture.release.countDown();
			indexes.close();

			// Then: the close pushed the document
			assertThat("pushes of a", fixture.pushesOfA.get(), greaterThan(pushesBefore));
		} finally {
			fixture.release.countDown();
			indexes.close();
		}
	}

	/**
	 * A node with one pull thread that holds "a" and "b". The pulls of "b"
	 * can be held, so that the work queued for "a" waits behind them.
	 */
	private class RegainFixture {
		final NodeState state = new NodeState(true);
		final AtomicBoolean holdB = new AtomicBoolean();
		final CountDownLatch bReached = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicInteger pushesOfA = new AtomicInteger();

		Indexes start() throws IOException {
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

			state.updateOwnership("a", true);
			state.updateOwnership("b", true);

			var indexes = newNode(state, provider, 1);
			indexes.create("a", keyedDefinition());
			indexes.create("b", keyedDefinition());
			return indexes;
		}

		/**
		 * Take "a" away and give it back while the only pull thread is held
		 * by "b", then let the queued work run and wait until it is done.
		 */
		Index loseAndTakeBack(Indexes indexes) throws Exception {
			holdB.set(true);
			state.revokeOwnership("b");
			assertThat("the pull of b holds the pull thread", bReached.await(10, TimeUnit.SECONDS), is(true));

			state.revokeOwnership("a");
			state.updateOwnership("a", true);

			release.countDown();
			awaitOwnershipWork(indexes);

			var a = indexes.getOrThrow("a");
			assertThat("a is read-only", a.isReadOnly(), is(false));
			return a;
		}
	}

	/**
	 * Wait until no ownership work is queued or running. Reads private state,
	 * because nothing public says when the queued reopens have run.
	 */
	@SuppressWarnings("unchecked")
	private static void awaitOwnershipWork(Indexes indexes) throws Exception {
		var field = Indexes.class.getDeclaredField("ownershipQueues");
		field.setAccessible(true);
		var queues = (Map<String, ?>) field.get(indexes);

		var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while(System.nanoTime() < deadline) {
			if(queues.isEmpty()) {
				return;
			}

			Thread.sleep(10);
		}

		throw new AssertionError("Ownership work did not finish, queued for " + queues.keySet());
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

	private static IndexDef keyedDefinition() {
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

	private static void awaitPulled(Set<String> pulled, String generation)
		throws InterruptedException {
		var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while(!pulled.contains(generation) && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
	}

	private Indexes newNode(NodeState state, StateSyncProvider provider) throws IOException {
		return newNode(state, provider, 4);
	}

	private Indexes newNode(
		NodeState state,
		StateSyncProvider provider,
		int refreshConcurrency
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
			OptionalInt.empty(),
			Duration.ofMinutes(5),
			Duration.ofMinutes(10),
			refreshConcurrency,
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
