package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import se.l4.exofind.engine.index.registry.RegistryAuditReport;

/**
 * The registry compared with what the storage holds. Reported by the engine
 * and never accepted as input.
 *
 * @param registry
 *   whether the registry object itself could be read
 * @param indexes
 *   every index the registry or the storage names, ordered by name
 * @param unusable
 *   prefixes in the storage whose names no index or generation may carry, as
 *   {@code index} or {@code index/generation}. A repair never registers these
 */
public record RegistryAuditResponse(
	RegistryAuditReport.Registry registry,
	List<AuditedIndex> indexes,
	List<String> unusable
) {
	/**
	 * @param name
	 *   the name of the index
	 * @param registered
	 *   whether the registry has an entry for the index
	 * @param live
	 *   the generation the index answers for, absent when it answers for none
	 *   or is not registered
	 * @param proposedLive
	 *   the generation a repair asked to promote would make live, absent when
	 *   it would promote none
	 * @param generations
	 *   every generation either side names, ordered by name
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AuditedIndex(
		String name,
		boolean registered,
		String live,
		String proposedLive,
		List<AuditedGeneration> generations
	) {
	}

	/**
	 * @param name
	 *   the name of the generation
	 * @param registered
	 *   whether the registry names the generation
	 * @param stored
	 *   what the storage holds under it
	 */
	public record AuditedGeneration(
		String name,
		boolean registered,
		RegistryAuditReport.Stored stored
	) {
	}
}
