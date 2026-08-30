package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What a registry repair changed. All three lists empty means the registry
 * already named everything storage holds and nothing was written.
 *
 * @param createdIndexes
 *   index entries added to the registry, ordered by name
 * @param addedGenerations
 *   generations registered by the repair, formatted as {@code index@generation}
 *   and ordered by name
 * @param promoted
 *   generations made live by the repair, formatted as {@code index@generation}
 *   and ordered by name
 */
@Schema(description = """
	Summary of the changes made by a repair. All three lists empty means the \
	registry already named everything storage holds and nothing was written.""")
public record RegistryRepairResponse(
	@Schema(description = "Index entries added to the registry, ordered by name.")
	List<String> createdIndexes,

	@Schema(description = """
		Generations added to the registry, formatted as `index@generation` and \
		ordered by name.""")
	List<String> addedGenerations,

	@Schema(description = """
		Generations made live by the repair, formatted as `index@generation` \
		and ordered by name.""")
	List<String> promoted
) {
}
