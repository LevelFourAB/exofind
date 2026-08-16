package se.l4.exofind.engine.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.IndexSearcherManager.Handle;

class IndexSearcherManagerTest {
	private static final Duration TIMEOUT = Duration.ofMillis(200);
	private static final long WAIT_TIME = TIMEOUT.toMillis() * 3;

	private IndexSearcherManager manager;
	private ScheduledExecutorService executor;

	private Directory directory;
	private IndexWriter writer;

	private IndexSearcher searcher1;
	private IndexSearcher searcher2;

	@BeforeEach
	void setUp() throws IOException {
		executor = Executors.newSingleThreadScheduledExecutor();
		manager = new IndexSearcherManager(TIMEOUT, executor);

		directory = new ByteBuffersDirectory();
		writer = new IndexWriter(directory, new IndexWriterConfig());
		writer.addDocument(new Document());
		writer.commit();

		searcher1 = new IndexSearcher(DirectoryReader.open(writer));
		searcher2 = new IndexSearcher(DirectoryReader.open(writer));
	}

	@AfterEach
	void tearDown() throws Exception {
		manager.close();
		executor.shutdown();
		executor.awaitTermination(1, TimeUnit.SECONDS);

		writer.close();
		directory.close();
	}

	/**
	 * A reader that has been closed has given up its last reference, which is
	 * what tells a searcher still open apart from one the manager has closed.
	 */
	private void assertOpen(IndexSearcher searcher) {
		assertTrue(isOpen(searcher.getIndexReader()), "Searcher should still be open");
	}

	private void assertClosed(IndexSearcher searcher) {
		assertFalse(isOpen(searcher.getIndexReader()), "Searcher should have been closed");
	}

	private boolean isOpen(IndexReader reader) {
		return reader.getRefCount() > 0;
	}

	@Test
	void testAcquireWithoutSearcher() {
		assertThrows(IllegalStateException.class, () -> manager.acquire());
	}

	@Test
	void testBasicHandleLifecycle() {
		manager.refreshLatest(searcher1);

		Handle handle = manager.acquire();
		assertNotNull(handle);
		assertTrue(handle.isValid());
		assertEquals(searcher1, handle.getSearcher());

		handle.close();
		assertFalse(handle.isValid());

		// The current searcher is not closed by releasing a handle on it
		assertOpen(searcher1);
	}

	@Test
	void testHandleInvalidationOnRefresh() {
		manager.refreshLatest(searcher1);
		Handle handle1 = manager.acquire();

		manager.refreshLatest(searcher2);
		Handle handle2 = manager.acquire();

		assertTrue(handle1.isValid());
		assertTrue(handle2.isValid());
		assertEquals(searcher1, handle1.getSearcher());
		assertEquals(searcher2, handle2.getSearcher());

		handle1.close();
		handle2.close();

		// Verify old searcher is closed
		assertClosed(searcher1);
		assertOpen(searcher2);
	}

	@Test
	void testRetiredSearcherClosedWhenUnused() {
		manager.refreshLatest(searcher1);
		manager.refreshLatest(searcher2);

		assertClosed(searcher1);
		assertOpen(searcher2);
	}

	@Test
	void testMultipleHandles() {
		manager.refreshLatest(searcher1);

		List<Handle> handles = new ArrayList<>();
		for(int i = 0; i < 5; i++) {
			handles.add(manager.acquire());
		}

		// Release all but one handle
		for(int i = 0; i < 4; i++) {
			handles.get(i).close();
		}

		// Searcher should still be valid
		assertOpen(searcher1);

		// Release last handle
		handles.get(4).close();

		// Current searcher should not be closed
		assertOpen(searcher1);
	}

	@Test
	void testCurrentSearcherSurvivesTimeout() throws Exception {
		manager.refreshLatest(searcher1);
		manager.acquire().close();

		// An index that is never written to is never refreshed either
		Thread.sleep(WAIT_TIME);

		assertOpen(searcher1);

		Handle handle = manager.acquire();
		assertTrue(handle.isValid(), "Current searcher should still be usable");
		assertEquals(searcher1, handle.getSearcher());
		handle.close();
	}

	@Test
	void testHeldSearcherSurvivesTimeout() throws Exception {
		manager.refreshLatest(searcher1);
		Handle handle = manager.acquire();

		manager.refreshLatest(searcher2);

		// Wait for the timeout to pass
		Thread.sleep(WAIT_TIME);

		assertTrue(handle.isValid(), "Held handle should stay valid past the timeout");
		assertEquals(searcher1, handle.getSearcher());
		assertOpen(searcher1);

		// Releasing the last handle is what closes a retired searcher
		handle.close();
		assertClosed(searcher1);
	}

	@Test
	void testCloseInvalidatesAllHandles() {
		manager.refreshLatest(searcher1);
		Handle handle1 = manager.acquire();

		manager.refreshLatest(searcher2);
		Handle handle2 = manager.acquire();

		manager.close();

		assertFalse(handle1.isValid(), "First handle should be invalid after close");
		assertFalse(handle2.isValid(), "Second handle should be invalid after close");
		assertThrows(
			IllegalStateException.class,
			() -> handle1.getSearcher(),
			"Using invalid handle should throw"
		);
		assertThrows(
			IllegalStateException.class,
			() -> handle2.getSearcher(),
			"Using invalid handle should throw"
		);

		assertClosed(searcher1);
		assertClosed(searcher2);
	}

	@Test
	void testConcurrentAccess() throws Exception {
		manager.refreshLatest(searcher1);

		int threadCount = 10;
		Thread[] threads = new Thread[threadCount];
		List<Handle> handles = new ArrayList<>();

		// Create threads that acquire and release handles
		for(int i = 0; i < threadCount; i++) {
			threads[i] = new Thread(() -> {
				for(int j = 0; j < 100; j++) {
					Handle handle = manager.acquire();
					synchronized(handles) {
						handles.add(handle);
					}
					try {
						Thread.sleep(1);
					} catch(InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					handle.close();
				}
			});
			threads[i].start();
		}

		// Wait for all threads to complete
		for(Thread thread : threads) {
			thread.join();
		}

		// Verify all handles are released
		for(Handle handle : handles) {
			assertFalse(handle.isValid());
		}
	}

	/**
	 * Searching while the index is refreshed underneath is what the manager is
	 * for - a searcher handed out has to stay readable until the search using
	 * it is done, however many times it has been replaced meanwhile.
	 */
	@Test
	void testSearchingWhileRefreshing() throws Exception {
		manager.refreshLatest(new IndexSearcher(DirectoryReader.open(writer)));

		var failure = new ArrayList<Throwable>();
		var readers = new Thread[4];
		var done = new AtomicBoolean(false);

		for(int i = 0; i < readers.length; i++) {
			readers[i] = new Thread(() -> {
				try {
					while(!done.get()) {
						try(var handle = manager.acquire()) {
							handle.getSearcher().count(new MatchAllDocsQuery());
						}
					}
				} catch(Throwable e) {
					synchronized(failure) {
						failure.add(e);
					}
				}
			});
			readers[i].start();
		}

		for(int i = 0; i < 50; i++) {
			manager.refreshLatest(new IndexSearcher(DirectoryReader.open(writer)));
			Thread.sleep(2);
		}

		done.set(true);
		for(var reader : readers) {
			reader.join();
		}

		assertTrue(failure.isEmpty(), () -> "Searching failed: " + failure);
	}
}
