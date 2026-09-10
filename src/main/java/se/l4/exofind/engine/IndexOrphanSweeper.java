package se.l4.exofind.engine;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.StartupEvent;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.storage.StorageMode;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

/**
 * Removes from the shared storage the objects of an index that no manifest
 * names anymore, on a timer. A push sweeps the index it pushes, so an index
 * that is written needs no timer. An index that receives no writes is
 * otherwise never swept, and the uploads of a push that died stay in the
 * bucket.
 *
 * <p>Runs on every node that may index, at the interval of the removal
 * sweep, see {@link IndexRemovalSweeper}. Every tick asks each open
 * generation this node writes to sweep, and the generation decides whether
 * a sweep is due, so one generation is listed at most once per grace period.
 *
 * <p>Failures are logged and left for the next pass.
 */
@Singleton
public class IndexOrphanSweeper {
	private static final Log logger = Log.of(IndexOrphanSweeper.class);

	private final Indexes indexes;
	private final Duration interval;
	private final boolean enabled;

	private final ScheduledExecutorService executor;

	public IndexOrphanSweeper(
		Indexes indexes,
		NodeState nodeState,
		StorageMode storageMode,
		@ConfigProperty(name = "exofind.indexes.removal.sweep-interval", defaultValue = "10m")
		Duration interval
	) {
		this.indexes = indexes;
		this.interval = interval;

		// A node that cannot write an index holds no claim to sweep under
		this.enabled = storageMode == StorageMode.OBJECT && nodeState.isIndexerCandidate();

		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "index-orphan-sweep");
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
	 * Ask every open generation this node writes to sweep its remote.
	 */
	void pass() {
		try {
			indexes.sweepRemoteOrphans();
		} catch(RuntimeException e) {
			// Letting this out would cancel the schedule
			logger.atLevel(Interruptions.levelOf(e))
				.setCause(e)
				.log("Could not sweep for orphaned remote objects; " + e.getMessage());
		}
	}
}
