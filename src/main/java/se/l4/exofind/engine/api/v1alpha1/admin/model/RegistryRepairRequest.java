package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * How a registry repair treats the indexes it creates.
 *
 * @param promoteNewest
 *   whether each index the repair creates answers for its highest-numbered
 *   generation; absent means {@code false}, so a created index answers for
 *   nothing until a generation is promoted
 */
@Schema(
	description = """
		How a repair should treat the indexes it creates. The entire body is \
		optional.""",
	examples = RegistryRepairRequest.EXAMPLE
)
public record RegistryRepairRequest(
	@Schema(
		description = """
			When `true`, each index created by the repair answers for its \
			highest-numbered generation. Hand-named generations are not \
			selected. Indexes that are already registered keep what they \
			answer for. When `false`, a created index answers for nothing \
			until a generation is promoted.""",
		defaultValue = "false"
	)
	Boolean promoteNewest
) {
	/**
	 * The example request, as the JSON a client sends. The OpenAPI schema of
	 * this record shows this text.
	 */
	public static final String EXAMPLE = """
		{ "promoteNewest": true }""";
}
