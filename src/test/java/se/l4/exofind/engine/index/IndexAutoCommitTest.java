package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.metrics.Meters;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSync;
import se.l4.exofind.engine.index.state.StateSync;
import se.l4.exofind.engine.index.state.SyncConflictException;

/**
 * Tests for an index committing without being asked to, and for what the state
 * of an index says while and after it is pushed.
 */
public class IndexAutoCommitTest {
	private static final Duration WAIT = Duration.ofSeconds(10);

	@TempDir
	Path indexRoot;

	private final List<Index> indexes = new ArrayList<>();

	@AfterEach
	void cleanup() throws IOException {
		for(var index : indexes) {
			index.close();
		}
	}

	/**
	 * What is indexed is only searchable once the index has been committed, so
	 * a document coming back from a lookup nothing asked to commit is the
	 * commit having happened on its own.
	 */
	@Test
	public void aDocumentBecomesSearchableWithoutAnythingAskingForACommit() throws Exception {
		var index = create(
			"test",
			new NoopSync(),
			new CommitPolicy(0, Duration.ofMillis(50))
		);

		index.addDocument(new Document(new Document.Value("id", "1")));

		assertThat(awaitDocument(index, "1"), is(notNullValue()));
	}

	@Test
	public void enoughChangesCommitBeforeTheIntervalHasPassed() throws Exception {
		var index = create(
			"test",
			new NoopSync(),
			new CommitPolicy(2, Duration.ofHours(1))
		);

		index.addDocument(new Document(new Document.Value("id", "1")));
		index.addDocument(new Document(new Document.Value("id", "2")));

		assertThat(awaitDocument(index, "2"), is(notNullValue()));
	}

	@Test
	public void anIndexThatOnlyCommitsWhenAskedHoldsWhatIsIndexedUntilItIs() throws Exception {
		var index = create("test", new NoopSync(), CommitPolicy.disabled());

		index.addDocument(new Document(new Document.Value("id", "1")));

		Thread.sleep(200);
		assertThat(index.getDocument("1"), is(nullValue()));

		index.commit();
		assertThat(index.getDocument("1"), is(notNullValue()));
	}

