package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.NoopSync;
import se.l4.exofind.engine.index.state.StateSync;

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

		sync.blockNextPush = true;
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
	 * while it runs.
	 */
	private static class BlockingSync implements StateSync {
		final CountDownLatch pushStarted = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);

		volatile boolean blockNextPush;

		@Override
		public boolean pull() throws IOException {
			return false;
		}

		@Override
		public void push(Set<String> files) throws IOException {
			if(!blockNextPush) {
				return;
			}

			blockNextPush = false;
			pushStarted.countDown();

			try {
				release.await();
			} catch(InterruptedException e) {
				Thread.currentThread().interrupt();
			}
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
