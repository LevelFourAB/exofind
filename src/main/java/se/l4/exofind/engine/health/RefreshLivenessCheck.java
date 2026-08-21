package se.l4.exofind.engine.health;

import java.time.Duration;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import se.l4.exofind.engine.Indexes;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Reports this node as live while its refresh loop is still running, at
 * {@code /q/health/live} under the name {@code index-refresh}.
 *
 * <p>The refresh loop is the only thing that brings a node in step with the
 * deployment: what indexes exist, which generation each name answers for, and
 * the contents of the generations this node holds open. A node whose loop has
 * stopped keeps answering searches from whatever it happened to hold when it
 * stopped, and goes on doing so until it is restarted - which is what makes
 * this liveness rather than readiness.
 *
 * <p>A pass that fails is a pass. An unreachable remote, a registry that will
 * not parse and a storage answering with errors are all logged and left to the
 * next pass, and none of them are mended by restarting the node.
 *
 * <p>The endpoint is served without a key, like every other {@code /q/health}
 * endpoint, and reports how long it has been rather than what the node holds.
 */
@Liveness
@ApplicationScoped
public class RefreshLivenessCheck implements HealthCheck {
	private static final String NAME = "index-refresh";

	/**
	 * How many refresh intervals may pass without one finishing before the loop
	 * counts as stopped. Wide enough that a pass delayed behind the disk sweep,
	 * or behind a reopen of every open index, is not read as a node to restart.
	 */
	private static final int MISSED_PASSES = 4;

	/**
	 * Shortest the allowance is ever made, so that a deployment refreshing every
	 * second is judged on whether its loop runs and not on a margin no probe
	 * interval could sit inside.
	 */
	private static final Duration MINIMUM_ALLOWANCE = Duration.ofMinutes(1);

	private final Indexes indexes;

	RefreshLivenessCheck(Indexes indexes) {
		this.indexes = indexes;
	}

	@Override
	public HealthCheckResponse call() {
		var since = indexes.getTimeSinceRefresh();
		var allowance = indexes.getRefreshInterval().multipliedBy(MISSED_PASSES);

		if(allowance.compareTo(MINIMUM_ALLOWANCE) < 0) {
			allowance = MINIMUM_ALLOWANCE;
		}

		return HealthCheckResponse.builder()
			.name(NAME)
			.status(since.compareTo(allowance) <= 0)
			.withData("secondsSinceRefresh", since.toSeconds())
			.build();
	}
}
