package se.l4.exofind.engine.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Tests for the time budget a search collects under - that a budget already
 * spent stops the collection, and that a scope only bounds the work inside it.
 */
public class SearchDeadlineTest {
	@Test
	public void testNoScopeRunsWithoutABudget() {
		assertThat(SearchDeadline.INSTANCE.shouldExit(), is(false));
	}

	@Test
	public void testASpentBudgetStopsCollecting() {
		try(var scope = SearchDeadline.start(Duration.ofNanos(1))) {
			assertThat(SearchDeadline.INSTANCE.shouldExit(), is(true));
			assertThat(scope.exceeded(), is(true));
		}
	}

	@Test
	public void testABudgetWithTimeLeftCollectsOn() {
		try(var scope = SearchDeadline.start(Duration.ofHours(1))) {
			assertThat(SearchDeadline.INSTANCE.shouldExit(), is(false));
			assertThat(scope.exceeded(), is(false));
		}
	}

	@Test
	public void testZeroLeavesTheSearchUnbounded() {
		try(var scope = SearchDeadline.start(Duration.ZERO)) {
			assertThat(SearchDeadline.INSTANCE.shouldExit(), is(false));
			assertThat(scope.exceeded(), is(false));
		}
	}

	@Test
	public void testAClosedScopeBoundsNothing() {
		var scope = SearchDeadline.start(Duration.ofNanos(1));
		SearchDeadline.INSTANCE.shouldExit();
		scope.close();

		assertThat(SearchDeadline.INSTANCE.shouldExit(), is(false));

		// What happened while the scope was open stays readable after it closes
		assertThat(scope.exceeded(), is(true));
	}

	@Test
	public void testABudgetBoundsOnlyTheThreadThatOpenedIt() throws Exception {
		try(var scope = SearchDeadline.start(Duration.ofNanos(1))) {
			var elsewhere = new boolean[1];

			var thread = new Thread(() -> elsewhere[0] = SearchDeadline.INSTANCE.shouldExit());
			thread.start();
			thread.join();

			assertThat(elsewhere[0], is(false));
		}
	}

	@Test
	public void testAnAttachedBudgetIsSharedWithTheThreadThatOpenedIt() throws Exception {
		try(var scope = SearchDeadline.start(Duration.ofNanos(1))) {
			var budget = SearchDeadline.current();
			var elsewhere = new boolean[1];

			var thread = new Thread(() -> {
				try(var attached = SearchDeadline.attach(budget)) {
					elsewhere[0] = SearchDeadline.INSTANCE.shouldExit();
				}
			});
			thread.start();
			thread.join();

			assertThat(elsewhere[0], is(true));

			// The other thread ran past the budget, and this one sees that it did
			assertThat(scope.exceeded(), is(true));
			assertThat(SearchDeadline.exceeded(), is(true));
		}
	}

	@Test
	public void testAClosedAttachmentRestoresWhatTheThreadHad() throws Exception {
		try(var scope = SearchDeadline.start(Duration.ofHours(1))) {
			var spent = SearchDeadline.start(Duration.ofNanos(1));
			spent.close();

			try(var attached = SearchDeadline.attach(null)) {
				assertThat(SearchDeadline.INSTANCE.shouldExit(), is(false));
			}

			assertThat(SearchDeadline.exceeded(), is(false));
			assertThat(scope.exceeded(), is(false));
		}
	}
}
