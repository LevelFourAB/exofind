package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * How a registry repair treats the indexes it creates, and what deleted
 * storage it brings back.
 *
 * @param promoteNewest
 *   whether each index the repair creates answers for its highest-numbered
 *   generation; absent means {@code false}, so a created index answers for
 *   nothing until a generation is promoted
 * @param restore
 *   indexes and generations a delete marked that the repair takes the mark
 *   off and registers; absent means none
 */
@Schema(
	description = """
		How a repair should treat the indexes it creates, and which deleted \
		indexes or generations it should bring back. The entire body is \
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
	Boolean promoteNewest,

	@Schema(
		description = """
			Names of deleted indexes (`books`) or generations (`books@2`) \
			whose storage the sweep has not removed yet, to bring back. The \
			repair takes the removal mark off each one and registers what it \
			holds like any other unregistered storage. A name without a mark \
			changes nothing. Deleted storage is never registered without \
			being named here.""",
		examples = "[\"books\"]"
	)
	List<String> restore
) {
	/**
	 * The example request, as the JSON a client sends. The OpenAPI schema of
	 * this record shows this text.
	 */
	public static final String EXAMPLE = """
		{ "promoteNewest": true, "restore": ["books"] }""";
}