	/**
	 * A push carries the commit it started from. A document indexed after that
	 * commit was taken is not part of what was uploaded, so the index still
	 * holds something the remote does not once the push is done.
	 */
	@Test
	public void aDocumentIndexedWhileAPushRunsLeavesTheIndexHoldingChanges() throws Exception {
		var sync = new BlockingSync();
		var index = create("test", sync, CommitPolicy.disabled());

		index.addDocument(new Document(new Document.Value("id", "1")));

		sync.holdNextPush();
		var committing = CompletableFuture.runAsync(() -> {
			try {
				index.commit();
			} catch(IOException e) {
				throw new UncheckedIOException(e);
			}
		});

		assertTrue(sync.pushStarted.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
		assertThat(index.getState(), is(IndexState.PUSHING));

		index.addDocument(new Document(new Document.Value("id", "2")));

		sync.release.countDown();
		committing.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

		assertThat(index.getState(), is(IndexState.MODIFIED));
	}

	/**
	 * A write is let through for as long as a push runs, and records its change
	 * once the push is over. A push that conflicted gave the local copy up for
	 * the remote, so that record must leave the index needing a pull: an index
	 * saying it holds changes is never pulled, goes on taking writes, conflicts
	 * on every commit after it, and drops everything it took when the pull
	 * finally comes.
	 */
	@Test
	public void aWriteRunningWhenAPushConflictsLeavesTheIndexNeedingAPull()
		throws Exception
	{
		var sync = new BlockingSync();
		var index = create("test", sync, CommitPolicy.disabled());

		/*
		 * Whether the write is still running when the conflict lands is up to
		 * the two threads, so the race is run several times over. A round where
		 * the write happened to be between two documents says nothing.
		 */
		for(var round = 0; round < 5; round++) {
			index.addDocument(new Document(new Document.Value("id", "seed" + round)));

			sync.holdNextPush();
			sync.refuseNextPush = true;

			var committing = CompletableFuture.runAsync(() -> {
				try {
					index.commit();
					fail("The push was refused, so the commit should have failed");
				} catch(SyncConflictException e) {
					// The conflict this round is about
				} catch(IOException e) {
					throw new UncheckedIOException(e);
				}
			});

			assertTrue(sync.pushStarted.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
			assertThat(index.getState(), is(IndexState.PUSHING));

			/*
			 * Documents are written one after another for the whole of the
			 * conflict, so that one of them is being written while it lands.
			 */
			var writes = new WriteLoop(index, round);
			var writing = new Thread(writes, "writes-" + round);
			writing.start();

			assertTrue(writes.wrote.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));

			sync.release.countDown();
			committing.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

			var refused = writes.refused.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
			writes.stop = true;
			writing.join();

			assertTrue(refused, "Writes went on being taken by an index needing a pull");
			assertThat(index.getState(), is(IndexState.NEEDS_PULL));

			// Back in step with the remote, ready for the next round
			index.pull();
			assertThat(index.getState(), is(IndexState.USABLE));
		}
	}

	/**
	 * Writes documents one after another until the index refuses one, which is
	 * what an index that has to be pulled does.
	 */
	private static final class WriteLoop implements Runnable {
		final CountDownLatch wrote = new CountDownLatch(1);
		final CountDownLatch refused = new CountDownLatch(1);

		private final Index index;
		private final int round;

		volatile boolean stop;

		WriteLoop(Index index, int round) {
			this.index = index;
			this.round = round;
		}

		@Override
		public void run() {
			for(var i = 0; !stop; i++) {
				try {
					index.addDocument(
						new Document(new Document.Value("id", round + ":" + i))
					);

					wrote.countDown();
				} catch(IndexOutOfDateException e) {
					refused.countDown();
					return;
				} catch(IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		}
	}

	@Test
	public void anIndexWithNothingIndexedSinceItsCommitIsInStepOnceItIsPushed()
		throws Exception {
		var index = create("test", new NoopSync(), CommitPolicy.disabled());

		index.addDocument(new Document(new Document.Value("id", "1")));
		index.commit();

		assertThat(index.getState(), is(IndexState.USABLE));
	}

	/**
	 * A definition is pushed as it is replaced, without the documents waiting
	 * for a commit going with it.
	 */
	@Test
	public void pushingADefinitionLeavesDocumentsWaitingForACommitCounted() throws Exception {
		var index = create("test", new NoopSync(), CommitPolicy.disabled());

		index.addDocument(new Document(new Document.Value("id", "1")));

		index.updateDefinition(
			definition()
				.putFields("name", string().build())
				.build()
		);

		assertThat(index.getState(), is(IndexState.MODIFIED));
	}

	/**
	 * A commit starts Lucene merging the segments it wrote, and a merge that
	 * finishes after the last commit leaves the writer holding work no commit
	 * has taken. The index commits it without new documents arriving.
	 */
	@Test
	public void aMergeFinishingAfterTheLastCommitIsCommittedOnItsOwn() throws Exception {
		var registry = new SimpleMeterRegistry();

		var path = indexRoot.resolve("test");
		Files.createDirectories(path);

		var state = new NodeState(true);
		state.updateOwnership(true);

		var index = new Index(
			state,
			"test",
			path,
			new NoopSync(),
			new CommitPolicy(1, Duration.ofMillis(25)),
			DocumentCache.disabled(),
			new RequestMetrics(registry, false)
		);
		indexes.add(index);

		index.pull();
		index.updateDefinition(definition().build());

		var mergeCommits = registry.timer(
			Meters.COMMIT,
			Meters.TAG_TRIGGER, Meters.TRIGGER_MERGES,
			Meters.TAG_OUTCOME, Meters.OUTCOME_SUCCESS
		);

		/*
		 * A commit per document, so every document is its own segment, and the
		 * writer is left with nothing waiting before the next one arrives.
		 * Whenever Lucene decides to merge the small segments, the merge
		 * finishes with no commit coming - only the commit made for the merges
		 * alone can take what it produced.
		 */
		for(var i = 0; i < 200 && mergeCommits.count() == 0; i++) {
			index.addDocument(new Document(new Document.Value("id", String.valueOf(i))));
			awaitNoPendingChanges(index);
			awaitNothingUncommitted(index);
		}

		assertTrue(
			mergeCommits.count() > 0,
			"no commit was made for the merges alone"
		);
	}

	private void awaitNothingUncommitted(Index index) throws Exception {
		var deadline = System.nanoTime() + WAIT.toNanos();
		while(index.hasUncommittedLuceneChanges() || index.hasPendingMerges()) {
			if(System.nanoTime() > deadline) {
				fail("The writer still holds work no commit has taken");
			}

			Thread.sleep(10);
		}
	}

	private void awaitNoPendingChanges(Index index) throws Exception {
		var deadline = System.nanoTime() + WAIT.toNanos();
		while(index.getPendingChanges() > 0) {
			if(System.nanoTime() > deadline) {
				fail("The change was never committed");
			}

			Thread.sleep(5);
		}
	}

	private Document awaitDocument(Index index, String key) throws Exception {
		var deadline = System.nanoTime() + WAIT.toNanos();
		while(System.nanoTime() < deadline) {
			var doc = index.getDocument(key);
			if(doc != null) {
				return doc;
			}

			Thread.sleep(10);
		}

		return null;
	}

	private Index create(String name, StateSync sync, CommitPolicy policy) throws IOException {
		var path = indexRoot.resolve(name);
		Files.createDirectories(path);

		var state = new NodeState(true);
		state.updateOwnership(true);

		var index = new Index(state, name, path, sync, policy);
		indexes.add(index);

		index.pull();
		index.updateDefinition(definition().build());

		return index;
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build());
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	/**
	 * A sync whose push can be held open, so that something can be indexed
	 * while it runs, and refused the way a remote another node has written
	 * refuses one.
	 */
	private static class BlockingSync implements StateSync {
		volatile CountDownLatch pushStarted = new CountDownLatch(1);
		volatile CountDownLatch release = new CountDownLatch(1);

		volatile boolean blockNextPush;
		volatile boolean refuseNextPush;

		/**
		 * Hold the next push open until {@link #release} is counted down, with
		 * latches of its own so one round can follow another.
		 */
		void holdNextPush() {
			pushStarted = new CountDownLatch(1);
			release = new CountDownLatch(1);
			blockNextPush = true;
		}

		@Override
		public boolean pull() throws IOException {
			return false;
		}

		@Override
		public void claimWriter() throws IOException {
		}

		@Override
		public void push(Set<String> files) throws IOException {
			if(blockNextPush) {
				blockNextPush = false;

				var held = release;
				pushStarted.countDown();

				try {
					held.await();
				} catch(InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}

			if(refuseNextPush) {
				refuseNextPush = false;
				throw new SyncConflictException("simulated conflict");
			}
		}

		@Override
		public boolean hasSyncedCommit() {
			return false;
		}

		@Override
		public OptionalInt luceneCreatedMajor() {
			return OptionalInt.empty();
		}

		@Override
		public OptionalLong syncedVersion() {
			return OptionalLong.empty();
		}
	}
}
