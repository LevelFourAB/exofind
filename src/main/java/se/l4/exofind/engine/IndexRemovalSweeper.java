package se.l4.exofind.engine;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.StartupEvent;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.state.IndexRemovals;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.storage.StorageMode;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

/**
 * Removes from the shared storage what a delete marked, once the mark is old
 * enough. This is the second half of deleting an index: the delete takes the
 * name out of the registry and leaves a mark, see {@link IndexRemovals}, and
 * this pass removes the objects after the grace period has run out.
 *
 * <p>Runs on every node that may index, at an interval. Two nodes removing
 * the same prefix at the same time delete the same keys, which the storage
 * takes without complaint, so nothing coordinates them. A node that has not
 * read the registry yet sweeps nothing: to it every index looks unregistered.
 *
 * <p>A mark over something the registry names is passed over. It cannot be
 * acted on - the name is in use - and it is not this pass's to take away: a
 * delete whose registry write went through leaves no such mark, and one that
 * did not has left nothing to remove.
 *
 * <p>Failures are logged and left for the next pass; what was removed stays
 * removed, and a mark stays until everything under it is gone.
 */
@Singleton
public class IndexRemovalSweeper {
	private static final Log logger = Log.of(IndexRemovalSweeper.class);

	private final IndexRegistry registry;
	private final IndexRemovals removals;
	private final Duration grace;
	private final Duration interval;
	private final boolean enabled;

	private final ScheduledExecutorService executor;

	public IndexRemovalSweeper(
		IndexRegistry registry,
		IndexRemovals removals,
		NodeState nodeState,
		StorageMode storageMode,
		@ConfigProperty(name = "exofind.indexes.removal.grace", defaultValue = "1h")
		Duration grace,
		@ConfigProperty(name = "exofind.indexes.removal.sweep-interval", defaultValue = "10m")
		Duration interval
	) {
		this.registry = registry;
		this.removals = removals;
		this.grace = grace;
		this.interval = interval;

		/*
		 * Only a shared storage holds anything to remove, and a node that
		 * cannot index cannot be trusted with removing what the indexers
		 * wrote - it may lack the credentials, and it is the smaller set of
		 * nodes that has to agree on the rules.
		 */
		this.enabled = storageMode == StorageMode.OBJECT && nodeState.isIndexerCandidate();

		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "index-removal-sweep");
			thread.setDaemon(true);
			return thread;
		});
	}

	void onStart(@Observes StartupEvent event) {
		if(!enabled) {
			return;
		}

		executor.scheduleWithFixedDelay(
			this::pass,
			interval.toMillis(),
			interval.toMillis(),
			TimeUnit.MILLISECONDS
		);
	}

	@PreDestroy
	void stop() {
		executor.shutdownNow();
	}

	/**
	 * Remove everything whose mark is older than the grace period and whose
	 * name the registry does not hold.
	 */
	void pass() {
		if(!registry.hasBeenRead()) {
			return;
		}

		ListIterable<IndexRemovals.Mark> marks;
		try {
			marks = removals.listMarks(target -> !isRegistered(target));
		} catch(IOException e) {
			logger.atWarn()
				.setCause(e)
				.log("Could not look for deleted indexes to remove; " + e.getMessage());

			return;
		}

		var cutoff = Instant.now().minus(grace);

		for(var mark : marks) {
			// The registry may have moved since the listing asked
			if(isRegistered(mark.target())) {
				continue;
			}

			if(mark.removedAt().isAfter(cutoff)) {
				logger.atDebug()
					.addKeyValue("index", mark.target().toString())
					.addKeyValue("removedAt", mark.removedAt())
					.log("Deleted index is within its grace period, leaving its objects");

				continue;
			}

			try {
				if(removals.remove(mark.target())) {
					logger.atInfo()
						.addKeyValue("index", mark.target().toString())
						.addKeyValue("removedAt", mark.removedAt())
						.log("Removed the objects of a deleted index from the storage");
				}
			} catch(IOException | RuntimeException e) {
				logger.atWarn()
					.addKeyValue("index", mark.target().toString())
					.setCause(e)
					.log("Could not remove the objects of a deleted index; " + e.getMessage());
			}
		}
	}

	/**
	 * Whether the registry names an index, or the generation of it that a
	 * mark stands over.
	 */
	private boolean isRegistered(IndexName target) {
		var index = registry.get(target.index());
		if(index.isEmpty()) {
			return false;
		}

		return !target.isPinned() || index.get().hasGeneration(target.generation());
	}
}
