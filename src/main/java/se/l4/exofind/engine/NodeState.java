package se.l4.exofind.engine;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.storage.StorageMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * NodeState represents the state of this node.
 */
@ApplicationScoped
public class NodeState {
	/**
	 * If this node may act as the indexer. Being a candidate is decided by
	 * configuration; actually being the indexer additionally requires holding
	 * the role, which is granted through {@link #updateOwnership(boolean)}.
	 */
	private final boolean indexerCandidate;

	/**
	 * If this node currently is the indexer. Starts out {@code false} - a
	 * candidate is not the indexer until ownership has been granted to it.
	 */
	private volatile boolean indexer;

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
		this.indexer = false;

		this.listeners = new Listener[0];
		this.listenerLock = new ReentrantLock();
	}

	/**
	 * Get if this node may act as the indexer when the role is granted to it.
	 *
	 * @return
	 */
	public boolean isIndexerCandidate() {
		return indexerCandidate;
	}

	/**
	 * Get if this node supports updating indexes right now, meaning it is a
	 * candidate and currently holds the indexer role.
	 *
	 * @return
	 */
	public boolean isIndexer() {
		return indexer;
	}

	/**
	 * Update whether this node holds the indexer role. A node that is not a
	 * candidate stays read-only no matter what it is granted. Listeners are
	 * notified when the answer to {@link #isIndexer()} changes, so that open
	 * indexes can follow the node into or out of the role.
	 *
	 * @param owner
	 *   whether the indexer role is currently held by this node
	 */
	public void updateOwnership(boolean owner) {
		var newValue = indexerCandidate && owner;

		listenerLock.lock();
		try {
			if(newValue == indexer) {
				return;
			}

			indexer = newValue;

			/*
			 * Notified while holding the lock, so that two changes in quick
			 * succession reach every listener in the order they happened.
			 * Listeners hand the actual work off rather than doing it here.
			 */
			for(var listener : listeners) {
				listener.onChangeState(this);
			}
		} finally {
			listenerLock.unlock();
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
		void onChangeState(NodeState state);
	}
}
