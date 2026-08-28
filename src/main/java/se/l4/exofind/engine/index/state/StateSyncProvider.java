package se.l4.exofind.engine.index.state;

import java.io.IOException;
import java.nio.file.Path;
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
}
