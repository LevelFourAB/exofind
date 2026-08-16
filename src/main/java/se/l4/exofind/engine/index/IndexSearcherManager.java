package se.l4.exofind.engine.index;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.lucene.search.IndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IndexSearcherManager manages the lifecycle of {@link IndexSearcher}
 * instances. This allows us to keep several searchers open to allow for
 * a user to continue searching with the searcher they used for their query
 * instead of always using the latest searcher.
 *
 * The latest searcher is the only one handles are handed out for, and it stays
 * open for as long as it is the latest. A searcher is retired when a newer one
 * replaces it, and the reader it holds is closed once the last handle taken
 * before the replacement is released - never while a search is still reading
 * it, as a reader closed underneath a search fails it with
 * {@link org.apache.lucene.store.AlreadyClosedException} rather than answering
 * it. A retired searcher that is still held long after it was replaced is
 * logged, because holding one is the only thing that can keep the files of a
 * commit from being freed.
 */
public class IndexSearcherManager {
	private static final Logger logger = LoggerFactory.getLogger(IndexSearcherManager.class);

	private final Duration retiredSearcherTimeout;
	private final ReadWriteLock lock;
	private final Set<SearcherRef> retiredSearchers;
	private final ScheduledFuture<?> cleanupTask;

	private volatile SearcherRef currentSearcher;
	private volatile boolean closed;

	public IndexSearcherManager(
		Duration retiredSearcherTimeout,
		ScheduledExecutorService cleanupExecutor
	) {
		this.retiredSearcherTimeout = retiredSearcherTimeout;
		this.lock = new ReentrantReadWriteLock();
		this.retiredSearchers = ConcurrentHashMap.newKeySet();

		// Mostly for testing, use timeout directly if less than 30 seconds
		var sweepInterval =
			retiredSearcherTimeout.toSeconds() < 30 ? retiredSearcherTimeout.toMillis() : 30000;

		this.cleanupTask = cleanupExecutor.scheduleAtFixedRate(
			this::reportHeldSearchers,
			sweepInterval,
			sweepInterval,
			TimeUnit.MILLISECONDS
		);
	}

	/**
	 * Make a searcher the one handles are handed out for, retiring the one it
	 * replaces.
	 *
	 * @param searcher
	 */
	public void refreshLatest(IndexSearcher searcher) {
		SearcherRef retired;
		lock.writeLock().lock();
		try {
			if(closed) {
				throw new IllegalStateException("IndexSearcherManager is closed");
			}

			retired = currentSearcher;
			currentSearcher = new SearcherRef(searcher);
		} finally {
			lock.writeLock().unlock();
		}

		if(retired != null) {
			/*
			 * Retiring is what starts the timeout, and it closes the reader
			 * here and now if nothing is reading it. Done outside the write
			 * lock so a close never holds up the searches waiting for it.
			 */
			retiredSearchers.add(retired);
			retired.retire();
		}
	}

	/**
	 * Take a handle on the latest searcher. The searcher stays open for as long
	 * as the handle is held, however many times it is replaced meanwhile.
	 *
	 * @return
	 */
	public Handle acquire() {
		lock.readLock().lock();
		try {
			if(closed) {
				throw new IllegalStateException("IndexSearcherManager is closed");
			}

			var ref = currentSearcher;
			if(ref == null) {
				throw new IllegalStateException("No searcher available");
			}

			/*
			 * Retiring takes the write lock before it touches the current
			 * searcher, so the one read here can not be retired while this
			 * holds the read lock.
			 */
			if(!ref.tryAcquire()) {
				throw new IllegalStateException("No searcher available");
			}

			return new SearcherHandle(ref);
		} finally {
			lock.readLock().unlock();
		}
	}

	public void close() {
		SearcherRef current;
		lock.writeLock().lock();
		try {
			if(closed) {
				return;
			}
			closed = true;

			current = currentSearcher;
			currentSearcher = null;
		} finally {
			lock.writeLock().unlock();
		}

		cleanupTask.cancel(false);

		/*
		 * Closing is the one time a reader is closed with handles still on it -
		 * the index takes its write lock before closing, so no search is left
		 * to read them.
		 */
		for(var ref : retiredSearchers) {
			ref.forceClose();
		}
		retiredSearchers.clear();

		if(current != null) {
			current.forceClose();
		}
	}

	private void reportHeldSearchers() {
		var deadline = System.currentTimeMillis() - retiredSearcherTimeout.toMillis();
		for(var ref : retiredSearchers) {
			ref.reportIfHeldSince(deadline);
		}
	}

	public interface Handle extends AutoCloseable {
		/**
		 * Get the searcher.
		 *
		 * @return
		 * @throws IllegalStateException if the handle has been released or invalidated
		 */
		IndexSearcher getSearcher();

		/**
		 * Release this handle. After releasing, the handle can no longer be used.
		 * If this is the last handle for a searcher, the searcher may be closed.
		 */
		@Override
		void close();

		/**
		 * Returns true if this handle is still valid and can be used.
		 */
		boolean isValid();
	}

	private static class SearcherHandle implements Handle {
		private final SearcherRef ref;
		private volatile boolean released;

		SearcherHandle(SearcherRef ref) {
			this.ref = ref;
			this.released = false;
		}

		@Override
		public IndexSearcher getSearcher() {
			if(released) {
				throw new IllegalStateException("Handle has been released");
			}
			if(!ref.isValid()) {
				throw new IllegalStateException("Searcher has been invalidated");
			}
			return ref.searcher;
		}

		@Override
		public void close() {
			if(!released) {
				released = true;
				ref.release();
			}
		}

		@Override
		public boolean isValid() {
			return !released && ref.isValid();
		}
	}

	private class SearcherRef {
		private final IndexSearcher searcher;

		private int handles;
		private boolean retired;
		private boolean closed;
		private long retiredAt;
		private boolean reported;

		SearcherRef(IndexSearcher searcher) {
			this.searcher = searcher;
		}

		synchronized boolean tryAcquire() {
			if(retired) {
				return false;
			}

			handles++;
			return true;
		}

		void release() {
			boolean close;
			synchronized(this) {
				handles--;
				close = retired && handles == 0 && !closed;
				if(close) {
					closed = true;
				}
			}

			if(close) {
				retiredSearchers.remove(this);
				closeReader();
			}
		}

		void retire() {
			boolean close;
			synchronized(this) {
				if(retired) {
					return;
				}

				retired = true;
				retiredAt = System.currentTimeMillis();
				close = handles == 0 && !closed;
				if(close) {
					closed = true;
				}
			}

			if(close) {
				retiredSearchers.remove(this);
				closeReader();
			}
		}

		void forceClose() {
			synchronized(this) {
				retired = true;
				if(closed) {
					return;
				}

				closed = true;
			}

			closeReader();
		}

		void reportIfHeldSince(long deadline) {
			int held;
			synchronized(this) {
				if(closed || reported || retiredAt > deadline) {
					return;
				}

				reported = true;
				held = handles;
			}

			logger.atWarn()
				.addKeyValue("handles", held)
				.log(
					"A searcher is still being read "
						+ retiredSearcherTimeout
						+ " after it was replaced; the files of its commit stay on disk until it is done"
				);
		}

		synchronized boolean isValid() {
			return !closed;
		}

		private void closeReader() {
			try {
				searcher.getIndexReader().close();
			} catch(Exception e) {
				logger.atError()
					.setCause(e)
					.log("Failed to close searcher; " + e.getMessage());
			}
		}
	}
}
