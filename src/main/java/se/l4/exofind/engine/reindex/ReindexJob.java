package se.l4.exofind.engine.reindex;

import java.time.Instant;
import java.util.Optional;

import se.l4.exofind.engine.index.IndexName;

/**
 * One reindex job as its record describes it: the generation being filled,
 * the one it is filled from, and how far the work has come. At most one
 * exists per index, and a finished one stays readable until the next job for
 * the index replaces it.
 *
 * @param id
 *   id of the job, minted when it was accepted, or {@code null} on a record
 *   an earlier version wrote
 * @param index
 *   name of the index, without a generation
 * @param target
 *   generation being filled
 * @param source
 *   generation the documents are read from, pinned when the job started
 * @param phase
 *   where the job stands
 * @param cursor
 *   the last primary key whose copy is known to have reached the remote,
 *   written the way a key is written in a URL, or {@code null} before the
 *   first checkpoint
 * @param documentsCopied
 *   how many documents the copy has confirmed so far
 * @param sourceDocCount
 *   how many documents the source held when the copy started
 * @param backlog
 *   how many documents the change log named when the record was last written
 * @param error
 *   why the job failed, or {@code null} in every other phase
 * @param manualPromote
 *   whether the creator promotes the target themselves
 * @param startedBy
 *   id of the principal whose request started the job, or {@code null} on a
 *   record an earlier version wrote
 * @param node
 *   name of the node that last wrote the record - the one running the job,
 *   or the one that ended it - or {@code null} on a record an earlier version
 *   wrote
 * @param startedAt
 *   when the job was accepted
 * @param updatedAt
 *   when the record was last written
 * @param finishedAt
 *   when the job ended, or {@code null} while it runs
 */
public record ReindexJob(
	String id,
	String index,
	String target,
	String source,
	ReindexPhase phase,
	String cursor,
	long documentsCopied,
	long sourceDocCount,
	long backlog,
	String error,
	boolean manualPromote,
	String startedBy,
	String node,
	Instant startedAt,
	Instant updatedAt,
	Instant finishedAt
) {
	/**
	 * The full name of the generation being filled, as a caller writes it.
	 */
	public String targetName() {
		return IndexName.of(index, target).toString();
	}

	/**
	 * The full name of the generation the documents are read from.
	 */
	public String sourceName() {
		return IndexName.of(index, source).toString();
	}

	/**
	 * The job moved to another phase. The error is dropped, as it only
	 * describes the failed phase.
	 */
	ReindexJob withPhase(ReindexPhase phase) {
		return new ReindexJob(
			id, index, target, source, phase, cursor, documentsCopied,
			sourceDocCount, backlog, null, manualPromote, startedBy, node,
			startedAt, updatedAt, finishedAt
		);
	}

	/**
	 * The job with another backlog figure.
	 */
	ReindexJob withBacklog(long backlog) {
		return new ReindexJob(
			id, index, target, source, phase, cursor, documentsCopied,
			sourceDocCount, backlog, error, manualPromote, startedBy, node,
			startedAt, updatedAt, finishedAt
		);
	}

	/**
	 * The job with its copy moved past one batch.
	 */
	ReindexJob withCopied(String cursor, long copied, long backlog) {
		return new ReindexJob(
			id, index, target, source, phase, cursor, documentsCopied + copied,
			sourceDocCount, backlog, error, manualPromote, startedBy, node,
			startedAt, updatedAt, finishedAt
		);
	}

	/**
	 * The job failed for the given reason.
	 */
	ReindexJob withError(String error) {
		return new ReindexJob(
			id, index, target, source, ReindexPhase.FAILED, cursor,
			documentsCopied, sourceDocCount, backlog, error, manualPromote,
			startedBy, node, startedAt, updatedAt, finishedAt
		);
	}

	/**
	 * The job as a node writes it: the node named, the update time moved to
	 * now, and the end time set the first time the phase is a finished one.
	 * Every write of the record passes through here, so the times and the
	 * node are always those of the write.
	 *
	 * @param node
	 *   name of the node writing the record
	 * @param now
	 *   when the record is written
	 */
	ReindexJob written(String node, Instant now) {
		var finished = finishedAt != null
			? finishedAt
			: phase.isFinished() ? now : null;

		return new ReindexJob(
			id, index, target, source, phase, cursor, documentsCopied,
			sourceDocCount, backlog, error, manualPromote, startedBy, node,
			startedAt, now, finished
		);
	}

	ReindexJobStore toStore() {
		var builder = ReindexJobStore.newBuilder()
			.setIndex(index)
			.setTarget(target)
			.setSource(source)
			.setPhase(phase.toStore())
			.setDocumentsCopied(documentsCopied)
			.setSourceDocCount(sourceDocCount)
			.setBacklog(backlog)
			.setManualPromote(manualPromote)
			.setStartedAt(startedAt.toEpochMilli())
			.setUpdatedAt(updatedAt.toEpochMilli());

		if(id != null) {
			builder.setId(id);
		}

		if(cursor != null) {
			builder.setCursor(cursor);
		}

		if(error != null) {
			builder.setError(error);
		}

		if(startedBy != null) {
			builder.setStartedBy(startedBy);
		}

		if(node != null) {
			builder.setNode(node);
		}

		if(finishedAt != null) {
			builder.setFinishedAt(finishedAt.toEpochMilli());
		}

		return builder.build();
	}

	/**
	 * Read a stored record back.
	 *
	 * @return
	 *   empty when the record was written by a newer version - its phase is
	 *   unknown here, and a job that cannot be placed is left alone
	 */
	static Optional<ReindexJob> fromStore(ReindexJobStore store) {
		return ReindexPhase.fromStore(store.getPhase())
			.map(phase -> new ReindexJob(
				store.hasId() ? store.getId() : null,
				store.getIndex(),
				store.getTarget(),
				store.getSource(),
				phase,
				store.hasCursor() ? store.getCursor() : null,
				store.getDocumentsCopied(),
				store.getSourceDocCount(),
				store.getBacklog(),
				store.hasError() ? store.getError() : null,
				store.getManualPromote(),
				store.hasStartedBy() ? store.getStartedBy() : null,
				store.hasNode() ? store.getNode() : null,
				Instant.ofEpochMilli(store.getStartedAt()),
				Instant.ofEpochMilli(store.getUpdatedAt()),
				store.hasFinishedAt() ? Instant.ofEpochMilli(store.getFinishedAt()) : null
			));
	}
}
