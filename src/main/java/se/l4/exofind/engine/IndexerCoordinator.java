package se.l4.exofind.engine;

import se.l4.exofind.engine.index.state.IndexerOwnership;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * IndexerCoordinator connects ownership of the indexes to the state of this
 * node. A candidate starts competing for them when the application starts;
 * the open indexes follow the node into and out of writing them through the
 * listeners on {@link NodeState}.
 */
@ApplicationScoped
public class IndexerCoordinator {
	private final NodeState nodeState;
	private final IndexerOwnership ownership;

	private boolean started;

	IndexerCoordinator(NodeState nodeState, IndexerOwnership ownership) {
		this.nodeState = nodeState;
		this.ownership = ownership;
	}

	void onStart(@Observes StartupEvent event) {
		if(!nodeState.isIndexerCandidate()) {
			// A node that may never index has no role to compete for
			return;
		}

		ownership.start(new IndexerOwnership.Listener() {
			@Override
			public void onOwnershipChanged(String index, boolean owner) {
				nodeState.updateOwnership(index, owner);
			}

			@Override
			public void onOwnershipRevoked(String index) {
				nodeState.revokeOwnership(index);
			}
		});
		started = true;
	}

	@PreDestroy
	void stop() {
		if(started) {
			ownership.stop();
		}
	}
}
