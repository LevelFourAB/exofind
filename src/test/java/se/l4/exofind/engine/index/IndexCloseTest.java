package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.ObjectStorageSync;
import se.l4.exofind.engine.index.state.StateSync;
import se.l4.exofind.engine.index.state.TestObjectStorage;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * That a close of the instance that writes an index pushes every change it
 * acknowledged. A close commits and pushes once, so a change it cannot carry
 * is refused rather than acknowledged, and an interrupt of the thread that
 * closes does not stop the commit or the push. Runs against SeaweedFS through
 * {@link TestObjectStorage}.
 */
public class IndexCloseTest {
	private static final CommitPolicy FAST_COMMITS = new CommitPolicy(0, Duration.ofMillis(200));

	S3Client s3Client;
	String bucketPrefix;

	@TempDir
	Path root;

	private final List<Index> opened = new ArrayList<>();

	@BeforeEach
	void setup() {
		s3Client = TestObjectStorage.client();
		bucketPrefix = "test" + RandomStringUtils.insecure().nextAlphabetic(10);
	}

	@AfterEach
	void cleanup() throws IOException {
		for(var index : opened) {
			index.close(false);
		}

		var objects = s3Client.listObjectsV2Paginator(
			b -> b.bucket(TestObjectStorage.BUCKET).prefix(bucketPrefix)
		).contents().stream().toList();

		for(var object : objects) {
			s3Client.deleteObject(
				DeleteObjectRequest.builder()
					.bucket(TestObjectStorage.BUCKET)
					.key(object.key())
					.build()
			);
		}
	}

	@Test
	@Timeout(60)
	void aWriteThatArrivesWhileTheCloseIsPushingIsRefused() throws Exception {
		// Given node A writes the index and pushed document 1
		var stateA = holder();
		var sync = new HeldSync(objectSync("a"));
		var a = open("a", stateA, sync, FAST_COMMITS);
		a.pull();
		a.updateDefinition(definition());
		a.addDocument(doc("1"));
		a.commit();

		// And node A acknowledged document 2 and did not commit it yet
		a.addDocument(doc("2"));

		// When the instance closes, as an eviction does, and its push is held
		sync.holdNextPush();
		var closing = CompletableFuture.runAsync(() -> {
			try {
				a.close();
			} catch(IOException e) {
				throw new RuntimeException(e);
			}
		});
		assertThat("the close started its push", sync.awaitHeldPush(), is(true));

		// Then a write during that push is refused, so the caller sends it again
		try {
			assertThrows(IndexClosedException.class, () -> {
				try(var change = a.beginChange()) {
					a.addDocument(doc("3"));
				}
			});
		} finally {
			sync.releaseHeldPush();
		}

		// And the close pushes document 2
		closing.get(30, TimeUnit.SECONDS);
		opened.remove(a);

		var reader = open("b", new NodeState(false), objectSync("b"), CommitPolicy.disabled());
		reader.pull();
		assertThat(reader.getDocument("2"), is(notNullValue()));
	}

	@Test
	@Timeout(60)
	void aCloseOnAnInterruptedThreadPushesWhatItHolds() throws Exception {
		// Given node A writes the index and pushed document 1
		var a = open("a", holder(), objectSync("a"), CommitPolicy.disabled());
		a.pull();
		a.updateDefinition(definition());
		a.addDocument(doc("1"));
		a.commit();

		// And node A acknowledged document 2 and did not commit it yet
		a.addDocument(doc("2"));

		// When the index is closed on a thread that was interrupted
		var failure = new AtomicReference<Throwable>();
		var stillInterrupted = new AtomicBoolean();
		var thread = new Thread(() -> {
			Thread.currentThread().interrupt();
			try {
				a.close();
			} catch(Throwable t) {
				failure.set(t);
			}
			stillInterrupted.set(Thread.currentThread().isInterrupted());
		});
		thread.start();
		thread.join(30_000);
		opened.remove(a);

		// Then the close reports no failure and keeps the interrupt
		assertThat("the close failed with " + failure.get(), failure.get(), is(nullValue()));
		assertThat(stillInterrupted.get(), is(true));

		// And a reader on another node finds document 2
		var reader = open("b", new NodeState(false), objectSync("b"), CommitPolicy.disabled());
		reader.pull();
		assertThat(reader.getDocument("2"), is(notNullValue()));
	}

