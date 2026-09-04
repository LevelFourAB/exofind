package se.l4.exofind.engine.index;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.metrics.Meters;
import se.l4.exofind.engine.metrics.RequestMetrics;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Prepares the facet state of a reader after it is reopened, so that the
 * first search against it does not pay for it.
 *
 * <p>Counting a facet over a reader needs what {@link FacetStates} keeps per
 * reader and per segment: the ordinals of every segment lined up against one
 * another, the values of each segment laid out flat, and what each segment
 * counts for the scope nothing narrows. All of it is about the reader alone
 * and costs the same whatever the search asked for, so without this the first
 * search after every pull or commit pays a cost that says nothing about that
 * search. An index queues itself here each time it replaces its reader - see
 * {@link Index#warmFacets()} - and a thread of this pool walks every faceted
 * field of the new reader over everything it holds, on the code path a search
 * counts with, so nothing can be prepared differently from how it is counted.
 *
 * <p>The queue holds indexes rather than readers, one entry per index. A
 * reader that is queued would keep a retired reader and the files of its
 * commit alive while it waits, and a node holding hundreds of indexes can
 * have a queue worth waiting in. So an index is taken from the queue and
 * asked for whatever reader it holds then, which folds every reopen that
 * happened while it waited into one warm of the newest. A warm that is
 * running when the index reopens again gives up between fields, since the
 * reader it was preparing is no longer handed out, and the index has queued
 * itself again behind it. What the warm built per segment is kept
 * either way, as those entries outlive the reader.
 *
 * <p>A search never waits for a warm. It builds what it needs itself where
 * the warm has not reached it, and where the warm is building the ordinal map
 * of the very field the search asks for, the search waits for that build
 * alone rather than making a second one - see
 * {@link FacetStates#stringOrdsOf}.
 *
 * <p>The threads are the node's, shared by every index on it, and sized by
 * {@code EXOFIND_SEARCH_WARM_THREADS}. They are not the search threads: a warm
 * is nobody's request and runs under no {@link SearchDeadline}, and a warm on
 * a search thread would slow the searches the pool exists to speed up. They
 * are not the maintenance thread of the index either, which is one thread
 * that also schedules the commits. Zero threads turns warming off, and the
 * first search after a reopen builds everything, as it does on a node with no
 * warming.
 *
 * <p>Every warm is timed under {@link Meters#FACET_WARM}, by how it ended.
 * How many indexes are waiting is {@link Meters#FACET_WARM_QUEUED}: a depth
 * that stays high means the threads are not keeping up with how often readers
 * reopen, and first searches are as cold as with no warming.
 *
 * <p>Safe for concurrent use.
 */
@ApplicationScoped
public class FacetWarmer implements AutoCloseable {
	private static final Log logger = Log.of(FacetWarmer.class);

	private final RequestMetrics metrics;
	private final int threads;

	/**
	 * The pool, or {@code null} where warming is off.
	 */
	private final ExecutorService pool;

	/**
	 * The indexes waiting for a thread, so that an index reopening several
	 * times while it waits is warmed once.
	 */
	private final Set<Index> queued;

	/**
	 * How many warms are queued or running, for {@link #awaitIdle}.
	 */
	private int active;

	@Inject
	public FacetWarmer(
		RequestMetrics metrics,
		@ConfigProperty(name = "exofind.search.warm-threads", defaultValue = "2")
		int threads
	) {
		if(threads < 0) {
			throw new IllegalArgumentException(
				"exofind.search.warm-threads can not be negative, got " + threads
			);
		}

		this.metrics = metrics;
		this.threads = threads;
		this.queued = ConcurrentHashMap.newKeySet();

		if(threads == 0) {
			this.pool = null;
			return;
		}

		var numbered = new AtomicInteger();
		this.pool = Executors.newFixedThreadPool(threads, runnable -> {
			var thread = new Thread(runnable, "facet-warm-" + numbered.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * A warmer that warms nothing, for an index opened outside a node. Every
	 * search then builds what it needs itself.
	 */
	public static FacetWarmer none() {
		return new FacetWarmer(RequestMetrics.none(), 0);
	}

	/**
	 * How many threads warm. Zero where warming is off.
	 */
	public int threads() {
		return threads;
	}

	/**
	 * How many indexes are waiting for a thread. What
	 * {@link Meters#FACET_WARM_QUEUED} reports.
	 */
	public int queued() {
		return queued.size();
	}

	/**
	 * Queue an index whose reader was just replaced. An index already waiting
	 * is left where it is, since the warm it waits for reads whatever reader
	 * the index holds when its turn comes.
	 *
	 * @param index
	 */
	public void warm(Index index) {
		if(pool == null) {
			return;
		}

		if(!queued.add(index)) {
			return;
		}

		synchronized(this) {
			active++;
		}

		try {
			pool.execute(() -> run(index));
		} catch(RejectedExecutionException e) {
			// The pool is closing, and the first search builds instead
			queued.remove(index);
			finished();
		}
	}

	private void run(Index index) {
		/*
		 * Taken out of the queue before the warm starts, so that an index
		 * reopening while it is being warmed queues itself again - the warm
		 * running now notices and leaves the newer reader to that one.
		 */
		queued.remove(index);

		var started = System.nanoTime();
		try {
			var outcome = index.warmFacets();
			metrics.recordFacetWarm(
				switch(outcome) {
					case COMPLETED -> Meters.OUTCOME_SUCCESS;
					case SUPERSEDED -> Meters.OUTCOME_SUPERSEDED;
				},
				System.nanoTime() - started
			);
		} catch(Throwable t) {
			metrics.recordFacetWarm(Meters.OUTCOME_ERROR, System.nanoTime() - started);

			/*
			 * Not what a search asked for, so nobody is waiting on it: the
			 * first search builds what the warm did not, and pays what it
			 * would have without warming.
			 */
			logger.atWarn()
				.addKeyValue("index", index.getId())
				.setCause(t)
				.log("Could not prepare the facets of a reopened index; " + t.getMessage());
		} finally {
			finished();
		}
	}

	private synchronized void finished() {
		active--;
		if(active == 0) {
			notifyAll();
		}
	}

	/**
	 * Wait until nothing is queued or running, for a test of what a warm
	 * leaves for a search to do.
	 *
	 * @param timeout
	 *   how long to wait at most
	 * @return
	 *   whether the warmer went idle within the time, rather than the time
	 *   running out first
	 * @throws InterruptedException
	 */
	public synchronized boolean awaitIdle(Duration timeout) throws InterruptedException {
		var deadline = System.nanoTime() + timeout.toNanos();
		while(active > 0) {
			var left = deadline - System.nanoTime();
			if(left <= 0) {
				return false;
			}

			wait(Math.max(1, left / 1_000_000));
		}

		return true;
	}

	/**
	 * Stop the threads. A warm queued after this is dropped, and the first
	 * search builds what it needs.
	 */
	@Override
	@PreDestroy
	public void close() {
		if(pool != null) {
			pool.shutdownNow();
		}
	}

	/**
	 * How a warm ended.
	 */
	public enum Outcome {
		/**
		 * Every faceted field of the reader was prepared.
		 */
		COMPLETED,

		/**
		 * The reader was replaced or the index closed before every field was
		 * prepared, and the warm gave up on it. The index has queued itself
		 * again for the newer reader where there is one.
		 */
		SUPERSEDED
	}
}
