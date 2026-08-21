package se.l4.exofind.engine.api.v1alpha1.admin.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The node currently writing an index, read from the state the deployment
 * shares. Reported by the engine and never accepted as input.
 *
 * @param node
 *   the name the node competes under
 * @param address
 *   where writes for the index are sent, or {@code null} when the node
 *   offered no address
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndexerInfo(
	String node,
	String address
) {
}
