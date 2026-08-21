package se.l4.exofind.engine.health;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.Indexes;

public class RefreshLivenessCheckTest {
	@Test
	void testLiveWhilePassesKeepFinishing() {
		var response = check(Duration.ofSeconds(30), Duration.ofSeconds(45));

		assertThat(response.getName(), is("index-refresh"));
		assertThat(response.getStatus(), is(HealthCheckResponse.Status.UP));
	}

	/**
	 * A pass may be late without the loop having stopped - the sweep and a
	 * reopen of every open index run on the same thread - so the allowance is
	 * several intervals wide before a node is called dead.
	 */
	@Test
	void testLiveWhileOnePassIsMerelyLate() {
		var response = check(Duration.ofSeconds(30), Duration.ofSeconds(100));

		assertThat(response.getStatus(), is(HealthCheckResponse.Status.UP));
	}

	@Test
	void testDeadOnceNoPassHasFinishedForSeveralIntervals() {
		var response = check(Duration.ofSeconds(30), Duration.ofSeconds(150));

		assertThat(response.getStatus(), is(HealthCheckResponse.Status.DOWN));
	}

	/**
	 * A deployment that refreshes far more often than it probes is still judged
	 * on whether its loop runs, and not on a margin no probe could sit inside.
	 */
	@Test
	void testShortIntervalsAreGivenTheMinimumAllowance() {
		assertThat(
			check(Duration.ofSeconds(1), Duration.ofSeconds(30)).getStatus(),
			is(HealthCheckResponse.Status.UP)
		);

		assertThat(
			check(Duration.ofSeconds(1), Duration.ofSeconds(90)).getStatus(),
			is(HealthCheckResponse.Status.DOWN)
		);
	}

	@Test
	void testHowLongItHasBeenIsReported() {
		var response = check(Duration.ofSeconds(30), Duration.ofSeconds(45));

		assertThat(
			response.getData().orElseThrow().get("secondsSinceRefresh"),
			is(45L)
		);
	}

	private static HealthCheckResponse check(Duration interval, Duration since) {
		var indexes = mock(Indexes.class);
		when(indexes.getRefreshInterval()).thenReturn(interval);
		when(indexes.getTimeSinceRefresh()).thenReturn(since);

		return new RefreshLivenessCheck(indexes).call();
	}
}
