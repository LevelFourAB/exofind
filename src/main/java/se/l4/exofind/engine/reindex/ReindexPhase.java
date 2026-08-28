package se.l4.exofind.engine.reindex;

import java.util.Optional;

/**
 * Where a reindex job stands. A job moves forward through the phases and ends
 * in exactly one of {@link #DONE}, {@link #FAILED} or {@link #CANCELLED};
 * {@link #READY} is only visited when the creator promotes the target
 * themselves.
 */
public enum ReindexPhase {
	/**
	 * Accepted but not yet running, waiting for a slot in the node's budget.
	 */
	PENDING(ReindexPhaseStore.REINDEX_PHASE_PENDING),

	/**
	 * Streaming documents out of the source generation and into the target.
	 */
	COPYING(ReindexPhaseStore.REINDEX_PHASE_COPYING),

	/**
	 * Replaying the changes that landed in the source while the copy ran.
	 */
	REPLAYING(ReindexPhaseStore.REINDEX_PHASE_REPLAYING),

	/**
	 * Caught up and waiting for the creator to promote, catching up
	 * periodically meanwhile.
	 */
	READY(ReindexPhaseStore.REINDEX_PHASE_READY),

	/**
	 * Holding writes for the final drain and the conditional promote.
	 */
	PROMOTING(ReindexPhaseStore.REINDEX_PHASE_PROMOTING),

	/**
	 * The target has been promoted and tracking has ended.
	 */
	DONE(ReindexPhaseStore.REINDEX_PHASE_DONE),

	/**
	 * Stopped before any promote, with the reason in the record. The partial
	 * target is left for a normal generation delete.
	 */
	FAILED(ReindexPhaseStore.REINDEX_PHASE_FAILED),

	/**
	 * Stopped by a cancel, leaving the partial target the way a failure does.
	 */
	CANCELLED(ReindexPhaseStore.REINDEX_PHASE_CANCELLED);

	private final ReindexPhaseStore stored;

	ReindexPhase(ReindexPhaseStore stored) {
		this.stored = stored;
	}

	/**
	 * Whether the job has ended and its record only describes what happened.
	 */
	public boolean isFinished() {
		return this == DONE || this == FAILED || this == CANCELLED;
	}

	/**
	 * The name this phase is written as, both in responses and where the API
	 * takes one in.
	 */
	public String id() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	ReindexPhaseStore toStore() {
		return stored;
	}

	/**
	 * Read a stored phase back.
	 *
	 * @return
	 *   empty when this build has no phase by that value, which is how a
	 *   record written by a newer version is read
	 */
	static Optional<ReindexPhase> fromStore(ReindexPhaseStore stored) {
		for(var phase : values()) {
			if(phase.stored == stored) {
				return Optional.of(phase);
			}
		}

		return Optional.empty();
	}
}
