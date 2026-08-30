package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Configuration for a reindex job.
 *
 * @param from
 *   the generation to read from: the index itself, or a generation as
 *   {@code books@1}, or {@code null} for whichever is live
 * @param promote
 *   {@code auto} or {@code null} to promote automatically once caught up, or
 *   {@code manual} to pause in the ready phase for manual promotion
 */
@Schema(description = """
	Configuration for a reindex job. Both fields are optional; an empty body \
	reads from the live generation and promotes automatically.""")
public record ReindexRequest(
	@Schema(
		description = """
			The generation to read documents from: an index name, or a \
			generation such as `products@1`. Must belong to the same index as \
			the target. Defaults to the live generation.""",
		examples = "products@1"
	)
	String from,

	@Schema(
		description = """
			The promotion mode. `auto` (default) automatically promotes the \
			target generation once it catches up with changes. `manual` pauses \
			the job in the `ready` phase and keeps the target caught up until \
			you manually promote it.""",
		enumeration = {"auto", "manual"},
		defaultValue = "auto"
	)
	String promote
) {
}
