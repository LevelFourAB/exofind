package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What a caller asks a reindex to do.
 *
 * @param from
 *   the generation to read from - the index itself, or one generation of it
 *   as {@code books@1} - or {@code null} for whichever is live
 * @param promote
 *   {@code auto} or {@code null} to promote once caught up, {@code manual}
 *   to stop in the ready phase and leave the promote to the caller
 */
@Schema(description = """
	How to run a reindex job. Both fields are optional; an empty body reads \
	from the live generation and promotes automatically.""")
public record ReindexRequest(
	@Schema(
		description = """
			The generation to read documents from - an index name, or one \
			generation as `products@1`. Must belong to the same index as the \
			target. Defaults to the live generation.""",
		examples = "products@1"
	)
	String from,

	@Schema(
		description = """
			`auto` promotes the target generation once it catches up with \
			changes. `manual` pauses the job in the `ready` phase, keeping the \
			target caught up, until it is promoted by hand.""",
		enumeration = {"auto", "manual"},
		defaultValue = "auto"
	)
	String promote
) {
}
