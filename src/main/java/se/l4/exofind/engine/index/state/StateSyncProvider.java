package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.OptionalLong;

import se.l4.exofind.engine.index.IndexName;

/**
 * StateSyncProvider is a factory for {@link StateSync} instances.
 */
public interface StateSyncProvider {
	/**
	 * Create a new {@link StateSync} for one generation of an index.
	 *
	 * @param generation
	 *        the generation, which is what decides where in the remote its
	 *        files live
	 * @param dataPath
	 *        the path that stores index data
	 * @return
	 */
	StateSync createSync(IndexName generation, Path dataPath);

	/**
	 * Create a new {@link StateSync} for one generation of an index as the
	 * registry names it.
	 *
	 * @param generation
	 *        the generation, which is what decides where in the remote its
	 *        files live
	 * @param dataPath
	 *        the path that stores index data
	 * @param createdAt
	 *        when the generation was created, as the registry states it, or
	 *        {@code null} when not known. A sync that keeps a remote copy
	 *        records it with what it pushes, so that a copy of a deleted
	 *        generation is told from a generation created again under the
	 *        same name
	 * @return
	 */
	default StateSync createSync(IndexName generation, Path dataPath, Instant createdAt) {
		return createSync(generation, dataPath);
	}

	/**
	 * Read the version of the manifest a generation holds in the remote,
	 * without a local copy being involved. Used to report a version hint for
	 * a generation this node does not hold open - one it holds open answers
	 * through {@link StateSync#syncedVersion()} without a request.
	 *
	 * @param generation
	 * @return
	 *   the version, empty when the remote holds no manifest for the
	 *   generation, the manifest predates versions, or there is no remote
	 * @throws IOException
	 *   if the remote could not be asked
	 */
	OptionalLong remoteVersion(IndexName generation) throws IOException;

	/**
	 * Whether the local directories are copies of generations that a remote
	 * holds. A node removes a local copy that the registry no longer names
	 * only when this is {@code true}. When this is {@code false}, a node
	 * refuses to open a generation that the registry names and whose
	 * directory is gone.
	 *
	 * @return
	 *   {@code false} by default, which is what local storage mode answers:
	 *   there the local directories are the only copy of the data
	 */
	default boolean hasRemote() {
		return false;
	}
}
