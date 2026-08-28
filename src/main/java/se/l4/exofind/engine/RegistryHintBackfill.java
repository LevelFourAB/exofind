package se.l4.exofind.engine;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.settings.SearchSettingsStorage;
import se.l4.exofind.engine.index.state.StateSyncProvider;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.storage.StorageMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

/**
 * Gives version hints to registry entries that have none, so a deployment
 * that predates the hints - or lost one - ends up fully covered rather than
 * covered only where something has been written since.
 *
 * <p>Everything written after the hints exist reports its own version: a push
 * reports the manifest, storing settings reports the settings. What nothing
 * reports is the indexes that simply sit there, and those are exactly the
 * ones whose polling the hints are meant to spare. So each index's writer
 * reads what the storage holds for the entries the registry says nothing
 * about - once, since the answer becomes a hint - and the other nodes can
 * stop asking.
 *
 * <p>Runs where the index's writer is, a few entries at a time, so a large
 * deployment fills in over minutes without a burst of requests. An entry it
 * cannot fill now is tried again on a later pass. Nothing here is needed for
 * correctness - a node reads an object it has no hint for the way it always
 * did - so failures are logged quietly and never propagate.
 */
@Singleton
public class RegistryHintBackfill {
	private static final Log logger = Log.of(RegistryHintBackfill.class);

	/**
	 * How many storage reads one pass may spend. Bounds what filling in a
	 * large deployment adds to a pass, at the cost of taking more passes.
	 */
	private static final int MAX_LOOKUPS_PER_PASS = 25;

	private final IndexRegistry registry;
	private final RegistryHints hints;
	private final NodeState nodeState;
	private final SearchSettingsStorage settingsStorage;
	private final StateSyncProvider syncProvider;
	private final boolean enabled;
	private final Duration interval;

	private final ScheduledExecutorService executor;

	public RegistryHintBackfill(
		IndexRegistry registry,
		RegistryHints hints,
		NodeState nodeState,
		SearchSettingsStorage settingsStorage,
		StateSyncProvider syncProvider,
		StorageMode storageMode,
		@ConfigProperty(name = "exofind.indexes.refresh-interval", defaultValue = "30s")
		Duration interval
	) {
		this.registry = registry;
		this.hints = hints;
		this.nodeState = nodeState;
		this.settingsStorage = settingsStorage;
		this.syncProvider = syncProvider;
		this.interval = interval;

		/*
		 * Only a shared storage has other nodes to hint, and only a candidate
		 * ever holds the writer role the filling runs under.
		 */
		this.enabled = storageMode == StorageMode.OBJECT && nodeState.isIndexerCandidate();

		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "registry-hint-backfill");
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
	 * Fill in hints for up to {@link #MAX_LOOKUPS_PER_PASS} entries this node
	 * writes that have none.
	 */
	void pass() {
		if(!registry.hasBeenRead()) {
			return;
		}

		var lookups = 0;

		for(var index : registry.list()) {
			if(lookups >= MAX_LOOKUPS_PER_PASS) {
				return;
			}

			if(!nodeState.isIndexer(index.name())) {
				continue;
			}

			if(index.settingsVersion() == null) {
				lookups++;
				fillSettings(index.name());
			}

			for(var generation : index.generations()) {
				if(lookups >= MAX_LOOKUPS_PER_PASS) {
					return;
				}

				if(generation.manifestVersion() == null) {
					lookups++;
					fillManifest(index.name(), generation.name());
				}
			}
		}
	}

	private void fillSettings(String index) {
		try {
			switch(settingsStorage.read(index, null)) {
				case SearchSettingsStorage.Read.Loaded loaded ->
					hints.reportSettings(index, loaded.version());
				case SearchSettingsStorage.Read.Absent absent ->
					hints.reportSettings(index, null);
				case SearchSettingsStorage.Read.Unchanged unchanged -> {
					// Not answered without a version to be unchanged against
				}
			}
		} catch(IOException e) {
			logger.atDebug()
				.addKeyValue("index", index)
				.setCause(e)
				.log("Could not read the settings for a hint; " + e.getMessage());
		}
	}

	private void fillManifest(String index, String generation) {
		try {
			var version = syncProvider.remoteVersion(IndexName.of(index, generation));

			/*
			 * A generation without a manifest is reported as version zero, a
			 * version no push ever writes. Without it the absence would be
			 * probed again on every pass, and every node would keep asking
			 * the storage for a manifest that is not there.
			 */
			hints.reportManifest(index, generation, version.orElse(0));
		} catch(IOException e) {
			logger.atDebug()
				.addKeyValue("index", index)
				.addKeyValue("generation", generation)
				.setCause(e)
				.log("Could not read the manifest version for a hint; " + e.getMessage());
		}
	}
}
