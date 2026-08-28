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
 * @param startedAt
 *   when the job was accepted
 * @param updatedAt
 *   when the record was last written
 */
public record ReindexJob(
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
	Instant startedAt,
	Instant updatedAt
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

		if(cursor != null) {
			builder.setCursor(cursor);
		}

		if(error != null) {
			builder.setError(error);
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
				Instant.ofEpochMilli(store.getStartedAt()),
				Instant.ofEpochMilli(store.getUpdatedAt())
			));
	}
}
