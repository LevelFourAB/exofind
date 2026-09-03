package se.l4.exofind.engine.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.metrics.Meters;
import se.l4.exofind.engine.metrics.RequestMetrics;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The threads a node lends to a search, so that one search spreads its work
 * over more than one core.
 *
 * <p>A search runs on the thread of its request. It hands what can run apart
 * - the segments it collects, the facets it counts, the slices Lucene ranks -
 * to the pool held here, and the request thread runs every piece the pool has
 * not started by the time it gets there. A node with idle cores answers one
 * search sooner. A node whose cores are all busy answers as it would with no
 * pool: the pieces run on the request thread, one after another, and the pool
 * costs the submission of each and nothing more. This is what lets the pool
 * be sized to the machine rather than to the load, and why
 * {@code EXOFIND_SEARCH_THREADS} defaults to the number of cores.
 *
 * <p>Handing a piece over wakes a thread, which costs about as much as
 * counting or pouring {@link #DEFAULT_PIECE_WORK} matches, so a caller sizes
 * its pieces by that and a batch holding less than two pieces of work runs on
 * the request thread - see {@link #invokeAll(List, long)}. A search counting a
 * handful of matches answers as fast as it did with no pool, and only a
 * search with work worth spreading pays for the spreading.
 *
 * <p>A pool thread runs a piece under the {@link SearchDeadline} of the request
 * thread that handed it over, so the pieces of one search read one budget and
 * stop together. Search work handed to any other executor runs without a
 * budget, which nothing reports; every thread a search borrows has to come
 * from here.
 *
 * <p>The pool is the node's, shared by every search on it. Reindexing and the
 * writers' own searches over their merge readers stay on their own threads:
 * they are not what a caller is waiting on, and a reindex borrowing search
 * threads would slow the searches the pool exists to speed up.
 *
 * <p>Every piece handed over is counted under {@link Meters#SEARCH_PIECES},
 * by which kind of thread ran it. Pieces running on the request thread while
 * the pool holds threads are the pool being busy, which is what says whether
 * a node has the cores its setting names.
 *
 * <p>Safe for concurrent use.
 */
@ApplicationScoped
public class SearchThreads implements AutoCloseable {
	/**
	 * The setting value that sizes the pool to the cores the process may use.
	 */
	public static final String AUTO = "auto";

	/**
	 * How much work one piece is worth handing over with, in the matches its
	 * caller counts or pours. Waking a thread costs on the order of ten
	 * microseconds, which is what counting this many matches for one facet
	 * costs, so a piece of this size gains from a thread of its own and a
	 * smaller one mostly pays for the wake-up. A batch is handed over from two
	 * pieces of work, since less than that has nothing to split.
	 */
	public static final int DEFAULT_PIECE_WORK = 1 << 13;

	private final RequestMetrics metrics;
	private final int threads;
	private final long pieceWork;

	/**
	 * The pool, or {@code null} where every search runs on its request thread
	 * alone.
	 */
	private final ExecutorService pool;

	/**
	 * The pool as Lucene runs on it, carrying the budget of the thread that
	 * hands a task over - see {@link #executor()}.
	 */
	private final Executor executor;

	@Inject
	public SearchThreads(
		RequestMetrics metrics,
		@ConfigProperty(name = "exofind.search.threads", defaultValue = AUTO)
		String threads
	) {
		this(metrics, parse(threads));
	}

	/**
	 * Hold a pool of the given size.
	 *
	 * @param metrics
	 *   told about every piece handed over
	 * @param threads
	 *   how many threads to hold. Zero holds none, and every search then runs
	 *   on its request thread alone
	 */
	public SearchThreads(RequestMetrics metrics, int threads) {
		this(metrics, threads, DEFAULT_PIECE_WORK);
	}

	/**
	 * Hold a pool of the given size, handing work over from a size of the
	 * caller's choosing - for a test that wants the pool used however little
	 * the work.
	 *
	 * @param pieceWork
	 *   how much work one piece is worth handing over with, see
	 *   {@link #DEFAULT_PIECE_WORK}. Zero hands every batch over
	 */
	public SearchThreads(RequestMetrics metrics, int threads, long pieceWork) {
		if(threads < 0) {
			throw new IllegalArgumentException("threads can not be negative, got " + threads);
		}

		if(pieceWork < 0) {
			throw new IllegalArgumentException("pieceWork can not be negative, got " + pieceWork);
		}

		this.metrics = metrics;
		this.threads = threads;
		this.pieceWork = pieceWork;

		if(threads == 0) {
			this.pool = null;
			this.executor = null;
			return;
		}

		var numbered = new AtomicInteger();
		this.pool = Executors.newFixedThreadPool(threads, runnable -> {
			var thread = new Thread(runnable, "search-" + numbered.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});

		this.executor = runnable -> {
			var budget = SearchDeadline.current();
			pool.execute(() -> {
				try(var scope = SearchDeadline.attach(budget)) {
					runnable.run();
				}
			});
		};
	}

	/**
	 * Threads for an index opened outside a node: none, so every search runs
	 * on the thread that asked for it.
	 */
	public static SearchThreads inline() {
		return new SearchThreads(RequestMetrics.none(), 0);
	}

	/**
	 * Read the setting: {@link #AUTO} for the cores the process may use, which
	 * honours a container's CPU limit, or a count of threads.
	 *
	 * @throws IllegalArgumentException
	 *   if the value is neither
	 */
	public static int parse(String setting) {
		var trimmed = setting == null ? "" : setting.trim();
		if(trimmed.equalsIgnoreCase(AUTO)) {
			return Runtime.getRuntime().availableProcessors();
		}

		int threads;
		try {
			threads = Integer.parseInt(trimmed);
		} catch(NumberFormatException e) {
			throw new IllegalArgumentException(
				"exofind.search.threads must be `auto` or a number of threads, got `" + setting + "`"
			);
		}

		if(threads < 0) {
			throw new IllegalArgumentException(
				"exofind.search.threads can not be negative, got " + threads
			);
		}

		return threads;
	}

	/**
	 * How many threads the pool holds. Zero where every search runs on its
	 * request thread alone.
	 */
	public int threads() {
		return threads;
	}

	/**
	 * How many pieces one search is worth splitting into: one per thread of
	 * the pool, and one for the request thread that runs alongside them.
	 * Splitting finer than this hands nothing to anyone and pays a submission
	 * per piece.
	 */
	public int pieces() {
		return threads + 1;
	}

	/**
	 * How much work one piece is worth handing over with, in the matches the
	 * caller counts or pours - see {@link #DEFAULT_PIECE_WORK}. What a caller
	 * splitting its work sizes each piece by, so that no piece is smaller than
	 * the handover is worth, and a batch is handed over from twice this.
	 */
	public long pieceWork() {
		return pieceWork;
	}

	/**
	 * Run the pieces on the calling thread, one after another, and answer
	 * what each returned in the order they were given. What
	 * {@link #invokeAll(List, long)} does for a batch too small to hand over.
	 *
	 * @throws IOException
	 *   if a piece threw one; the pieces after it do not run
	 */
	public static <T> List<T> invokeHere(List<? extends Callable<T>> pieces) throws IOException {
		var results = new ArrayList<T>(pieces.size());
		for(var piece : pieces) {
			results.add(call(piece));
		}

		return results;
	}

	/**
	 * The executor to open an {@link org.apache.lucene.search.IndexSearcher}
	 * with, or {@code null} where the pool holds no threads. A task runs
	 * under the budget of the thread that handed it over.
	 */
	public Executor executor() {
		return executor;
	}

	/**
	 * Run the pieces of a search and answer what each returned, in the order
	 * they were given.
	 *
	 * <p>Every piece is handed to the pool, and the calling thread then runs
	 * each piece the pool has not started, so the call returns once every
	 * piece has run whether the pool had a thread free or not. A piece on a
	 * pool thread runs under the budget the calling thread has open, see
	 * {@link SearchDeadline}.
	 *
	 * <p>A batch holding less than two pieces of work - twice
	 * {@link #pieceWork()} - runs on the calling thread instead, the way it
	 * would with no pool: waking a thread for it would cost more than the
	 * work, and there is nothing in it to split.
	 *
	 * <p>Every piece handed over runs even when one of them fails. What the
	 * first failure threw is rethrown once the rest have run, with the others
	 * suppressed under it.
	 *
	 * @param pieces
	 *   the work, each piece independent of the others
	 * @param work
	 *   how much work the pieces hold together, in the matches the caller
	 *   counts or pours - what decides whether the batch is worth handing
	 *   over
	 * @return
	 *   what each piece returned, aligned with {@code pieces}
	 * @throws IOException
	 *   if a piece threw one
	 */
	public <T> List<T> invokeAll(List<? extends Callable<T>> pieces, long work)
		throws IOException
	{
		if(pieces.isEmpty()) {
			return List.of();
		}

		if(pool == null || pieces.size() == 1 || work < 2 * pieceWork) {
			return invokeHere(pieces);
		}

		var results = new ArrayList<T>(pieces.size());
		var budget = SearchDeadline.current();
		var handed = new ArrayList<Piece<T>>(pieces.size());
		for(var callable : pieces) {
			var piece = new Piece<>(callable);
			handed.add(piece);

			try {
				pool.execute(() -> piece.runUnder(budget));
			} catch(RejectedExecutionException e) {
				// The pool is closing; the piece runs on this thread below
			}
		}

		var onRequestThread = 0;
		for(var piece : handed) {
			if(piece.runHere()) {
				onRequestThread++;
			}
		}

		metrics.recordSearchPieces(Meters.THREAD_REQUEST, onRequestThread);
		metrics.recordSearchPieces(Meters.THREAD_POOL, handed.size() - onRequestThread);

		Throwable failure = null;
		var interrupted = false;
		for(var piece : handed) {
			while(true) {
				try {
					results.add(piece.task.get());
					break;
				} catch(ExecutionException e) {
					failure = suppress(failure, e.getCause());
					break;
				} catch(InterruptedException e) {
					/*
					 * The pieces still running read the reader this search holds
					 * open, so they are waited for rather than left behind, and
					 * the interruption is handed back once they are done.
					 */
					interrupted = true;
				}
			}
		}

		if(interrupted) {
			Thread.currentThread().interrupt();
		}

		if(failure != null) {
			throw rethrow(failure);
		}

		return results;
	}

	/**
	 * Stop the pool. A search handing work over after this runs every piece
	 * on its own thread.
	 */
	@Override
	@PreDestroy
	public void close() {
		if(pool != null) {
			pool.shutdownNow();
		}
	}

	private static <T> T call(Callable<T> piece) throws IOException {
		try {
			return piece.call();
		} catch(IOException | RuntimeException | Error e) {
			throw e;
		} catch(Exception e) {
			throw new IOException(e);
		}
	}

	private static Throwable suppress(Throwable first, Throwable next) {
		if(first == null) {
			return next;
		}

		first.addSuppressed(next);
		return first;
	}

	private static IOException rethrow(Throwable failure) throws IOException {
		if(failure instanceof IOException e) {
			throw e;
		}

		if(failure instanceof RuntimeException e) {
			throw e;
		}

		if(failure instanceof Error e) {
			throw e;
		}

		throw new IOException(failure);
	}

	/**
	 * One piece of a search, run by whichever thread claims it first: a pool
	 * thread, or the request thread once it has handed every piece over.
	 */
	private static final class Piece<T> {
		private final FutureTask<T> task;
		private final AtomicBoolean claimed;

		Piece(Callable<T> callable) {
			this.task = new FutureTask<>(callable);
			this.claimed = new AtomicBoolean();
		}

		/**
		 * Run on a pool thread, under the budget of the thread that handed
		 * the piece over. Does nothing where the request thread got there
		 * first.
		 */
		void runUnder(SearchDeadline.Budget budget) {
			if(!claimed.compareAndSet(false, true)) {
				return;
			}

			try(var scope = SearchDeadline.attach(budget)) {
				task.run();
			}
		}

		/**
		 * Run on the request thread, which already holds the budget.
		 *
		 * @return
		 *   whether this thread ran the piece rather than finding it claimed
		 */
		boolean runHere() {
			if(!claimed.compareAndSet(false, true)) {
				return false;
			}

			task.run();
			return true;
		}
	}
}
