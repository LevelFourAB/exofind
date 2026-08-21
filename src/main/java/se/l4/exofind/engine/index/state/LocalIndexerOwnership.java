package se.l4.exofind.engine.index.state;

import java.util.Optional;

import se.l4.exofind.engine.logging.Log;

/**
 * IndexerOwnership for a node keeping everything on its own disk, where
 * nothing can contest any index - a candidate simply holds them all, the ones
 * that do not exist yet included.
 */
public class LocalIndexerOwnership implements IndexerOwnership {
	private static final Log logger = Log.of(LocalIndexerOwnership.class);

	private volatile boolean started;

	@Override
	public void start(Listener listener) {
		/*
		 * Logged even though it cannot go any other way, so that whether a node
		 * writes is answered the same way in both storage modes and the line to
		 * look for does not depend on which one a deployment runs.
		 */
		logger.atInfo().log("Acquired the indexer role");

		started = true;
		listener.onOwnershipChanged(null, true);
	}

	@Override
	public void stop() {
	}

	@Override
	public boolean tryClaim(String index) {
		// Everything is already held, so a claim only says whether this node competes
		return started;
	}

	@Override
	public boolean hasHolder(String index) {
		// This node holds everything there is, existing or not
		return started;
	}

	@Override
	public Optional<String> indexerAddress(String index) {
		// Without a remote there is no other node to send a write to
		return Optional.empty();
	}
}
