package se.l4.exofind.engine.health;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.Indexes;

public class PreloadReadinessCheckTest {
	/**
	 * A node still opening what it held answers the first search for each of
	 * those indexes by opening it first, which is the wait a rolling upgrade
	 * would move onto the traffic it sends here.
	 */
	@Test
	void testNotReadyWhileTheHeldIndexesAreStillBeingOpened() {
		var indexes = mock(Indexes.class);
		when(indexes.hasSettledPreload()).thenReturn(false);

		var response = new PreloadReadinessCheck(indexes).call();

		assertThat(response.getName(), is("index-preload"));
		assertThat(response.getStatus(), is(HealthCheckResponse.Status.DOWN));
	}

	@Test
	void testReadyOnceTheNodeHasStoppedWaiting() {
		var indexes = mock(Indexes.class);
		when(indexes.hasSettledPreload()).thenReturn(true);

		var response = new PreloadReadinessCheck(indexes).call();

		assertThat(response.getStatus(), is(HealthCheckResponse.Status.UP));
	}
}
