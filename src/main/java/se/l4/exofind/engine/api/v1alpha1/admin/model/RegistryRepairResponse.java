package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What a registry repair changed. All three lists empty means the registry
 * already named everything the storage holds and nothing was written.
 *
 * @param createdIndexes
 *   indexes the repair added an entry for, ordered by name
 * @param addedGenerations
 *   generations the repair registered, as {@code index@generation}, ordered
 *   by name
 * @param promoted
 *   generations the repair made their index answer for, as
 *   {@code index@generation}, ordered by name
 */
@Schema(description = """
	What a repair changed. All three lists empty means the registry already \
	named everything storage holds and nothing was written.""")
public record RegistryRepairResponse(
	@Schema(description = "Index entries the repair added to the registry, ordered by name.")
	List<String> createdIndexes,

	@Schema(description = """
		Generations the repair registered, written as `index@generation` and \
		ordered by name.""")
	List<String> addedGenerations,

	@Schema(description = """
		Generations the repair made their index answer for, written as \
		`index@generation` and ordered by name.""")
	List<String> promoted
) {
}
