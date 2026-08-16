package se.l4.exofind.engine.auth;

import java.io.IOException;

/**
 * Where the keys of a deployment are kept.
 *
 * <p>The store is one object read and replaced as a whole. Replacing it is
 * conditional on the version the writer last read, so two nodes changing keys
 * at the same time can not lose one another's change - which is what lets any
 * node manage keys rather than only the one holding the indexer role.
 *
 * <p>Implementations are safe for concurrent use.
 */
public interface KeyStorage {
	/**
	 * Whether this node has somewhere to keep keys. A node running without
	 * remote storage has not, and can only be reached with its root key.
	 */
	boolean isAvailable();

	/**
	 * Read the key store.
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
	 * Replace the key store.
	 *
	 * @param keys
	 * @param expectedVersion
	 *   version the store is expected to be at, or {@code null} when it is
	 *   expected not to exist yet
	 * @return
	 *   the version the written store is now at, or {@code null} when the write
	 *   was refused because someone else changed the store first
	 * @throws IOException
	 *   if the storage could not be reached, or this node has nowhere to keep
	 *   keys
	 */
	String write(KeyStore keys, String expectedVersion) throws IOException;

	/**
	 * What a read found.
	 */
	sealed interface Read {
		/**
		 * The store as the storage holds it.
		 */
		record Loaded(KeyStore keys, String version) implements Read {
		}

		/**
		 * The store is still at the version the caller already holds.
		 */
		record Unchanged() implements Read {
		}

		/**
		 * There is no store yet, which is what a deployment that has never
		 * created a key looks like.
		 */
		record Absent() implements Read {
		}
	}
}
