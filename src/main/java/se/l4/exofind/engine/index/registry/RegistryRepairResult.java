package se.l4.exofind.engine.index.registry;

import org.eclipse.collections.api.list.ListIterable;

/**
 * What a {@link RegistryAudit#repair(boolean, ListIterable) repair} changed.
 * Every list empty means the registry already named everything the storage
 * holds, nothing was restored, and nothing was written.
 *
 * @param createdIndexes
 *   indexes the repair added an entry for, ordered by name
 * @param addedGenerations
 *   generations the repair registered, as {@code index@generation}, ordered
 *   by name
 * @param promoted
 *   generations the repair made their index answer for, as
 *   {@code index@generation}, ordered by name
 * @param restored
 *   indexes and generations whose removal mark the repair took away, as
 *   {@code index} or {@code index@generation}, in the order they were asked
 *   for. What they hold is registered through the other lists, the way any
 *   unregistered storage is
 */
public record RegistryRepairResult(
	ListIterable<String> createdIndexes,
	ListIterable<String> addedGenerations,
	ListIterable<String> promoted,
	ListIterable<String> restored
) {
	/**
	 * Whether the repair changed nothing.
	 */
	public boolean isEmpty() {
		return createdIndexes.isEmpty()
			&& addedGenerations.isEmpty()
			&& promoted.isEmpty()
			&& restored.isEmpty();
	}
}
