package se.l4.exofind.engine;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.set.ImmutableSet;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.storage.StorageMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * NodeState represents the state of this node: whether it may write at all,
 * and which indexes it currently writes.
 */
@ApplicationScoped
public class NodeState {
	/**
	 * If this node may act as an indexer. Being a candidate is decided by
	 * configuration; actually writing an index additionally requires holding
	 * it, which is granted through {@link #updateOwnership(String, boolean)}.
	 */
	private final boolean indexerCandidate;

	/**
	 * Whether every index is held at once, which is how a node that can never
	 * be contested - one storing locally - holds indexes that do not exist
	 * yet. Starts out {@code false} - a candidate holds nothing until
	 * ownership has been granted to it.
	 */
	private volatile boolean everything;

	/**
	 * The indexes held by name, for a node granted them one at a time.
	 */
	private volatile ImmutableSet<String> owned;

	private volatile Listener[] listeners;
	private final ReentrantLock listenerLock;

	/**
	 * Read whether this node is a candidate, defaulting to what the storage
	 * mode makes of the question.
	 *
	 * <p>A deployment sharing an object storage runs some nodes that only
	 * search, so candidacy is asked for. A node storing locally is the only
	 * node there is: leaving it out of the role would leave nothing able to
	 * write, and every write answered with the index being read-only.
	 *
	 * @param storageMode
	 * @param indexer
	 */
	@Inject
	NodeState(
		StorageMode storageMode,
		@ConfigProperty(name = "indexer") Optional<Boolean> indexer
	) {
		this(indexer.orElse(storageMode == StorageMode.LOCAL));
	}

	public NodeState(boolean indexer) {
		this.indexerCandidate = indexer;
		this.everything = false;
		this.owned = Sets.immutable.empty();

		this.listeners = new Listener[0];
		this.listenerLock = new ReentrantLock();
	}

	/**
	 * Get if this node may act as an indexer when indexes are granted to it.
	 *
	 * @return
	 */
	public boolean isIndexerCandidate() {
		return indexerCandidate;
	}

	/**
	 * Get if this node supports updating an index right now, meaning it is a
	 * candidate and currently holds the index.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @return
	 */
	public boolean isIndexer(String index) {
		return indexerCandidate && (everything || owned.contains(index));
	}

	/**
	 * Update whether this node holds every index at once. A node that is not
	 * a candidate stays read-only no matter what it is granted. Listeners are
	 * notified with no index name, since which indexes the change touches is
	 * every one there is.
	 *
	 * @param owner
	 *   whether every index is currently held by this node
	 */
	public void updateOwnership(boolean owner) {
		if(!indexerCandidate) {
			// Stays read-only whatever it is granted, so there is no change to tell
			return;
		}

		listenerLock.lock();
		try {
			if(owner == everything) {
				return;
			}

			everything = owner;
			notifyListeners(null, false);
		} finally {
			listenerLock.unlock();
		}
	}

	/**
	 * Update whether this node holds one index. A node that is not a
	 * candidate stays read-only no matter what it is granted. Listeners are
	 * notified when the answer to {@link #isIndexer(String)} changes, so that
	 * the open generations of the index can follow the node into or out of
	 * writing it.
	 *
	 * @param index
	 *   name of the index, without a generation, or {@code null} to grant or
	 *   revoke every index at once as {@link #updateOwnership(boolean)} does
	 * @param owner
	 *   whether the index is currently held by this node
	 */
	public void updateOwnership(String index, boolean owner) {
		if(index == null) {
			updateOwnership(owner);
			return;
		}

		if(!indexerCandidate) {
			// Stays read-only whatever it is granted, so there is no change to tell
			return;
		}

		listenerLock.lock();
		try {
			if(owned.contains(index) == owner) {
				return;
			}

			owned = owner
				? owned.newWith(index)
				: owned.newWithout(index);

			notifyListeners(index, false);
		} finally {
			listenerLock.unlock();
		}
	}

	/**
	 * Take one index away without this node having chosen to hand it over -
	 * holding it stopped being certain, so another node may already write it.
	 * Listeners are told through {@link Listener#onOwnershipRevoked}, which is
	 * what says that nothing the index still holds locally may be pushed.
	 *
	 * @param index
	 *   name of the index, without a generation
	 */
	public void revokeOwnership(String index) {
		if(!indexerCandidate) {
			// Stays read-only whatever it is granted, so there is no change to tell
			return;
		}

		listenerLock.lock();
		try {
			if(!owned.contains(index)) {
				return;
			}

			owned = owned.newWithout(index);
			notifyListeners(index, true);
		} finally {
			listenerLock.unlock();
		}
	}

	/**
	 * Tell every listener about a change, while holding the lock so that two
	 * changes in quick succession reach every listener in the order they
	 * happened. Listeners hand the actual work off rather than doing it here.
	 */
	private void notifyListeners(String index, boolean revoked) {
		for(var listener : listeners) {
			if(revoked) {
				listener.onOwnershipRevoked(this, index);
			} else {
				listener.onOwnershipChanged(this, index);
			}
		}
	}

	/**
	 * Add a listener to be notified when the state changes.
	 *
	 * @param listener
	 */
	public void addListener(Listener listener) {
		listenerLock.lock();
		try {
			var newListeners = new Listener[listeners.length + 1];
			System.arraycopy(listeners, 0, newListeners, 0, listeners.length);
			newListeners[listeners.length] = listener;
			listeners = newListeners;
		} finally {
			listenerLock.unlock();
		}
	}

	/**
	 * Remove a listener.
	 *
	 * @param listener
	 */
	public void removeListener(Listener listener) {
		listenerLock.lock();
		try {
			int found = -1;
			for(int i = 0; i < listeners.length; i++) {
				if(listeners[i] == listener) {
					found = i;
					break;
				}
			}

			if(found < 0) {
				// Listener was not registered
				return;
			}

			var newListeners = new Listener[listeners.length - 1];
			System.arraycopy(listeners, 0, newListeners, 0, found);
			System.arraycopy(
				listeners, found + 1,
				newListeners, found,
				listeners.length - found - 1
			);
			listeners = newListeners;
		} finally {
			listenerLock.unlock();
		}
	}

	@FunctionalInterface
	public interface Listener {
		/**
		 * Called when which indexes this node writes changed. Losing an index
		 * this way is a chosen handover: what it still holds locally may be
		 * pushed before the successor takes over.
		 *
		 * @param state
		 * @param index
		 *   name of the index whose ownership changed, or {@code null} when
		 *   ownership of every index changed at once
		 */
		void onOwnershipChanged(NodeState state, String index);

		/**
		 * Called when this node lost an index without holding it being
		 * certain up to now. Another node may already write the index, so
		 * nothing it still holds locally may be pushed.
		 *
		 * @param state
		 * @param index
		 *   name of the index whose ownership was revoked
		 */
		default void onOwnershipRevoked(NodeState state, String index) {
			onOwnershipChanged(state, index);
		}
	}
}
