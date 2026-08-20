package se.l4.exofind.engine.index.state;

import java.util.Optional;

import se.l4.exofind.engine.logging.Log;

/**
 * IndexerOwnership for a node keeping everything on its own disk, where nothing
 * can contest the role - a candidate simply holds it.
 */
public class LocalIndexerOwnership implements IndexerOwnership {
	private static final Log logger = Log.of(LocalIndexerOwnership.class);

	@Override
	public void start(Listener listener) {
		/*
		 * Logged even though it cannot go any other way, so that whether a node
		 * writes is answered the same way in both storage modes and the line to
		 * look for does not depend on which one a deployment runs.
		 */
		logger.atInfo().log("Acquired the indexer role");

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
