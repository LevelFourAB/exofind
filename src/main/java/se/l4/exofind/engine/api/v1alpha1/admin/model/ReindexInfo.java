package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(description = """
	One reindex job as its durable record stands. See [Job record and \
	phases](https://exofind.dev/reference/admin-api/#job-record-and-phases).""")
public record ReindexInfo(
	@Schema(description = "Name of the index the job belongs to.", examples = "products")
	String index,

	@Schema(
		description = "The generation being filled, as `index@generation`.",
		examples = "products@2"
	)
	String target,

	@Schema(
		description = "The generation the documents are read from.",
		examples = "products@1"
	)
	String source,

	@Schema(
		description = """
			Where the job stands. `pending`: accepted and waiting for a \
			concurrency slot. `copying`: streaming documents from source to \
			target in primary key order. `replaying`: copying documents that \
			changed in the source while the copy ran. `ready`: used only with \
			`promote: manual`, caught up and waiting for manual promotion. \
			`promoting`: holding writes for the final drain and promotion. \
			`done`: completed and promoted. `failed`: stopped before promotion \
			due to an error, named in `error`. `cancelled`: stopped before \
			completion in response to a cancellation.""",
		enumeration = {
			"pending", "copying", "replaying", "ready", "promoting", "done", "failed",
			"cancelled"
		},
		examples = "copying"
	)
	String phase,

	@Schema(
		description = """
			`auto` when the job promotes the target itself once it has caught \
			up, `manual` when it stops in the `ready` phase and the caller \
			promotes.""",
		enumeration = {"auto", "manual"},
		examples = "auto"
	)
	String promote,

	@Schema(
		description = "How many documents the copy has confirmed so far.",
		examples = "125000"
	)
	long documentsCopied,

	@Schema(
		description = "How many documents the source held when the copy started.",
		examples = "2400000"
	)
	long sourceDocuments,

	@Schema(
		description = """
			How many changed documents were waiting to be replayed when the \
			record was last written.""",
		examples = "4100"
	)
	long backlog,

	@Schema(description = "Why the job failed, or `null` in every other phase.")
	String error,

	@Schema(
		description = "When the job was accepted, as an ISO 8601 timestamp.",
		examples = "2026-08-28T10:15:30Z"
	)
	String startedAt,

	@Schema(
		description = "When the record was last written, as an ISO 8601 timestamp.",
		examples = "2026-08-28T10:16:02Z"
	)
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
