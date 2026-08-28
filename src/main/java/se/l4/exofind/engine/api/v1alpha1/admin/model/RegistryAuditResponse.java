package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(description = """
	The registry compared with what remote storage holds. Reported by the \
	engine and never accepted as input. See \
	[Audit](https://levelfourab.github.io/exofind/reference/admin-api/#audit).""")
public record RegistryAuditResponse(
	@Schema(description = """
		The state of the registry object. `PRESENT`: it was read. `ABSENT`: \
		there is no registry object. `CORRUPT`: its contents could not be \
		parsed.""")
	RegistryAuditReport.Registry registry,

	@Schema(description = """
		Every index the registry names or storage holds, ordered by name.""")
	List<AuditedIndex> indexes,

	@Schema(description = """
		Storage prefixes whose names no index or generation may carry, written \
		as `index` or `index/generation`. A repair never registers these.""")
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
	@Schema(description = "One index as the registry and storage each describe it.")
	public record AuditedIndex(
		@Schema(description = "Name of the index.", examples = "products")
		String name,

		@Schema(description = "Whether the registry has an entry for the index.")
		boolean registered,

		@Schema(
			description = """
				The generation the index answers for. Omitted when it is \
				unregistered or answers for none.""",
			examples = "2"
		)
		String live,

		@Schema(
			description = """
				The generation a repair with `promoteNewest` would make live. \
				Omitted when none would be promoted.""",
			examples = "1"
		)
		String proposedLive,

		@Schema(description = "Every generation either side names, ordered by name.")
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
	@Schema(description = "One generation as the registry and storage each describe it.")
	public record AuditedGeneration(
		@Schema(description = "Name of the generation.", examples = "1")
		String name,

		@Schema(description = "Whether the registry names the generation.")
		boolean registered,

		@Schema(description = """
			What storage holds under it. `SYNCED`: a manifest is there, so \
			nodes can pull and serve this generation. `INCOMPLETE`: a prefix \
			without a manifest, such as an unfinished push or leftovers from a \
			deleted generation. `MISSING`: the generation is registered but \
			nothing exists in storage.""")
		RegistryAuditReport.Stored stored
	) {
	}
}
