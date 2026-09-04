package se.l4.exofind.engine.index;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.lucene.search.IndexSearcher;

import se.l4.exofind.engine.logging.Log;

/**
 * IndexSearcherManager manages the lifecycle of {@link IndexSearcher}
 * instances. This allows us to keep several searchers open to allow for
 * a user to continue searching with the searcher they used for their query
 * instead of always using the latest searcher.
 *
 * <p>The latest searcher is the only one handles are handed out for, and it
 * stays open for as long as it is the latest. A searcher is retired when a
 * newer one replaces it, and the reader it holds is closed once the last
 * handle taken before the replacement is released - never while a search is
 * still reading it, as a reader closed underneath a search fails it with
 * {@link org.apache.lucene.store.AlreadyClosedException} rather than answering
 * it. A retired searcher that is still held long after it was replaced is
 * logged, because holding one is the only thing that can keep the files of a
 * commit from being freed.
 *
 * <p>{@link #acquire()} and releasing the handle it returns are on the path
 * of every search and take no lock - a contended monitor parks waiting
 * threads, and a parked thread resumes on the scheduler's terms, not when the
 * monitor is free. Replacing and closing searchers may block; {@link #close()}
 * must not run concurrently with {@link #acquire()}, which the index
 * guarantees by holding its write lock while closing.
 */
public class IndexSearcherManager {
	private static final Log logger = Log.of(IndexSearcherManager.class);

	private final Duration retiredSearcherTimeout;
	private final Set<SearcherRef> retiredSearchers;
	private final ScheduledFuture<?> cleanupTask;

	private volatile SearcherRef currentSearcher;
	private volatile boolean closed;

	public IndexSearcherManager(
		Duration retiredSearcherTimeout,
		ScheduledExecutorService cleanupExecutor
	) {
		this.retiredSearcherTimeout = retiredSearcherTimeout;
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
	public synchronized void refreshLatest(IndexSearcher searcher) {
		if(closed) {
			throw new IllegalStateException("IndexSearcherManager is closed");
		}

		var retired = currentSearcher;
		currentSearcher = new SearcherRef(searcher);

		if(retired != null) {
			/*
			 * Retiring is what starts the timeout, and it closes the reader
			 * here and now if nothing is reading it.
			 */
			retiredSearchers.add(retired);
			retired.retire();
		}
	}

	/**
	 * Get whether the given searcher is still the one handles are handed out
	 * for. Background work over a searcher asks this between its steps, so
	 * that it stops preparing a searcher that is no longer handed out.
	 *
	 * @param searcher
	 * @return
	 */
	public boolean isLatest(IndexSearcher searcher) {
		var ref = currentSearcher;
		return ref != null && ref.searcher == searcher;
	}

	/**
	 * Take a handle on the latest searcher. The searcher stays open for as long
	 * as the handle is held, however many times it is replaced meanwhile.
	 *
	 * @return
	 */
	public Handle acquire() {
		while(true) {
			if(closed) {
				throw new IllegalStateException("IndexSearcherManager is closed");
			}

			var ref = currentSearcher;
			if(ref == null) {
				throw new IllegalStateException("No searcher available");
			}

			if(ref.tryAcquire()) {
				return new SearcherHandle(ref);
			}

			/*
			 * The ref was retired between reading it and acquiring it, which
			 * means something newer replaced it - go read that instead.
			 */
		}
	}

	public synchronized void close() {
		if(closed) {
			return;
		}
		closed = true;

		var current = currentSearcher;
		currentSearcher = null;

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

	/**
	 * A searcher together with the handles that are reading it. The whole
	 * lifecycle - handle count, retired, closed - is one atomic integer, so
	 * that taking and releasing a handle is a CAS rather than a monitor: with
	 * every search of an index acquiring and releasing on the one shared
	 * current ref, a monitor there is the contention point of the entire read
	 * path.
	 *
	 * <p>The low bits count handles. {@link #RETIRED} means no new handles;
	 * {@link #CLOSED} means the reader is closed or being closed. The CAS that
	 * moves the state to retired-with-no-handles also sets {@link #CLOSED},
	 * so exactly one caller wins the transition and closes the reader.
	 */
	private class SearcherRef {
		private static final int RETIRED = 1 << 30;
		private static final int CLOSED = 1 << 31;
		private static final int HANDLES = RETIRED - 1;

		private final IndexSearcher searcher;
		private final AtomicInteger state;

		/**
		 * When {@link #retire()} ran. Written before the CAS that sets
		 * {@link #RETIRED} and read only after seeing that bit, so it needs no
		 * volatile of its own.
		 */
		private long retiredAt;

		/** Only touched by the single-threaded cleanup task. */
		private boolean reported;

		SearcherRef(IndexSearcher searcher) {
			this.searcher = searcher;
			this.state = new AtomicInteger();
		}

		boolean tryAcquire() {
			while(true) {
				int s = state.get();
				if((s & RETIRED) != 0) {
					return false;
				}

				if(state.compareAndSet(s, s + 1)) {
					return true;
				}
			}
		}

		void release() {
			while(true) {
				int s = state.get();
				int next = s - 1;
				boolean close = next == RETIRED;
				if(close) {
					next |= CLOSED;
				}

				if(state.compareAndSet(s, next)) {
					if(close) {
						retiredSearchers.remove(this);
						closeReader();
					}
					return;
				}
			}
		}

		void retire() {
			retiredAt = System.currentTimeMillis();

			while(true) {
				int s = state.get();
				if((s & RETIRED) != 0) {
					return;
				}

				int next = s | RETIRED;
				boolean close = s == 0;
				if(close) {
					next |= CLOSED;
				}

				if(state.compareAndSet(s, next)) {
					if(close) {
						retiredSearchers.remove(this);
						closeReader();
					}
					return;
				}
			}
		}

		void forceClose() {
			while(true) {
				int s = state.get();
				if((s & CLOSED) != 0) {
					return;
				}

				if(state.compareAndSet(s, s | RETIRED | CLOSED)) {
					closeReader();
					return;
				}
			}
		}

		void reportIfHeldSince(long deadline) {
			int s = state.get();
			if((s & RETIRED) == 0 || (s & CLOSED) != 0 || reported || retiredAt > deadline) {
				return;
			}

			reported = true;
			logger.atWarn()
				.addKeyValue("handles", s & HANDLES)
				.log(
					"A searcher is still being read "
						+ retiredSearcherTimeout
						+ " after it was replaced; the files of its commit stay on disk until it is done"
				);
		}

		boolean isValid() {
			return (state.get() & CLOSED) == 0;
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
