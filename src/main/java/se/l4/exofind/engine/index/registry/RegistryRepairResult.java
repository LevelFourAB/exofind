package se.l4.exofind.engine.index.registry;

import org.eclipse.collections.api.list.ListIterable;

/**
 * What a {@link RegistryAudit#repair(boolean) repair} changed in the registry.
 * All three lists empty means the registry already named everything the
 * storage holds and nothing was written.
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
public record RegistryRepairResult(
	ListIterable<String> createdIndexes,
	ListIterable<String> addedGenerations,
	ListIterable<String> promoted
) {
	/**
	 * Whether the repair changed nothing.
	 */
	public boolean isEmpty() {
		return createdIndexes.isEmpty()
			&& addedGenerations.isEmpty()
			&& promoted.isEmpty();
	}
}
