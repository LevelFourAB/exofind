package se.l4.exofind.engine.index;

import java.time.Duration;

/**
 * When an index commits without being asked to.
 *
 * <p>The two triggers bound different things and either may be turned off on
 * its own by giving it zero. {@code maxInterval} bounds how long a change can
 * sit uncommitted, measured from the oldest change that has not been committed;
 * it is what decides how stale a search on another node can be. {@code
 * maxChanges} bounds how much can be waiting at once, whatever the rate they
 * arrive at.
 *
 * <p>An index still commits when asked to, whatever this says.
 *
 * @param maxChanges
 *   how many changes may be waiting before a commit is started, or zero to
 *   leave the number of changes alone
 * @param maxInterval
 *   how long the oldest waiting change may go uncommitted, or
 *   {@link Duration#ZERO} to leave time alone
 */
public record CommitPolicy(int maxChanges, Duration maxInterval) {
	private static final CommitPolicy DISABLED = new CommitPolicy(0, Duration.ZERO);

	public CommitPolicy {
		if(maxChanges < 0) {
			throw new IllegalArgumentException("maxChanges can not be negative");
		}

		if(maxInterval == null || maxInterval.isNegative()) {
			throw new IllegalArgumentException("maxInterval can not be negative");
		}
	}

	/**
	 * A policy under which an index only commits when it is asked to, which is
	 * also what both triggers being off amounts to.
	 */
	public static CommitPolicy disabled() {
		return DISABLED;
	}

	/**
	 * Whether either trigger can start a commit.
	 */
	public boolean isEnabled() {
		return maxChanges > 0 || !maxInterval.isZero();
	}
}
