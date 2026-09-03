package se.l4.exofind.engine.index.registry;

import org.eclipse.collections.api.list.ListIterable;

import se.l4.exofind.engine.index.IndexName;

/**
 * Comparing the registry with what the storage the indexes live in actually
 * holds, and rebuilding the registry from it.
 *
 * <p>The registry is the one object saying which indexes a deployment holds,
 * and nothing else reads the storage to find them - so a registry that is lost
 * or can no longer be parsed forgets every index even though all of their
 * files are still there. This is the way back: the audit says how the two
 * disagree, the repair writes a registry that names what the storage holds.
 *
 * <p>A repair only ever adds. Entries the registry already has are kept
 * exactly as they are stored - unknown fields, feature names and all - and a
 * finding that could mean an interrupted rollout is reported rather than
 * removed. Whether to make a rebuilt index answer for anything is the caller's
 * to say: nothing in the storage records which generation was live.
 *
 * <p>Storage a delete marked is the exception to registering what is found:
 * it is on its way out, and a repair leaves it be unless asked to restore it,
 * which takes the mark away and registers it like anything else. That is how
 * a delete is taken back before the sweep removes the objects.
 *
 * <p>Implementations are safe for concurrent use.
 */
public interface RegistryAudit {
	/**
	 * Compare the registry with the storage. Reads both without changing
	 * either, so this is also the check that catches an interrupted rollout,
	 * or shows what a delete left that the sweep has not removed yet.
	 *
	 * @return
	 * @throws RegistryException
	 *   if the storage could not be reached
	 */
	RegistryAuditReport audit();

	/**
	 * Register everything the storage holds that the registry does not name
	 * and no delete marked. A corrupt registry is replaced with one rebuilt
	 * from the storage; a readable one is added to, with every entry it
	 * already has kept as it was stored.
	 *
	 * <p>The write is conditional on what the audit read, and is rebuilt on
	 * top of a concurrent change rather than overwriting it. A generation
	 * being rolled out right now can still gain its registration from here
	 * moments before its creator writes it, which the creator sees as the
	 * generation already existing - run a repair when no rollout is in
	 * flight.
	 *
	 * @param promoteNewest
	 *   whether an index the repair creates should answer for its
	 *   highest-numbered generation. Indexes that are already registered keep
	 *   what they answer for either way. {@code false} leaves a created index
	 *   answering for nothing until a generation is promoted
	 * @param restore
	 *   indexes and generations to take the removal mark off before
	 *   registering, so that what a delete marked comes back. A name without
	 *   a mark changes nothing. The marks go first, so a restored index that
	 *   holds no synced generation is unmarked and still not registered
	 * @return
	 *   what was changed, empty when the registry already named everything
	 *   and nothing was restored
	 * @throws RegistryException
	 *   if the storage could not be reached, or the registry kept being
	 *   changed by someone else
	 */
	RegistryRepairResult repair(boolean promoteNewest, ListIterable<IndexName> restore);
}