	@Test
	@Timeout(60)
	void anInterruptDuringTheClosePushDoesNotStopIt() throws Exception {
		// Given node A writes the index and pushed document 1
		var sync = new HeldSync(objectSync("a"));
		var a = open("a", holder(), sync, CommitPolicy.disabled());
		a.pull();
		a.updateDefinition(definition());
		a.addDocument(doc("1"));
		a.commit();

		// And node A acknowledged document 2 and did not commit it yet
		a.addDocument(doc("2"));

		// When the index closes and its push is held
		sync.holdNextPush();
		var failure = new AtomicReference<Throwable>();
		var thread = new Thread(() -> {
			try {
				a.close();
			} catch(Throwable t) {
				failure.set(t);
			}
		});
		thread.start();
		assertThat("the close started its push", sync.awaitHeldPush(), is(true));

		// And the closing thread is interrupted, as a shutdown of its executor does
		thread.interrupt();
		sync.releaseHeldPush();
		thread.join(30_000);
		opened.remove(a);

		// Then the close reports no failure
		assertThat("the close failed with " + failure.get(), failure.get(), is(nullValue()));

		// And a reader on another node finds document 2
		var reader = open("b", new NodeState(false), objectSync("b"), CommitPolicy.disabled());
		reader.pull();
		assertThat(reader.getDocument("2"), is(notNullValue()));
	}

	private ObjectStorageSync objectSync(String node) throws IOException {
		var path = root.resolve(node);
		Files.createDirectories(path);

		return new ObjectStorageSync(
			s3Client, "books", path, TestObjectStorage.BUCKET, bucketPrefix
		);
	}

	private Index open(String node, NodeState state, StateSync sync, CommitPolicy policy) throws IOException {
		var path = root.resolve(node);
		Files.createDirectories(path);

		var index = new Index(state, "books", path, sync, policy);
		opened.add(index);
		return index;
	}

	private static NodeState holder() {
		var state = new NodeState(true);
		state.updateOwnership(true);
		return state;
	}

	private static Document doc(String id) {
		return new Document(new Document.Value("id", id));
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
	 * Wraps a real synchronization, and can hold the next push until the test
	 * lets it go.
	 */
	private static final class HeldSync implements StateSync {
		private final StateSync delegate;
		private final AtomicBoolean holdNext;
		private volatile CountDownLatch entered;
		private volatile CountDownLatch release;

		HeldSync(StateSync delegate) {
			this.delegate = delegate;
			this.holdNext = new AtomicBoolean();
		}

		void holdNextPush() {
			entered = new CountDownLatch(1);
			release = new CountDownLatch(1);
			holdNext.set(true);
		}

		boolean awaitHeldPush() throws InterruptedException {
			return entered.await(10, TimeUnit.SECONDS);
		}

		void releaseHeldPush() {
			release.countDown();
		}

		@Override
		public void push(Set<String> files) throws IOException {
			if(holdNext.compareAndSet(true, false)) {
				entered.countDown();
				try {
					release.await(30, TimeUnit.SECONDS);
				} catch(InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while held", e);
				}
			}

			delegate.push(files);
		}

		@Override
		public void claimWriter() throws IOException {
			delegate.claimWriter();
		}

		@Override
		public void sweep() throws IOException {
			delegate.sweep();
		}

		@Override
		public boolean pull() throws IOException {
			return delegate.pull();
		}

		@Override
		public void stopPulling() {
			delegate.stopPulling();
		}

		@Override
		public OptionalLong syncedVersion() {
			return delegate.syncedVersion();
		}

		@Override
		public boolean hasSyncedCommit() {
			return delegate.hasSyncedCommit();
		}

		@Override
		public OptionalInt luceneCreatedMajor() {
			return delegate.luceneCreatedMajor();
		}
	}
}
