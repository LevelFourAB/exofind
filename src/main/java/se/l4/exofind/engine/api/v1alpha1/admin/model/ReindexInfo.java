package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import se.l4.exofind.engine.reindex.ReindexJob;

/**
 * One reindex job as its durable record stands.
 *
 * @param id
 *   the id of the job, minted when it was accepted, or {@code null} on a
 *   record an earlier version wrote
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
 * @param startedBy
 *   the id of the principal whose request started the job, or {@code null}
 *   on a record an earlier version wrote
 * @param node
 *   the name of the node running the job, or the one that ended it, or
 *   {@code null} on a record an earlier version wrote
 * @param startedAt
 *   the timestamp when the job started, in ISO 8601 format
 * @param updatedAt
 *   the timestamp when the job record was last updated, in ISO 8601 format
 * @param finishedAt
 *   the timestamp when the job ended, in ISO 8601 format, or {@code null}
 *   while it runs
 */
@Schema(
	description = """
		A reindex job record. See [Job record and \
		phases](https://exofind.dev/reference/admin-api/#job-record-and-phases).""",
	examples = ReindexInfo.EXAMPLE
)
public record ReindexInfo(
	@Schema(
		description = """
			The id of the job, minted when it was accepted. Tells one job of \
			an index from the one that replaced it. `null` on a record an \
			earlier version wrote.""",
		examples = "6f1c2a9d8b3e4c05"
	)
	String id,

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
		description = """
			The id of the principal whose request started the job: the id of \
			a key, or the name of a configured principal such as `root`. \
			`null` on a record an earlier version wrote.""",
		examples = "3f9a1c7e2b8d4650"
	)
	String startedBy,

	@Schema(
		description = """
			The name of the node running the job, as the indexer listing \
			names it, or the node that ended it. A job resumed on another \
			node after a failover names that node from its next checkpoint \
			on. `null` on a record an earlier version wrote.""",
		examples = "node-a-7f21"
	)
	String node,

	@Schema(
		description = "The timestamp when the job started.",
		examples = "2026-08-28T10:15:30Z"
	)
	String startedAt,

	@Schema(
		description = "The timestamp when the job record was last updated.",
		examples = "2026-08-28T10:16:02Z"
	)
	String updatedAt,

	@Schema(
		description = """
			The timestamp when the job reached `done`, `failed` or \
			`cancelled`, or `null` while it runs.""",
		examples = "2026-08-28T10:41:17Z"
	)
	String finishedAt
) {
	/**
	 * The example job, as the JSON the engine answers with. The OpenAPI schema
	 * of this record shows this text. A job part way through its copy, which is
	 * what reading the job back usually answers with.
	 */
	public static final String EXAMPLE = """
		{
		  "id": "6f1c2a9d8b3e4c05",
		  "index": "products",
		  "target": "products@2",
		  "source": "products@1",
		  "phase": "copying",
		  "promote": "auto",
		  "documentsCopied": 125000,
		  "sourceDocuments": 2400000,
		  "backlog": 4100,
		  "error": null,
		  "startedBy": "3f9a1c7e2b8d4650",
		  "node": "node-a-7f21",
		  "startedAt": "2026-08-28T10:15:30Z",
		  "updatedAt": "2026-08-28T10:16:02Z",
		  "finishedAt": null
		}""";

	/**
	 * The example job as starting one answers with. A job is written in the
	 * {@code pending} phase and takes a concurrency slot on the node before it
	 * starts copying, so nothing has been copied yet.
	 */
	public static final String EXAMPLE_STARTED = """
		{
		  "id": "6f1c2a9d8b3e4c05",
		  "index": "products",
		  "target": "products@2",
		  "source": "products@1",
		  "phase": "pending",
		  "promote": "auto",
		  "documentsCopied": 0,
		  "sourceDocuments": 2400000,
		  "backlog": 0,
		  "error": null,
		  "startedBy": "3f9a1c7e2b8d4650",
		  "node": "node-a-7f21",
		  "startedAt": "2026-08-28T10:15:30Z",
		  "updatedAt": "2026-08-28T10:15:30Z",
		  "finishedAt": null
		}""";

	public static ReindexInfo of(ReindexJob job) {
		return new ReindexInfo(
			job.id(),
			job.index(),
			job.targetName(),
			job.sourceName(),
			job.phase().id(),
			job.manualPromote() ? "manual" : "auto",
			job.documentsCopied(),
			job.sourceDocCount(),
			job.backlog(),
			job.error(),
			job.startedBy(),
			job.node(),
			job.startedAt().toString(),
			job.updatedAt().toString(),
			job.finishedAt() == null ? null : job.finishedAt().toString()
		);
	}
}
