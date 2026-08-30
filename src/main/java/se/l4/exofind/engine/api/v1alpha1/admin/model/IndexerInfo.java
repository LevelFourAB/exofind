package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The node currently writing an index, read from shared deployment state.
 * Reported by the engine and never accepted as input.
 *
 * @param node
 *   the name the node competes under
 * @param address
 *   the address where writes are forwarded, or {@code null} if the node
 *   provided no address
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	The node currently writing an index, read from shared deployment state. \
	Reported by the engine and never accepted as input.""")
public record IndexerInfo(
	@Schema(description = "The name the node competes under.", examples = "node-a-7f21")
	String node,

	@Schema(
		description = """
			The target address for write forwarding. Omitted when the node did \
			not set `EXOFIND_NODE_ADDRESS`.""",
		examples = "http://node-a:8080"
	)
	String address
) {
}
