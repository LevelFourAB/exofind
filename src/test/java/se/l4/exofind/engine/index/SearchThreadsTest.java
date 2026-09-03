package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.metrics.RequestMetrics;

/**
 * Tests for the threads a search spreads over - that every piece runs once
 * whoever runs it, that a pool thread runs under the budget of the request,
 * and that a node with no threads still runs everything.
 */
public class SearchThreadsTest {
	private SearchThreads threads;

	@AfterEach
	void close() {
		if(threads != null) {
			threads.close();
		}
	}

	/**
	 * Open a pool that takes every batch however little work it holds.
	 */
	private SearchThreads open(int count) {
		threads = new SearchThreads(RequestMetrics.none(), count, 0);
		return threads;
	}

	@Test
	public void testTheDefaultPoolHandsOverFromAPieceOfWork() {
		var pool = open(2);
		assertThat(pool.pieceWork(), is(0L));

		pool.close();
		threads = new SearchThreads(RequestMetrics.none(), 2);
		assertThat(threads.pieceWork(), is((long) SearchThreads.DEFAULT_PIECE_WORK));
	}

	@Test
	public void testABatchWithTooLittleWorkStaysOnTheCaller() throws IOException {
		threads = new SearchThreads(RequestMetrics.none(), 2, 100);

		var caller = Thread.currentThread();
		var ranOn = new ArrayList<Thread>();
		// A piece of work is a hundred, and a batch is handed over from two of them
		var results = threads.invokeAll(List.of(
			piece(ranOn, 1),
			piece(ranOn, 2)
		), 199);

		assertThat(results, contains(1, 2));
		assertThat(ranOn, contains(caller, caller));
	}

	@Test
	public void testABatchWithEnoughWorkIsHandedOver() throws Exception {
		threads = new SearchThreads(RequestMetrics.none(), 2, 100);

		var started = new CountDownLatch(2);
		var pieces = new ArrayList<Callable<Thread>>();
		for(var i = 0; i < 2; i++) {
			pieces.add(() -> {
				started.countDown();
				started.await(10, TimeUnit.SECONDS);
				return Thread.currentThread();
			});
		}

		var ranOn = threads.invokeAll(pieces, 200);
		assertThat(ranOn.stream().distinct().count(), is(2L));
	}

	@Test
	public void testAutoIsTheCoresOfTheProcess() {
		assertThat(
			SearchThreads.parse("auto"),
			is(Runtime.getRuntime().availableProcessors())
		);
	}

	@Test
	public void testANumberIsRead() {
		assertThat(SearchThreads.parse("0"), is(0));
		assertThat(SearchThreads.parse(" 4 "), is(4));
	}

