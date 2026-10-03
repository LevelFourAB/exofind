package se.l4.exofind.engine.index.state;

/**
 * NoopSyncProvider that says a remote holds the generations, for tests of a
 * node whose directories are copies the way they are in object storage mode.
 * Nothing is synchronized.
 */
public class NoopRemoteSyncProvider extends NoopSyncProvider {
	@Override
	public boolean hasRemote() {
		return true;
	}
}
