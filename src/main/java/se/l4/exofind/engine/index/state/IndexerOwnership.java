package se.l4.exofind.engine.index.state;

import java.util.Optional;

/**
 * IndexerOwnership decides whether this node currently holds the indexer
 * role. Only one node writes to the indexes at a time; an implementation
 * coordinates that through whatever the deployment shares - the object
 * storage itself by default - and reports every change to the listener.
 *
 * Holding the role is a liveness matter: it decides who tries to write. The
 * conditional writes of the manifests are what keep a node that wrongly
 * believes it holds the role from doing damage, so an implementation does not
 * have to be airtight, it has to converge on one holder and notice losing.
 */
public interface IndexerOwnership {
	/**
	 * Start trying to hold the indexer role. The listener is called with
	 * {@code true} when this node gains the role and {@code false} when it
	 * loses it, from whatever thread the implementation coordinates on -
	 * listeners hand real work off rather than doing it in the call.
	 *
	 * @param listener
	 */
	void start(Listener listener);

	/**
	 * Stop trying to hold the indexer role, giving it up for a successor if
	 * it is currently held. The listener is not called for that - this runs
	 * on shutdown, where there is nothing left to react.
	 */
	void stop();

	/**
	 * The address the current indexer serves writes on, used to point a
	 * caller that reached the wrong node at the right one. Empty when there
	 * is no indexer right now, when it offered no address, or when the
	 * indexer is this node - a node never points a caller back at itself.
	 *
	 * Answers may lag reality by a short while, so a caller can be sent to a
	 * node that just lost the role - it answers the same way this one did,
	 * with whatever it knows.
	 *
	 * @return
	 */
	Optional<String> indexerAddress();

	@FunctionalInterface
	interface Listener {
		void onOwnershipChanged(boolean owner);
	}
}
