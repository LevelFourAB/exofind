package se.l4.exofind.engine.health;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.Indexes;

public class RegistryReadinessCheckTest {
	/**
	 * A node that has not read the registry answers every name as unknown, so
	 * sending it searches is sending them nowhere.
	 */
	@Test
	void testNotReadyBeforeTheRegistryHasBeenRead() {
		var indexes = mock(Indexes.class);
		when(indexes.hasReadRegistry()).thenReturn(false);

		var response = new RegistryReadinessCheck(indexes).call();

		assertThat(response.getName(), is("index-registry"));
		assertThat(response.getStatus(), is(HealthCheckResponse.Status.DOWN));
	}

	@Test
	void testReadyOnceTheRegistryHasBeenRead() {
		var indexes = mock(Indexes.class);
		when(indexes.hasReadRegistry()).thenReturn(true);

		var response = new RegistryReadinessCheck(indexes).call();

		assertThat(response.getStatus(), is(HealthCheckResponse.Status.UP));
	}
}
