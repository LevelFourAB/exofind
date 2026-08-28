package se.l4.exofind.engine.api.v1alpha1.admin.model;

import se.l4.exofind.engine.reindex.ReindexJob;

/**
 * One reindex job as its record stands.
 *
 * @param index
 *   name of the index the job belongs to
 * @param target
 *   the generation being filled, as {@code index@generation}
 * @param source
 *   the generation the documents are read from
 * @param phase
 *   where the job stands: {@code pending}, {@code copying},
 *   {@code replaying}, {@code ready}, {@code promoting}, {@code done},
 *   {@code failed} or {@code cancelled}
 * @param promote
 *   {@code auto} when the job promotes on its own, {@code manual} when the
 *   caller does
 * @param documentsCopied
 *   how many documents the copy has confirmed so far
 * @param sourceDocuments
 *   how many documents the source held when the copy started
 * @param backlog
 *   how many changed documents were waiting to be carried over when the
 *   record was last written
 * @param error
 *   why the job failed, or {@code null} in every other phase
 * @param startedAt
 *   when the job was accepted, as ISO 8601
 * @param updatedAt
 *   when the record was last written, as ISO 8601
 */
public record ReindexInfo(
	String index,
	String target,
	String source,
	String phase,
	String promote,
	long documentsCopied,
	long sourceDocuments,
	long backlog,
	String error,
	String startedAt,
	String updatedAt
) {
	public static ReindexInfo of(ReindexJob job) {
		return new ReindexInfo(
			job.index(),
			job.targetName(),
			job.sourceName(),
			job.phase().id(),
			job.manualPromote() ? "manual" : "auto",
			job.documentsCopied(),
			job.sourceDocCount(),
			job.backlog(),
			job.error(),
			job.startedAt().toString(),
			job.updatedAt().toString()
		);
	}
}
