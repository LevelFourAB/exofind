package se.l4.exofind.engine.index;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import se.l4.exofind.engine.index.state.SyncConflictException;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.metrics.Meters;
import se.l4.exofind.engine.metrics.RequestMetrics;

/**
 * Commits an index on its own, so that what has been indexed becomes
 * searchable without anything asking for it.
 *
 * <p>Changes are counted as they are recorded and committed when either
 * trigger of the {@link CommitPolicy} is reached. At most one commit runs at a
 * time however many changes arrive: a change recorded while a commit is
 * running is committed by the next one rather than starting one of its own, so
 * a burst costs the same number of commits as a trickle does.
 *
 * <p>A commit that fails is retried, waiting twice as long before each attempt
 * up to a minute, and the changes stay counted so that nothing is lost by a
 * remote that is briefly unreachable. Two failures are not retried, because
 * neither gets better by trying again: a {@link SyncConflictException} means
 * another node has written the remote and this copy is about to be replaced by
 * a pull, and an index that has become read-only or closed is no longer this
 * node's to commit. Both give up the changes they were counting.
 *
 * <p>Safe for concurrent use. {@link #recordChange(long)} never blocks on a
 * commit, so it can be called while the index is being written.
 */
public class IndexCommitManager {
	private static final Log logger = Log.of(IndexCommitManager.class);

	/**
	 * How long to wait before trying a failed commit again, doubled for every
	 * failure after the first.
	 */
	private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(1);

	private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(1);

	/**
	 * How long {@link #close()} waits for a commit that is already running.
	 * Reached only by a push that is not coming back, where waiting longer
	 * keeps the index and the thread closing it alive for no gain.
	 */
	private static final Duration CLOSE_WAIT = Duration.ofMinutes(1);

	private final Index index;
	private final ScheduledExecutorService executor;
	private final CommitPolicy policy;
	private final RequestMetrics metrics;

	private final ReentrantLock lock;

	/**
	 * Signalled whenever a commit finishes, so that closing can wait for one
	 * that is already running.
	 */
	private final Condition idle;

	/**
	 * Changes recorded since the last commit that took them. Changes recorded
	 * while a commit runs are not part of it and stay counted here.
	 */
	private long pendingChanges;

	/**
	 * When the oldest change that has not been committed arrived, as
	 * {@link System#nanoTime()}. Only meaningful while {@link #pendingChanges}
	 * is above zero. After a commit that left changes behind this is when that
	 * commit started, which is the earliest they can have arrived.
	 */
	private long oldestPendingAt;

	private boolean committing;
	private int failures;
	private boolean closed;

	/**
	 * The armed time trigger, or {@code null} when none is waiting to fire.
	 */
	private ScheduledFuture<?> timer;

	/**
	 * @param executor
	 *   where commits and the waiting between them run. A commit pushes to the
	 *   remote, so this thread blocks for as long as that takes
	 */
	public IndexCommitManager(
		Index index,
		ScheduledExecutorService executor,
		CommitPolicy policy
	) {
		this(index, executor, policy, RequestMetrics.none());
	}

	/**
	 * @param metrics
	 *   told how long each commit took and whether it succeeded
	 */
	public IndexCommitManager(
		Index index,
		ScheduledExecutorService executor,
		CommitPolicy policy,
		RequestMetrics metrics
	) {
		this.index = index;
		this.executor = executor;
		this.policy = policy;
		this.metrics = metrics;

		this.lock = new ReentrantLock();
		this.idle = lock.newCondition();
	}

