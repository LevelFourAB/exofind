package se.l4.exofind.engine.index.state;

import java.util.Optional;

/**
 * IndexerOwnership for a node keeping everything on its own disk, where nothing
 * can contest the role - a candidate simply holds it.
 */
public class LocalIndexerOwnership implements IndexerOwnership {
	@Override
	public void start(Listener listener) {
		listener.onOwnershipChanged(true);
	}

	@Override
	public void stop() {
	}

	@Override
	public Optional<String> indexerAddress() {
		// Without a remote there is no other node to point a caller at
		return Optional.empty();
	}
}
