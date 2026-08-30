package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import se.l4.exofind.engine.reindex.ReindexJob;

/**
 * One reindex job as its durable record stands.
 *
 * @param index
 *   the name of the index
 * @param target
 *   the generation being populated, formatted as {@code index@generation}
 * @param source
 *   the generation providing the source documents
 * @param phase
 *   the current phase of the job: {@code pending}, {@code copying},
 *   {@code replaying}, {@code ready}, {@code promoting}, {@code done},
 *   {@code failed}, or {@code cancelled}
 * @param promote
 *   the configured promote mode: {@code auto} when the job promotes the target
 *   itself, or {@code manual} when the caller promotes
 * @param documentsCopied
 *   the number of confirmed documents copied to the target
 * @param sourceDocuments
 *   the document count of the source generation when the copy started
 * @param backlog
 *   the number of changed documents waiting to be replayed when the record was
 *   last written
 * @param error
 *   the error message if the job failed, or {@code null} in every other phase
 * @param startedAt
 *   the timestamp when the job started, in ISO 8601 format
 * @param updatedAt
 *   the timestamp when the job record was last updated, in ISO 8601 format
 */
@Schema(description = """
	A reindex job record. See [Job record and \
	phases](https://exofind.dev/reference/admin-api/#job-record-and-phases).""")
public record ReindexInfo(
	@Schema(description = "The name of the index.", examples = "products")
	String index,

	@Schema(
		description = "The generation being populated, formatted as `index@generation`.",
		examples = "products@2"
	)
	String target,

	@Schema(
		description = "The generation providing the source documents.",
		examples = "products@1"
	)
	String source,

	@Schema(
		description = """
			The current phase of the job. `pending`: accepted and waiting for \
			a concurrency slot on the node. `copying`: streaming documents \
			from the source to the target in primary key order. `replaying`: \
			copying documents that changed in the source while the copy ran. \
			`ready`: used only with `promote: manual`, caught up and waiting \
			for manual promotion, while continuing to catch up periodically. \
			`promoting`: holding writes for the final drain and promotion. \
			`done`: completed and promoted successfully. `failed`: stopped \
			before promotion due to an error, indicated by `error`. \
			`cancelled`: stopped before completion in response to a \
			cancellation request.""",
		enumeration = {
			"pending", "copying", "replaying", "ready", "promoting", "done", "failed",
			"cancelled"
		},
		examples = "copying"
	)
	String phase,

	@Schema(
		description = """
			The configured promote mode. `auto` automatically promotes the \
			target generation once it catches up with changes. `manual` pauses \
			the job in the `ready` phase and keeps the target caught up until \
			you manually promote it.""",
		enumeration = {"auto", "manual"},
		examples = "auto"
	)
	String promote,

	@Schema(
		description = "The number of confirmed documents copied to the target.",
		examples = "125000"
	)
	long documentsCopied,

	@Schema(
		description = "The document count of the source generation when the copy started.",
		examples = "2400000"
	)
	long sourceDocuments,

	@Schema(
		description = """
			The number of changed documents waiting to be replayed when the \
			record was last written.""",
		examples = "4100"
	)
	long backlog,

	@Schema(description = "The error message if the job failed, or `null`.")
	String error,

	@Schema(
		description = "The timestamp when the job started.",
		examples = "2026-08-28T10:15:30Z"
	)
	String startedAt,

	@Schema(
		description = "The timestamp when the job record was last updated.",
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
