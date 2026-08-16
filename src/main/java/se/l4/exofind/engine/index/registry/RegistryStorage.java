package se.l4.exofind.engine.index.registry;

import java.io.IOException;

/**
 * Where the registry of a deployment's indexes is kept.
 *
 * <p>The registry is one object read and replaced as a whole. Replacing it is
 * conditional on the version the writer last read, so two nodes creating or
 * promoting at the same time can not lose one another's change - which is what
 * makes creating an index a race exactly one node wins, whether or not either
 * of them holds the indexer role.
 *
 * <p>Implementations are safe for concurrent use.
 */
public interface RegistryStorage {
	/**
	 * Read the registry.
	 *
	 * @param knownVersion
	 *   version the caller already holds, or {@code null} to read whatever is
	 *   there
	 * @return
	 * @throws IOException
	 *   if the storage could not be reached
	 */
	Read read(String knownVersion) throws IOException;

	/**
	 * Replace the registry.
	 *
	 * @param indexes
	 * @param expectedVersion
	 *   version the registry is expected to be at, or {@code null} when it is
	 *   expected not to exist yet
	 * @return
	 *   the version the written registry is now at, or {@code null} when the
	 *   write was refused because someone else changed it first
	 * @throws IOException
	 *   if the storage could not be reached
	 */
	String write(IndexRegistryStore indexes, String expectedVersion) throws IOException;

	/**
	 * What a read found.
	 */
	sealed interface Read {
		/**
		 * The registry as the storage holds it.
		 */
		record Loaded(IndexRegistryStore indexes, String version) implements Read {
		}

		/**
		 * The registry is still at the version the caller already holds.
		 */
		record Unchanged() implements Read {
		}

		/**
		 * There is no registry yet, which is what a deployment that has never
		 * created an index looks like.
		 */
		record Absent() implements Read {
		}
	}
}
