package se.l4.exofind.engine.freshness;

import java.time.Duration;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.settings.InMemorySearchSettingsStorage;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * Freshness waiters for tests that build a resource by hand.
 */
public final class TestFreshnessWaiters {
	private TestFreshnessWaiters() {
	}

	/**
	 * A waiter over settings held in memory, for a test that never reads or
	 * writes settings through it.
	 */
	public static FreshnessWaiter create(Indexes indexes, IndexRegistry registry) {
		return create(
			indexes,
			new SearchSettings(
				new InMemorySearchSettingsStorage(),
				registry,
				new RegistryHints(registry, StorageMode.LOCAL),
				Duration.ofSeconds(10),
				Duration.ofMinutes(10)
			)
		);
	}

	/**
	 * A waiter over the settings a test holds, waiting ten seconds the way a
	 * node does by default.
	 */
	public static FreshnessWaiter create(Indexes indexes, SearchSettings searchSettings) {
		return new FreshnessWaiter(indexes, searchSettings, Duration.ofSeconds(10));
	}
}
