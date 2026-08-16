package se.l4.exofind.engine;

import se.l4.exofind.engine.index.state.IndexerOwnership;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * IndexerCoordinator connects ownership of the indexer role to the state of
 * this node. A candidate starts competing for the role when the application
 * starts; the open indexes follow the node into and out of it through the
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

		ownership.start(nodeState::updateOwnership);
		started = true;
	}

	@PreDestroy
	void stop() {
		if(started) {
			ownership.stop();
		}
	}
}
