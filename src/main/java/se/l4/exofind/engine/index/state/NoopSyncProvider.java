package se.l4.exofind.engine.index.state;

import java.nio.file.Path;

import se.l4.exofind.engine.index.IndexName;

/**
 * NoopSyncProvider provides instances of {@link NoopSync}.
 */
public class NoopSyncProvider implements StateSyncProvider {
	private static final NoopSync NOOP_SYNC = new NoopSync();

	@Override
	public StateSync createSync(IndexName generation, Path dataPath) {
		return NOOP_SYNC;
	}
}
