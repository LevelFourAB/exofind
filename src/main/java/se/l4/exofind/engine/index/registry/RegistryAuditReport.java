package se.l4.exofind.engine.index.registry;

import org.eclipse.collections.api.list.ListIterable;

/**
 * The registry compared with what the storage actually holds, from one read
 * of each: every index either of them names, and where the two disagree.
 *
 * <p>A generation the storage holds that the registry does not name is what a
 * {@link RegistryAudit#repair(boolean) repair} registers. The other findings
 * are reported without a repair for them: a generation prefix without a
 * manifest is an interrupted first push or storage a delete left behind, and
 * which of those it is cannot be read off the storage; a registered generation
 * with nothing behind it has nothing left to serve from.
 *
 * @param registry
 *   whether the registry object itself could be read
 * @param indexes
 *   every index the registry or the storage names, ordered by name
 * @param unusable
 *   prefixes in the storage whose names no index or generation may carry, as
 *   {@code index} or {@code index/generation} relative to where the indexes
 *   live. A repair never registers these
 */
public record RegistryAuditReport(
	Registry registry,
	ListIterable<AuditedIndex> indexes,
	ListIterable<String> unusable
) {
	/**
	 * The state the registry object was found in.
	 */
	public enum Registry {
		/**
		 * The registry exists and could be read.
		 */
		PRESENT,

		/**
		 * There is no registry, so every index in the storage is unregistered.
		 */
		ABSENT,

		/**
		 * There is a registry but its contents can not be parsed. A repair
		 * replaces it with one rebuilt from the storage.
		 */
		CORRUPT
	}

	/**
	 * One index as the registry and the storage agree or disagree about it.
	 *
	 * @param name
	 * @param registered
	 *   whether the registry has an entry for the index
	 * @param live
	 *   the generation the index answers for, or {@code null} when it answers
	 *   for none or is not registered
	 * @param proposedLive
	 *   the generation a repair asked to promote would make live, or
	 *   {@code null} when it would promote none - an index that is already
	 *   registered keeps whatever it answers for
	 * @param generations
	 *   every generation either side names, ordered by name
	 */
	public record AuditedIndex(
		String name,
		boolean registered,
		String live,
		String proposedLive,
		ListIterable<AuditedGeneration> generations
	) {
	}

	/**
	 * One generation of an index.
	 *
	 * @param name
	 * @param registered
	 *   whether the registry names the generation
	 * @param stored
	 *   what the storage holds under it
	 */
	public record AuditedGeneration(
		String name,
		boolean registered,
		Stored stored
	) {
	}

	/**
	 * What the storage holds for a generation.
	 */
	public enum Stored {
		/**
		 * The generation has a manifest, so a node can pull and serve it.
		 */
		SYNCED,

		/**
		 * The generation has a prefix but no manifest - a first push that never
		 * finished, or what a sweep left of a removed generation. Not
		 * registrable, as there is nothing to serve from.
		 */
		INCOMPLETE,

		/**
		 * The storage holds nothing under the generation, so a node that does
		 * not already hold a copy has nowhere to pull it from.
		 */
		MISSING
	}
}