	/**
	 * Record that the contents of the index have changed, which may start a
	 * commit or arm one to happen later.
	 *
	 * @param changes
	 *   how many documents the change covered. Zero and below are ignored, so a
	 *   removal that matched nothing does not start a commit
	 */
	public void recordChange(long changes) {
		if(changes <= 0 || !policy.isEnabled()) {
			return;
		}

		lock.lock();
		try {
			if(closed) {
				return;
			}

			if(pendingChanges == 0) {
				oldestPendingAt = System.nanoTime();
			}

			pendingChanges += changes;

			if(reachedChangeTrigger()) {
				startCommit(Duration.ZERO);
			} else {
				armTimer();
			}
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Get how long the oldest change waiting for a commit has waited.
	 *
	 * @return
	 *   {@link Duration#ZERO} when nothing is waiting
	 */
	public Duration getPendingAge() {
		lock.lock();
		try {
			if(pendingChanges == 0) {
				return Duration.ZERO;
			}

			return Duration.ofNanos(System.nanoTime() - oldestPendingAt);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Stop committing on this index and wait for a commit that is already
	 * running. What is still uncommitted stays uncommitted - closing an index
	 * is what decides whether it is flushed first.
	 */
	public void close() {
		lock.lock();
		try {
			closed = true;
			cancelTimer();

			var deadline = System.nanoTime() + CLOSE_WAIT.toNanos();
			while(committing) {
				var remaining = deadline - System.nanoTime();
				if(remaining <= 0) {
					logger.atWarn()
						.addKeyValue("index", index.getId())
						.log("A commit is still running " + CLOSE_WAIT
							+ " after the index was closed, continuing without it");
					return;
				}

				try {
					idle.awaitNanos(remaining);
				} catch(InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		} finally {
			lock.unlock();
		}
	}

	/**
	 * How many changes are waiting for a commit.
	 */
	long pendingChanges() {
		lock.lock();
		try {
			return pendingChanges;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Wait until nothing is waiting to be committed and no commit is running.
	 *
	 * @return
	 *   whether it became idle before the timeout
	 */
	boolean awaitIdle(Duration timeout) throws InterruptedException {
		lock.lock();
		try {
			var deadline = System.nanoTime() + timeout.toNanos();
			while(committing || pendingChanges > 0) {
				var remaining = deadline - System.nanoTime();
				if(remaining <= 0) {
					return false;
				}

				idle.awaitNanos(remaining);
			}

			return true;
		} finally {
			lock.unlock();
		}
	}

	private boolean reachedChangeTrigger() {
		return policy.maxChanges() > 0 && pendingChanges >= policy.maxChanges();
	}

	/**
	 * Arm the time trigger for the oldest change that is waiting, unless one is
	 * already armed or a commit is running - a commit that leaves changes
	 * behind arms it again itself.
	 */
	private void armTimer() {
		if(timer != null || committing || policy.maxInterval().isZero()) {
			return;
		}

		var waited = System.nanoTime() - oldestPendingAt;
		var delay = Math.max(0, policy.maxInterval().toNanos() - waited);

		try {
			timer = executor.schedule(this::onTimer, delay, TimeUnit.NANOSECONDS);
		} catch(RejectedExecutionException e) {
			// The node is shutting down, so there is nothing left to commit for
		}
	}

	private void cancelTimer() {
		if(timer != null) {
			timer.cancel(false);
			timer = null;
		}
	}

	private void onTimer() {
		lock.lock();
		try {
			timer = null;

			if(closed || pendingChanges == 0) {
				return;
			}

			startCommit(Duration.ZERO);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Hand a commit to the executor, unless one is already running - the one
	 * running takes whatever has arrived since it started when it finishes,
	 * which is what keeps a burst of changes from queueing a commit each.
	 */
	private void startCommit(Duration delay) {
		if(committing || closed) {
			return;
		}

		cancelTimer();
		committing = true;

		try {
			executor.schedule(this::runCommit, delay.toNanos(), TimeUnit.NANOSECONDS);
		} catch(RejectedExecutionException e) {
			committing = false;
			idle.signalAll();
		}
	}

	private void runCommit() {
		long attempted;
		long startedAt;

		lock.lock();
		try {
			attempted = pendingChanges;
			startedAt = System.nanoTime();

			if(closed || attempted == 0) {
				finish(Outcome.NOTHING_TO_DO, 0, startedAt);
				return;
			}
		} finally {
			lock.unlock();
		}

		/*
		 * Committing pushes to the remote and takes the write lock of the
		 * index, so it runs without this manager's lock - a caller recording a
		 * change holds a read lock of the index while it takes ours.
		 */
		var outcome = commit(attempted);

		lock.lock();
		try {
			finish(outcome, attempted, startedAt);
		} finally {
			lock.unlock();
		}
	}

	private Outcome commit(long attempted) {
		logger.atDebug()
			.addKeyValue("index", index.getId())
			.addKeyValue("changes", attempted)
			.log("Committing index");

		/*
		 * Decided from the changes this commit set out to take, which was read
		 * under the lock. Reading the backlog here would race with a change
		 * being recorded while the commit runs.
		 */
		var trigger = policy.maxChanges() > 0 && attempted >= policy.maxChanges()
			? Meters.TRIGGER_CHANGES
			: Meters.TRIGGER_INTERVAL;

		var started = System.nanoTime();
		try {
			index.commit();
			metrics.recordCommit(trigger, System.nanoTime() - started, true);
			return Outcome.COMMITTED;
		} catch(SyncConflictException e) {
			metrics.recordCommit(trigger, System.nanoTime() - started, false);

			/*
			 * The push found the remote written by someone else and gave up the
			 * local changes for it; the index is now waiting to be pulled over.
			 * Counting the changes on would commit them against the pulled copy.
			 */
			logger.atWarn()
				.addKeyValue("index", index.getId())
				.addKeyValue("changes", attempted)
				.log("Giving up on committing, the remote was changed by another node; "
					+ e.getMessage());

			return Outcome.ABANDONED;
		} catch(IndexReadonlyException | IndexClosedException | IndexOutOfDateException e) {
			metrics.recordCommit(trigger, System.nanoTime() - started, false);

			logger.atInfo()
				.addKeyValue("index", index.getId())
				.addKeyValue("changes", attempted)
				.log("Giving up on committing, the index is no longer ours to commit; "
					+ e.getMessage());

			return Outcome.ABANDONED;
		} catch(IOException | RuntimeException e) {
			metrics.recordCommit(trigger, System.nanoTime() - started, false);

			logger.atWarn()
				.addKeyValue("index", index.getId())
				.addKeyValue("changes", attempted)
				.setCause(e)
				.log("Could not commit index, trying again; " + e.getMessage());

			return Outcome.FAILED;
		}
	}

	/**
	 * Take in what came of a commit and decide what happens next.
	 *
	 * @param attempted
	 *   how many changes the commit set out to take, which is what it took when
	 *   it succeeded. Anything recorded while it ran is not part of that and is
	 *   left waiting
	 * @param startedAt
	 *   when the commit started, which is the earliest the changes left waiting
	 *   can have arrived
	 */
	private void finish(Outcome outcome, long attempted, long startedAt) {
		committing = false;

		switch(outcome) {
			case COMMITTED -> {
				pendingChanges = Math.max(0, pendingChanges - attempted);
				oldestPendingAt = startedAt;
				failures = 0;
			}
			case ABANDONED -> {
				pendingChanges = 0;
				failures = 0;
			}
			case FAILED -> failures++;
			case NOTHING_TO_DO -> {
				// Nothing was counted, so nothing changes
			}
		}

		idle.signalAll();

		if(closed || pendingChanges == 0) {
			return;
		}

		if(outcome == Outcome.FAILED) {
			startCommit(retryDelay());
		} else if(reachedChangeTrigger()) {
			startCommit(Duration.ZERO);
		} else {
			armTimer();
		}
	}

	private Duration retryDelay() {
		var doublings = Math.min(failures - 1, 16);
		var delay = FIRST_RETRY_DELAY.multipliedBy(1L << doublings);

		return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
	}

	private enum Outcome {
		COMMITTED,
		/**
		 * The commit failed in a way that another attempt could get past.
		 */
		FAILED,
		/**
		 * The commit failed in a way that no attempt gets past, so the changes
		 * it was counting are given up.
		 */
		ABANDONED,
		/**
		 * There was nothing to commit by the time the commit ran.
		 */
		NOTHING_TO_DO
	}
}
