package se.l4.exofind.engine.index.state;

import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides when an entry that another node wrote into the leadership table
 * has lapsed, without trusting the wall clock of that node.
 *
 * <p>The holder of an entry renews it every round by writing it with a new
 * expiry. An entry with the same expiry is therefore an entry that was not
 * renewed. This class keeps the time at which this node first read each
 * entry, on its own monotonic clock. An entry lapses when two conditions
 * are true:
 *
 * <ul>
 *   <li>This node has read the same entry for at least one lease, measured
 *   on its monotonic clock from the first read.</li>
 *   <li>The expiry in the entry has passed on the wall clock of this
 *   node.</li>
 * </ul>
 *
 * <p>The first condition makes the decision safe when the clocks of the
 * nodes do not agree. The holder times its own lease on its monotonic
 * clock, from a moment before it wrote the entry, and gives the claims up
 * before the lease ends. This node can read the entry only after the write,
 * so it cannot find the entry lapsed while the holder still writes. The
 * second condition can only make the wait longer. It keeps an entry live
 * until the expiry that its writer gave it, as nodes that do not keep
 * sightings also do.
 *
 * <p>A node that starts, or that reads an entry for the first time, waits
 * one lease before it finds the entry lapsed, whatever the expiry says.
 *
 * <p>Safe for use from many threads.
 */
final class LeaseSightings {
	private record ClaimKey(String index, String node, String session, long expiresAt) {
	}

	private record CandidateKey(String node, String session, long expiresAt) {
	}

	private final long leaseNanos;

	/**
	 * The monotonic time of the first read of each entry, by its key.
	 */
	private final ConcurrentHashMap<Object, Long> firstSeen = new ConcurrentHashMap<>();

	LeaseSightings(Duration leaseDuration) {
		this.leaseNanos = leaseDuration.toNanos();
	}

	/**
	 * Record the entries of a table that this node read, and forget the
	 * entries that the table no longer holds.
	 *
	 * @param readNanos
	 *   a reading of the monotonic clock taken after the storage answered
	 *   the read. A reading from before the request was sent can come before
	 *   the write of an entry in the answer
	 */
	void record(IndexerLeadership table, long readNanos) {
		var keys = new HashSet<Object>();

		for(var claim : table.getClaimsList()) {
			var key = keyOf(claim);
			keys.add(key);
			firstSeen.putIfAbsent(key, readNanos);
		}

		for(var candidate : table.getCandidatesList()) {
			var key = keyOf(candidate);
			keys.add(key);
			firstSeen.putIfAbsent(key, readNanos);
		}

		/*
		 * A key that a read of an older table puts back is forgotten on the
		 * next read. A key that is forgotten too early is read again later,
		 * which only makes its entry lapse later.
		 */
		firstSeen.keySet().retainAll(keys);
	}

	/**
	 * Whether a claim has lapsed, so that another candidate may take it.
	 */
	boolean isLapsed(IndexerLeadership.Claim claim, long nowMillis, long nowNanos) {
		return isLapsed(keyOf(claim), claim.getExpiresAt(), nowMillis, nowNanos);
	}

	/**
	 * Whether a candidate entry has lapsed, so that its node no longer
	 * counts as a candidate.
	 */
	boolean isLapsed(IndexerLeadership.Candidate candidate, long nowMillis, long nowNanos) {
		return isLapsed(keyOf(candidate), candidate.getExpiresAt(), nowMillis, nowNanos);
	}

	private boolean isLapsed(Object key, long expiresAt, long nowMillis, long nowNanos) {
		/*
		 * An entry this node did not read from the storage, such as a claim
		 * that this node handed to a taker, starts its wait now.
		 */
		var first = firstSeen.putIfAbsent(key, nowNanos);
		if(first == null || nowNanos - first < leaseNanos) {
			return false;
		}

		return expiresAt <= nowMillis;
	}

	private static ClaimKey keyOf(IndexerLeadership.Claim claim) {
		return new ClaimKey(
			claim.getIndex(),
			claim.getNode(),
			claim.hasSession() ? claim.getSession() : null,
			claim.getExpiresAt()
		);
	}

	private static CandidateKey keyOf(IndexerLeadership.Candidate candidate) {
		return new CandidateKey(
			candidate.getNode(),
			candidate.hasSession() ? candidate.getSession() : null,
			candidate.getExpiresAt()
		);
	}
}
