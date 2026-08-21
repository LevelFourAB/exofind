package se.l4.exofind.engine.index.state;

import java.util.Optional;

/**
 * IndexerOwnership decides which indexes this node currently writes. Only
 * one node writes an index at a time, but different indexes may be written
 * by different nodes; an implementation divides them among the candidates
 * through whatever the deployment shares - the object storage itself by
 * default - and reports every change to the listener.
 *
 * Holding an index is a liveness matter: it decides who tries to write it.
 * The conditional writes of the manifests are what keep a node that wrongly
 * believes it holds an index from doing damage, so an implementation does
 * not have to be airtight, it has to converge on one holder per index and
 * notice losing.
 *
 * Indexes are held by name; every generation of a name is written by the
 * node holding it.
 */
public interface IndexerOwnership {
	/**
	 * Start competing for indexes. The listener is called with {@code true}
	 * when this node gains an index and {@code false} when it loses one, from
	 * whatever thread the implementation coordinates on - listeners hand real
	 * work off rather than doing it in the call.
	 *
	 * @param listener
	 */
	void start(Listener listener);

	/**
	 * Stop competing, giving up every held index for successors. Handled
	 * like a handover this node chose: the listener hears about every loss,
	 * and an index is given up only once what it still held here has been
	 * pushed - so a successor that takes it at once never pulls a manifest
	 * the shutdown flush had not written yet.
	 */
	void stop();

	/**
	 * Try to take one index here and now, for serving a write that found no
	 * holder - which is every index the moment it is created, and every index
	 * whose holder died until a renewal round picks its claims up. An index
	 * another node holds is never taken, and a node that is not competing
	 * takes nothing.
	 *
	 * <p>May read and write the shared state, so a caller is waiting on the
	 * storage - the cost of the moment where an index has no holder, not of
	 * every write.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @return
	 *   whether this node now holds the index
	 */
	boolean tryClaim(String index);

	/**
	 * Whether some node - this one included - currently writes the index.
	 * Answers from what this node knows rather than by coordinating, so the
	 * answer may lag reality the way {@link #indexerAddress(String)} does.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @return
	 */
	boolean hasHolder(String index);

	/**
	 * The address writes to an index should be sent to: the node holding it,
	 * or - for an index nothing holds - a candidate that could take it by
	 * serving the write. Empty when there is neither, when the node in
	 * question offered no address, or when it is this node - a node never
	 * forwards to itself.
	 *
	 * Answers may lag reality by a short while, so a request can be passed to
	 * a node that just lost the index - which refuses it rather than passing
	 * it along again, and the caller retries against fresher answers.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @return
	 */
	Optional<String> indexerAddress(String index);

	@FunctionalInterface
	interface Listener {
		/**
		 * Called when this node gains an index or hands one over. Losing an
		 * index this way is deliberate: holding it was certain up to this
		 * call, so what it holds locally may still be pushed before a
		 * successor takes over.
		 *
		 * @param index
		 *   name of the index, or {@code null} when ownership of every index
		 *   changed at once - which is how a node that can never be contested
		 *   holds them all without naming them
		 * @param owner
		 */
		void onOwnershipChanged(String index, boolean owner);

		/**
		 * Called when this node lost an index without holding it being
		 * certain up to now - its claims lapsed before they could be renewed.
		 * Another node may already write the index, so nothing it holds
		 * locally may be pushed.
		 *
		 * @param index
		 *   name of the index
		 */
		default void onOwnershipRevoked(String index) {
			onOwnershipChanged(index, false);
		}
	}
}
