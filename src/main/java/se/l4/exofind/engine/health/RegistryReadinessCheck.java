package se.l4.exofind.engine.health;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import se.l4.exofind.engine.Indexes;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Reports this node as ready once it has read the registry, at
 * {@code /q/health/ready} under the name {@code index-registry}.
 *
 * <p>The registry is the one object saying which indexes exist and which
 * generation each name answers for, and reading it is the first thing a node
 * does. One that has not managed it is listening without having reached the
 * storage it serves from - a node to keep traffic away from rather than a node
 * that is up.
 *
 * <p>Failing to read the registry later does not take readiness away again.
 * The copy the node holds is what it goes on serving, and the deployment is
 * better off with a node serving a registry from a minute ago than with no
 * node at all.
 *
 * <p>The endpoint is served without a key, like every other {@code /q/health}
 * endpoint, and says nothing about which indexes the deployment holds.
 */
@Readiness
@ApplicationScoped
public class RegistryReadinessCheck implements HealthCheck {
	private static final String NAME = "index-registry";

	private final Indexes indexes;

	RegistryReadinessCheck(Indexes indexes) {
		this.indexes = indexes;
	}

	@Override
	public HealthCheckResponse call() {
		return HealthCheckResponse.builder()
			.name(NAME)
			.status(indexes.hasReadRegistry())
			.build();
	}
}