	@Test
	public void testOtherValuesAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> SearchThreads.parse("many"));
		assertThrows(IllegalArgumentException.class, () -> SearchThreads.parse("-1"));
	}

	@Test
	public void testNoThreadsRunsEveryPieceOnTheCaller() throws IOException {
		var inline = open(0);
		assertThat(inline.executor(), is((Object) null));
		assertThat(inline.pieces(), is(1));

		var caller = Thread.currentThread();
		var ranOn = new ArrayList<Thread>();
		var results = inline.invokeAll(List.of(
			piece(ranOn, 1),
			piece(ranOn, 2),
			piece(ranOn, 3)
		), 0);

		assertThat(results, contains(1, 2, 3));
		assertThat(ranOn, contains(caller, caller, caller));
	}

	@Test
	public void testResultsKeepTheOrderOfThePieces() throws IOException {
		var pool = open(4);

		var pieces = new ArrayList<Callable<Integer>>();
		for(var i = 0; i < 64; i++) {
			var value = i;
			pieces.add(() -> {
				Thread.sleep(value % 3);
				return value;
			});
		}

		var results = pool.invokeAll(pieces, 0);
		for(var i = 0; i < 64; i++) {
			assertThat(results.get(i), is(i));
		}
	}

	@Test
	public void testTheCallerRunsWhatThePoolHasNotStarted() throws Exception {
		var pool = open(1);

		/*
		 * The one pool thread is held busy, so every piece handed over is
		 * still waiting when the caller gets to it.
		 */
		var release = new CountDownLatch(1);
		var busy = new CountDownLatch(1);
		pool.executor().execute(() -> {
			busy.countDown();
			try {
				release.await();
			} catch(InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		busy.await(10, TimeUnit.SECONDS);

		try {
			var caller = Thread.currentThread();
			var ranOn = new ArrayList<Thread>();
			var results = pool.invokeAll(List.of(
				piece(ranOn, 1),
				piece(ranOn, 2)
			), 0);

			assertThat(results, contains(1, 2));
			assertThat(ranOn, contains(caller, caller));
		} finally {
			release.countDown();
		}
	}

	@Test
	public void testAPoolThreadRunsUnderTheBudgetOfTheCaller() throws Exception {
		var pool = open(2);

		var onPool = new CountDownLatch(1);
		var seen = new boolean[1];
		try(var scope = SearchDeadline.start(Duration.ofNanos(1))) {
			pool.executor().execute(() -> {
				seen[0] = SearchDeadline.INSTANCE.shouldExit();
				onPool.countDown();
			});

			assertThat(onPool.await(10, TimeUnit.SECONDS), is(true));
			assertThat(seen[0], is(true));

			// The pool thread ran past the budget, and the caller sees that it did
			assertThat(scope.exceeded(), is(true));
		}
	}

	@Test
	public void testAPoolThreadRunsUnboundedForACallerWithoutABudget() throws Exception {
		var pool = open(2);

		var onPool = new CountDownLatch(1);
		var seen = new boolean[1];
		pool.executor().execute(() -> {
			seen[0] = SearchDeadline.INSTANCE.shouldExit();
			onPool.countDown();
		});

		assertThat(onPool.await(10, TimeUnit.SECONDS), is(true));
		assertThat(seen[0], is(false));
	}

	@Test
	public void testABudgetLeavesThePoolThreadWhenThePieceIsDone() throws Exception {
		var pool = open(1);

		try(var scope = SearchDeadline.start(Duration.ofNanos(1))) {
			pool.invokeAll(List.of(
				(Callable<Void>) () -> null,
				(Callable<Void>) () -> null
			), 0);
		}

		var onPool = new CountDownLatch(1);
		var seen = new boolean[1];
		pool.executor().execute(() -> {
			seen[0] = SearchDeadline.INSTANCE.shouldExit();
			onPool.countDown();
		});

		assertThat(onPool.await(10, TimeUnit.SECONDS), is(true));
		assertThat(seen[0], is(false));
	}

	@Test
	public void testEveryPieceRunsWhenOneFails() {
		var pool = open(2);

		var ran = new AtomicInteger();
		var pieces = new ArrayList<Callable<Integer>>();
		for(var i = 0; i < 8; i++) {
			var value = i;
			pieces.add(() -> {
				ran.incrementAndGet();
				if(value == 3) {
					throw new IOException("piece " + value);
				}

				return value;
			});
		}

		var thrown = assertThrows(IOException.class, () -> pool.invokeAll(pieces, 0));
		assertThat(thrown.getMessage(), is("piece 3"));
		assertThat(ran.get(), is(8));
	}

	@Test
	public void testARuntimeFailureIsRethrownAsItself() {
		var pool = open(2);

		var thrown = assertThrows(IllegalStateException.class, () -> pool.invokeAll(List.of(
			(Callable<Void>) () -> null,
			(Callable<Void>) () -> {
				throw new IllegalStateException("piece");
			}
		), 0));
		assertThat(thrown.getMessage(), is("piece"));
	}

	@Test
	public void testAClosedPoolRunsEveryPieceOnTheCaller() throws IOException {
		var pool = open(2);
		pool.close();

		var caller = Thread.currentThread();
		var ranOn = new ArrayList<Thread>();
		var results = pool.invokeAll(List.of(
			piece(ranOn, 1),
			piece(ranOn, 2)
		), 0);

		assertThat(results, contains(1, 2));
		assertThat(ranOn, contains(caller, caller));
	}

	@Test
	public void testPiecesSpreadOverThePool() throws Exception {
		var pool = open(4);

		var started = new CountDownLatch(4);
		var pieces = new ArrayList<Callable<Thread>>();
		for(var i = 0; i < 4; i++) {
			pieces.add(() -> {
				/*
				 * Every piece waits for the others to have started, which only
				 * happens when they run on threads of their own.
				 */
				started.countDown();
				started.await(10, TimeUnit.SECONDS);
				return Thread.currentThread();
			});
		}

		var ranOn = pool.invokeAll(pieces, 0);
		assertThat(ranOn.stream().distinct().count(), is(greaterThan(1L)));
		assertThat(pool.pieces(), is(5));
		assertThat(pool.threads(), is(4));
		assertThat(ranOn.get(0), is(not((Object) null)));
	}

	private static Callable<Integer> piece(List<Thread> ranOn, int value) {
		return () -> {
			synchronized(ranOn) {
				ranOn.add(Thread.currentThread());
			}

			return value;
		};
	}
}
