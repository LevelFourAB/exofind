package se.l4.exofind.engine.index;

import java.time.Duration;

import org.apache.lucene.index.QueryTimeout;

/**
 * Time budget for the searches running on the current thread.
 *
 * <p>A caller opens a scope around the work it wants bounded, and asks the
 * scope afterwards whether the budget ran out:
 *
 * <pre>{@code
 * try(var scope = SearchDeadline.start(timeout)) {
 *     var result = index.search(request);
 *     if(scope.exceeded()) {
 *         throw new SearchTimeoutException(name, timeout);
 *     }
 *     return response(result);
 * }
 * }</pre>
 *
 * <p>{@link Index} hands {@link #INSTANCE} to every searcher it opens, and
 * Lucene asks it while it collects. Collection over a budget that has run out
 * stops where it is and returns the results found so far, so a caller that
 * does not check {@link Scope#exceeded()} answers from part of the index
 * without knowing it. Nothing else observes the budget: the time spent
 * rewriting a query, reading stored fields, or highlighting a page falls
 * outside it.
 *
 * <p>The budget belongs to the thread that opened the scope. A search runs on
 * the thread that asked for it, and on the threads {@link SearchThreads} lends
 * it, which carry the budget of the requesting thread while they run its
 * work: the threads read one budget, so they stop together and the requesting
 * thread sees that they did. Work handed to any other thread runs without a
 * budget, and nothing reports that it did.
 */
public final class SearchDeadline implements QueryTimeout {
	/**
	 * The timeout to hand to Lucene. Stateless: it reads the budget of
	 * whichever thread asks.
	 */
	public static final SearchDeadline INSTANCE = new SearchDeadline();

	private static final ThreadLocal<Budget> CURRENT = new ThreadLocal<>();

	private SearchDeadline() {
	}

	/**
	 * Bound the searches this thread runs until the returned scope is closed.
	 *
	 * @param budget
	 *   how long the searches may collect for. {@code null}, zero and negative
	 *   durations open a scope that never runs out
	 * @return
	 *   the scope, to close on the thread that opened it
	 */
	public static Scope start(Duration budget) {
		if(budget == null || budget.isZero() || budget.isNegative()) {
			return attach(null);
		}

		return attach(new Budget(System.nanoTime() + budget.toNanos()));
	}

	/**
	 * The budget in force on this thread, to carry to another thread through
	 * {@link #attach(Budget)}. {@code null} on a thread with no scope open or
	 * one opened without a budget.
	 */
	static Budget current() {
		return CURRENT.get();
	}

	/**
	 * Bound the searches this thread runs by a budget another thread opened,
	 * until the returned scope is closed. The two threads read the same
	 * budget: either of them running past it stops both.
	 *
	 * @param budget
	 *   the budget to share, or {@code null} to run unbounded until the scope
	 *   is closed
	 * @return
	 *   the scope, to close on the thread that attached it
	 */
	static Scope attach(Budget budget) {
		var previous = CURRENT.get();

		if(budget == null) {
			CURRENT.remove();
		} else {
			CURRENT.set(budget);
		}

		return new Scope(budget, previous);
	}

	/**
	 * Whether a search on this thread has already run past the budget of the
	 * scope now open. Answers {@code false} on a thread with no scope open
	 * or one opened without a budget.
	 *
	 * <p>What decides whether the results of a search are worth keeping for
	 * the searches after it: collection over a spent budget stops where it
	 * is, so counts made from it describe part of the index and must not be
	 * answered again as if they were whole - see {@link FacetStates}.
	 *
	 * @return
	 */
	public static boolean exceeded() {
		var budget = CURRENT.get();
		return budget != null && budget.exceeded;
	}

	@Override
	public boolean shouldExit() {
		var budget = CURRENT.get();
		if(budget == null) {
			return false;
		}

		if(budget.exceeded) {
			return true;
		}

		if(System.nanoTime() - budget.deadline < 0) {
			return false;
		}

		budget.exceeded = true;
		return true;
	}

	/**
	 * A budget in force, and whether searching has already run past it.
	 *
	 * <p>Read by every thread the search runs on, so running past it is
	 * written through a volatile field: the other threads see the stop on
	 * their next window rather than when they run past it on their own.
	 */
	static final class Budget {
		private final long deadline;
		private volatile boolean exceeded;

		Budget(long deadline) {
			this.deadline = deadline;
		}
	}

	/**
	 * A budget in force on one thread until it is closed.
	 *
	 * <p>Not thread safe: close it on the thread that opened it, and read
	 * {@link #exceeded()} there.
	 */
	public static final class Scope implements AutoCloseable {
		private final Budget budget;
		private final Budget previous;

		Scope(Budget budget, Budget previous) {
			this.budget = budget;
			this.previous = previous;
		}

		/**
		 * Whether a search ran past the budget while this scope was open. A
		 * scope opened without a budget always answers {@code false}.
		 *
		 * <p>Stays answerable after the scope is closed.
		 */
		public boolean exceeded() {
			return budget != null && budget.exceeded;
		}

		@Override
		public void close() {
			if(previous == null) {
				CURRENT.remove();
			} else {
				CURRENT.set(previous);
			}
		}
	}
}
