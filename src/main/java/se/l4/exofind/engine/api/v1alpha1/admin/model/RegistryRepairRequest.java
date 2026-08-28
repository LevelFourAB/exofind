package se.l4.exofind.engine.api.v1alpha1.admin.model;

/**
 * How a registry repair should treat the indexes it creates.
 *
 * @param promoteNewest
 *   whether an index the repair creates should answer for its
 *   highest-numbered generation. Absent means {@code false}: a created index
 *   answers for nothing until a generation is promoted
 */
public record RegistryRepairRequest(
	Boolean promoteNewest
) {
}
