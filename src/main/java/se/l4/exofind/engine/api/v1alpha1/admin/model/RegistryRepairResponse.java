package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

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
public record RegistryRepairResponse(
	List<String> createdIndexes,
	List<String> addedGenerations,
	List<String> promoted
) {
}
