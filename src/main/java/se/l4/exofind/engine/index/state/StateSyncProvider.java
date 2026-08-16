package se.l4.exofind.engine.index.state;

import java.nio.file.Path;

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
}
