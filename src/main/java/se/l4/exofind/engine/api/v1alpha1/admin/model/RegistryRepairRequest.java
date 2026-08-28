package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * How a registry repair should treat the indexes it creates.
 *
 * @param promoteNewest
 *   whether an index the repair creates should answer for its
 *   highest-numbered generation. Absent means {@code false}: a created index
 *   answers for nothing until a generation is promoted
 */
@Schema(description = """
	How a repair should treat the indexes it creates. The whole body is \
	optional.""")
public record RegistryRepairRequest(
	@Schema(
		description = """
			When `true`, each index the repair creates answers for its \
			highest-numbered generation. Hand-named generations are not \
			selected, and indexes that are already registered keep what they \
			answer for. When `false`, a created index answers for nothing \
			until a generation is promoted.""",
		defaultValue = "false"
	)
	Boolean promoteNewest
) {
}
