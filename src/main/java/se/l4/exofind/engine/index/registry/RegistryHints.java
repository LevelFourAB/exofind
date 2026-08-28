package se.l4.exofind.engine.index.registry;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.collections.api.factory.Lists;

import se.l4.exofind.engine.storage.StorageMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

/**
 * Collects {@link VersionHint version hints} and folds them into the registry
 * a moment later, so that a burst of pushes costs one conditional registry
 * write rather than one each - the registry is one object every node contends
 * for, and a hint gains nothing from arriving alone.
 *
 * <p>Reporting never blocks on the storage and never fails the caller: hints
 * are advisory, and one that could not be written is retried on the next flush
 * for as long as this node runs. A hint reported between a crash and its flush
 * is simply lost, which the readers of the hints already withstand - they read
 * the object itself at an interval whatever the hints say.
 *
 * <p>A node storing locally is the only node there is, so there is nobody to
 * hint and nothing is collected or written.
 *
 * <p>Safe for concurrent use.
 */
@Singleton
public class RegistryHints {
	/**
	 * How long reported hints are allowed to gather before they are written.
	 * Sits under the shortest interval anything polls at, so waiting for a
	 * flush never becomes what a reader notices - and over the pace of an
	 * index committing, so a busy writer reports whole batches.
	 */
	private static final Duration FLUSH_INTERVAL = Duration.ofSeconds(5);

	private final IndexRegistry registry;
	private final boolean enabled;

	/**
	 * The settings version to report per index. Replaced whole on a new
	 * report - settings versions carry no order, and the last write is the
	 * one the object holds.
	 */
	private final ConcurrentHashMap<String, String> settings = new ConcurrentHashMap<>();

	/**
	 * The manifest version to report per generation, by full name. Merged by
	 * keeping the larger value, as manifest versions only grow.
	 */
	private final ConcurrentHashMap<GenerationKey, Long> manifests = new ConcurrentHashMap<>();

	private record GenerationKey(String index, String generation) {
	}

	private final ScheduledExecutorService executor;

	public RegistryHints(IndexRegistry registry, StorageMode storageMode) {
		this.registry = registry;
		this.enabled = storageMode == StorageMode.OBJECT;

		this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "registry-hints");
			thread.setDaemon(true);
			return thread;
		});
	}

	void onStart(@Observes StartupEvent event) {
		if(!enabled) {
			return;
		}

		executor.scheduleWithFixedDelay(
			this::flush,
			FLUSH_INTERVAL.toMillis(),
			FLUSH_INTERVAL.toMillis(),
			TimeUnit.MILLISECONDS
		);
	}

	@PreDestroy
	void stop() {
		executor.shutdownNow();

		/*
		 * One last attempt on the caller's thread, so that hints reported by
		 * the final pushes of a shutdown are not lost just for arriving inside
		 * the flush interval.
		 */
		if(enabled) {
			flush();
		}
	}

	/**
	 * Report the version an index's settings object was stored at.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @param version
	 *   the stored version, or {@code null} when the settings were removed
	 */
	public void reportSettings(String index, String version) {
		if(!enabled) {
			return;
		}

		settings.put(index, version == null ? "" : version);
	}

	/**
	 * Report the version a generation's manifest was pushed at.
	 *
	 * @param index
	 *   name of the index
	 * @param generation
	 *   name of the generation
	 * @param version
	 */
	public void reportManifest(String index, String generation, long version) {
		if(!enabled) {
			return;
		}

		manifests.merge(new GenerationKey(index, generation), version, Math::max);
	}

	/**
	 * Write the gathered hints into the registry. Hints that could not be
	 * written go back to being pending, without overriding anything reported
	 * while the flush ran.
	 */
	void flush() {
		var pending = Lists.mutable.<VersionHint>empty();

		for(var index : settings.keySet()) {
			var version = settings.remove(index);
			if(version != null) {
				pending.add(new VersionHint.Settings(index, version));
			}
		}

		for(var key : manifests.keySet()) {
			var version = manifests.remove(key);
			if(version != null) {
				pending.add(new VersionHint.Manifest(key.index(), key.generation(), version));
			}
		}

		if(pending.isEmpty() || registry.updateHints(pending)) {
			return;
		}

		for(var hint : pending) {
			switch(hint) {
				case VersionHint.Settings s -> settings.putIfAbsent(s.index(), s.version());
				case VersionHint.Manifest m -> manifests.merge(
					new GenerationKey(m.index(), m.generation()),
					m.version(),
					Math::max
				);
			}
		}
	}
}
