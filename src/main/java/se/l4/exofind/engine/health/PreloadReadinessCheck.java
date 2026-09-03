package se.l4.exofind.engine.health;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import se.l4.exofind.engine.Indexes;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Reports this node as ready once it has opened the indexes it held before it
 * started, at {@code /q/health/ready} under the name {@code index-preload}.
 *
 * <p>A node that kept its directory across a restart comes back holding copies
 * of the indexes it was serving, and opens the most used of them before
 * anything asks for one. A node reporting itself ready before that is done
 * answers the first search for each index by opening the index first, which is
 * a wait a rolling upgrade would put in front of every node it restarts.
 *
 * <p>The wait is capped by {@code EXOFIND_INDEXES_PRELOAD_READINESS_WAIT}. Once
 * it passes, the node reports itself ready and opens the rest in the
 * background. A node serving cold indexes helps the deployment more than a node
 * serving nothing.
 *
 * <p>The endpoint is served without a key, like every other {@code /q/health}
 * endpoint, and says nothing about which indexes the node holds.
 */
@Readiness
@ApplicationScoped
public class PreloadReadinessCheck implements HealthCheck {
	private static final String NAME = "index-preload";

	private final Indexes indexes;

	PreloadReadinessCheck(Indexes indexes) {
		this.indexes = indexes;
	}

	@Override
	public HealthCheckResponse call() {
		return HealthCheckResponse.builder()
			.name(NAME)
			.status(indexes.hasSettledPreload())
			.build();
	}
}
