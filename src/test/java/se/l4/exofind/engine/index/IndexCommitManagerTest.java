package se.l4.exofind.engine.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.index.state.SyncConflictException;

class IndexCommitManagerTest {
	/**
	 * How long a test waits for something it expects to happen. Long enough to
	 * cover the first retry delay, which one of the tests waits through.
	 */
	private static final Duration WAIT = Duration.ofSeconds(10);

	/**
	 * How long a test waits to satisfy itself that something is not going to
	 * happen.
	 */
	private static final Duration SETTLE = Duration.ofMillis(200);

	private Index index;
	private ScheduledExecutorService executor;

	@BeforeEach
	void setUp() {
		index = mock(Index.class);
		when(index.getId()).thenReturn("test");

		// One thread, the way an index runs its background work
		executor = Executors.newSingleThreadScheduledExecutor();
	}

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
	}

	private IndexCommitManager manager(int maxChanges, Duration maxInterval) {
		return new IndexCommitManager(
			index,
			executor,
			new CommitPolicy(maxChanges, maxInterval)
		);
	}

	@Test
	void commitsOnceTheIntervalHasPassed() throws Exception {
		var manager = manager(0, Duration.ofMillis(50));

		manager.recordChange(1);

		assertTrue(manager.awaitIdle(WAIT));
		verify(index).commit();
	}

	@Test
	void commitsOnceEnoughChangesAreWaiting() throws Exception {
		// An interval far enough out that only the change count can commit
		var manager = manager(3, Duration.ofHours(1));

		manager.recordChange(2);
		assertFalse(
			manager.awaitIdle(SETTLE),
			"committed before the change count was reached"
		);

		manager.recordChange(1);

		assertTrue(manager.awaitIdle(WAIT));
		verify(index).commit();
	}

	@Test
	void aChangeOfNothingCommitsNothing() throws Exception {
		var manager = manager(1, Duration.ofMillis(50));

		manager.recordChange(0);

		assertTrue(manager.awaitIdle(SETTLE));
		verify(index, never()).commit();
	}

	@Test
	void anIndexThatOnlyCommitsWhenAskedNeverCommitsOnItsOwn() throws Exception {
		var manager = new IndexCommitManager(index, executor, CommitPolicy.disabled());

		manager.recordChange(100);

		assertEquals(0, manager.pendingChanges());
		Thread.sleep(SETTLE.toMillis());
		verify(index, never()).commit();
	}

	@Test
	void changesArrivingDuringACommitAreTakenByOneMoreCommit() throws Exception {
		var started = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var firstCommit = new AtomicBoolean(true);

		doAnswer(invocation -> {
			if(firstCommit.getAndSet(false)) {
				started.countDown();
				release.await();
			}

			return null;
		}).when(index).commit();

		// Every change reaches the trigger, so an uncoalesced burst is a commit each
		var manager = manager(1, Duration.ZERO);

		manager.recordChange(1);
		assertTrue(started.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));

		for(var i = 0; i < 50; i++) {
			manager.recordChange(1);
		}

		release.countDown();

		assertTrue(manager.awaitIdle(WAIT));
		verify(index, times(2)).commit();
		assertEquals(0, manager.pendingChanges());
	}

	@Test
	void aFailedCommitIsTriedAgainWithTheChangesStillWaiting() throws Exception {
		var firstCommit = new AtomicBoolean(true);

		doAnswer(invocation -> {
			if(firstCommit.getAndSet(false)) {
				throw new IOException("the remote is not answering");
			}

			return null;
		}).when(index).commit();

		var manager = manager(1, Duration.ZERO);
		manager.recordChange(4);

		assertTrue(manager.awaitIdle(WAIT));
		verify(index, times(2)).commit();
		assertEquals(0, manager.pendingChanges());
	}

	@Test
	void aConflictGivesUpTheChangesWithoutTryingAgain() throws Exception {
		doThrow(new SyncConflictException("the remote was written by another node"))
			.when(index).commit();

		var manager = manager(1, Duration.ZERO);
		manager.recordChange(3);

		assertTrue(manager.awaitIdle(WAIT));

		/*
		 * Nothing is left counted, which is what says no retry was armed - a
		 * retry only ever runs for changes that are still waiting.
		 */
		assertEquals(0, manager.pendingChanges());
		verify(index, times(1)).commit();
	}

	@Test
	void anIndexThatIsNoLongerOursGivesUpTheChangesWithoutTryingAgain() throws Exception {
		doThrow(new IndexReadonlyException("test")).when(index).commit();

		var manager = manager(1, Duration.ZERO);
		manager.recordChange(3);

		assertTrue(manager.awaitIdle(WAIT));
		assertEquals(0, manager.pendingChanges());
		verify(index, times(1)).commit();
	}

	@Test
	void mergesFinishedAfterACommitAreCommittedOnTheirOwn() throws Exception {
		// The first check finds what a merge produced, the one after finds nothing
		when(index.hasUncommittedLuceneChanges()).thenReturn(true, false);

		var manager = manager(0, Duration.ofMillis(25));
		manager.recordChange(1);

		verify(index, timeout(WAIT.toMillis()).times(2)).commit();

		Thread.sleep(SETTLE.toMillis());
		verify(index, times(2)).commit();
	}

	@Test
	void aWriterWithNothingUncommittedIsLeftAlone() throws Exception {
		var manager = manager(0, Duration.ofMillis(25));
		manager.recordChange(1);

		assertTrue(manager.awaitIdle(WAIT));

		Thread.sleep(SETTLE.toMillis());
		verify(index, times(1)).commit();
	}

	@Test
	void aMergeStillRunningIsCheckedForAgain() throws Exception {
		when(index.hasPendingMerges()).thenReturn(true, false);
		when(index.hasUncommittedLuceneChanges()).thenReturn(false, true, false);

		var manager = manager(0, Duration.ofMillis(25));
		manager.recordChange(1);

		verify(index, timeout(WAIT.toMillis()).times(2)).commit();
	}

	@Test
	void aDisabledIntervalTriggerNeverChecksForMerges() throws Exception {
		when(index.hasUncommittedLuceneChanges()).thenReturn(true);

		var manager = manager(1, Duration.ZERO);
		manager.recordChange(1);

		assertTrue(manager.awaitIdle(WAIT));

		Thread.sleep(SETTLE.toMillis());
		verify(index, times(1)).commit();
	}

	@Test
	void aFailedMergeCommitIsCheckedForAgain() throws Exception {
		// The check that follows the failed commit still finds the merge
		when(index.hasUncommittedLuceneChanges()).thenReturn(true, true, false);

		var commits = new AtomicInteger();
		doAnswer(invocation -> {
			if(commits.incrementAndGet() == 2) {
				throw new IOException("the remote is not answering");
			}

			return null;
		}).when(index).commit();

		var manager = manager(0, Duration.ofMillis(25));
		manager.recordChange(1);

		// One for the change, a failed one for the merges, and the check after it
		verify(index, timeout(WAIT.toMillis()).times(3)).commit();
	}

	@Test
	void aFailedMergeCommitDoesNotHoldClosingOpen() throws Exception {
		when(index.hasUncommittedLuceneChanges()).thenReturn(true);

		var commits = new AtomicInteger();
		var failed = new CountDownLatch(1);
		doAnswer(invocation -> {
			if(commits.incrementAndGet() >= 2) {
				failed.countDown();
				throw new IOException("the remote is not answering");
			}

			return null;
		}).when(index).commit();

		var manager = manager(0, Duration.ofMillis(25));
		manager.recordChange(1);

		assertTrue(failed.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));

		var started = System.nanoTime();
		manager.close();
		var waited = Duration.ofNanos(System.nanoTime() - started);

		assertTrue(
			waited.compareTo(SETTLE) < 0,
			"closing waited " + waited + " for a commit that carried no changes"
		);
	}

	@Test
	void aCommitGivenUpOnLeavesTheMergeCheckOff() throws Exception {
		// The check would find something to commit if it were still armed
		when(index.hasUncommittedLuceneChanges()).thenReturn(true);

		var commits = new AtomicInteger();
		doAnswer(invocation -> {
			if(commits.incrementAndGet() == 2) {
				throw new SyncConflictException("the remote was written by another node");
			}

			return null;
		}).when(index).commit();

		// Changes commit at once, so the second one lands inside the interval
		var interval = Duration.ofMillis(500);
		var manager = manager(1, interval);

		manager.recordChange(1);
		assertTrue(manager.awaitIdle(WAIT));

		// Given up on while the check armed by the first commit is still waiting
		manager.recordChange(1);
		assertTrue(manager.awaitIdle(WAIT));

		Thread.sleep(interval.plus(SETTLE).toMillis());
		verify(index, times(2)).commit();
	}

	@Test
	void nothingIsCommittedAfterClosing() throws Exception {
		/*
		 * The single background thread is held for the whole of this, so the
		 * armed trigger can only ever run after the close has cancelled it.
		 */
		var occupied = new CountDownLatch(1);
		executor.execute(() -> {
			try {
				occupied.await();
			} catch(InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});

		var manager = manager(0, Duration.ofMillis(10));
		manager.recordChange(1);
		manager.close();

		occupied.countDown();

		manager.recordChange(1);

		Thread.sleep(SETTLE.toMillis());
		verify(index, never()).commit();
	}

	@Test
	void closingWaitsForACommitThatIsAlreadyRunning() throws Exception {
		var started = new CountDownLatch(1);
		var release = new CountDownLatch(1);

		doAnswer(invocation -> {
			started.countDown();
			release.await();
			return null;
		}).when(index).commit();

		var manager = manager(1, Duration.ZERO);
		manager.recordChange(1);
		assertTrue(started.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));

		var closing = CompletableFuture.runAsync(manager::close);

		assertThrows(
			TimeoutException.class,
			() -> closing.get(SETTLE.toMillis(), TimeUnit.MILLISECONDS),
			"closing returned while a commit was still running"
		);

		release.countDown();
		closing.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
	}
}
