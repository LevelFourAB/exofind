package se.l4.exofind.engine.index.state;

import java.io.IOException;

/**
 * Thrown when a push finds that the remote was changed by another node, so
 * completing it would overwrite data this node has never seen. Nothing has
 * been overwritten when this is thrown; pulling is what brings the local copy
 * back in step with the remote.
 *
 * Under a single indexer this means a second writer is running, which is a
 * misconfiguration - the refusal is what keeps it from becoming corruption.
 */
public class SyncConflictException extends IOException {
	public SyncConflictException(String message) {
		super(message);
	}
}
