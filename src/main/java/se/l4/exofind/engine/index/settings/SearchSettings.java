package se.l4.exofind.engine.index.settings;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.registry.RegistryPoller;
import se.l4.exofind.engine.index.schema.RankingConfig;
import se.l4.exofind.engine.logging.Log;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

/**
 * The search settings of the indexes as this node knows them.
 *
 * <p>Settings live in the storage rather than in the definitions, so a change
 * made on one node reaches every node without waiting for an index sync. Each
 * node keeps its own copy per index. {@link RegistryPoller} hands over the
 * registry - one object however many indexes there are - and an index's own
 * object is read only when the registry's version hint says it changed, when
 * the registry says nothing about it, or when {@code indexes.verify-interval}
 * has passed since the last read. The poller reads at least every
 * {@code exofind.settings.refresh-interval}, and no index's object is read
 * twice inside that interval, so it bounds both how long a change takes to
 * reach a node already serving the index and what a node spends on one that
 * changes continuously. A search on the node that stored the change sees it at
 * once.
 *
 * <p>Writing settings needs no coordination of its own. Each index's settings
 * are a single object replaced conditionally on the version it was read at, so
 * a node that raced another is refused and tries again on top of what the
 * other wrote. The version each write ends at is reported as a hint through
 * {@link RegistryHints}, which is what lets the other nodes skip reading.
 *
 * <p>An object that needs features this build does not have is set aside whole
 * - the index searches with its definition alone - rather than applied in
 * part, and the names it needed are reported in the snapshot so a caller can
 * see why the settings are not in force.
 *
 * <p>Safe for concurrent use.
 */
@Singleton
public class SearchSettings implements RegistryPoller.Listener {
	private static final Log logger = Log.of(SearchSettings.class);

	/**
	 * How many times a change is rebuilt on top of a concurrent one before
	 * giving up. Losing three races in a row means something is writing the
	 * settings continuously, which is not a state waiting longer improves.
	 */
	private static final int WRITE_ATTEMPTS = 3;

	/**
	 * How long an index's settings are kept without anyone asking for them
	 * before this node lets go of them. A deleted or idle index would
	 * otherwise be read forever. A search arriving later is answered from a
	 * fresh read of the object.
	 */
	private static final Duration IDLE_PERIOD = Duration.ofMinutes(2);

	private final SearchSettingsStorage storage;
	private final IndexRegistry registry;
	private final RegistryHints registryHints;
	private final Duration refreshInterval;

	/**
	 * How long a copy may go without the object being asked for when the
	 * registry's hint says it is current. Bounds the staleness of a copy
	 * whose hint is stale or was lost.
	 */
	private final Duration verifyInterval;

	/**
	 * {@link #refreshInterval} held under {@link #verifyInterval}, so a
	 * refresh interval configured above the verify interval cannot suppress
	 * the read the verify interval promises.
	 */
	private final Duration minRead;

	private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

	/**
	 * Runs the refresh passes {@link RegistryPoller} hands over. One thread,
	 * so two never run next to each other.
	 */
	private final ExecutorService executor;

	/**
	 * The settings of one index as of one read of the storage, together with
	 * the version they were read at.
	 *
	 * @param stored
	 *   the object as the storage holds it
	 * @param ranking
	 *   the ranking to search with instead of the definition's, or
	 *   {@code null} when the object carries none or could not be honoured
	 * @param synonyms
	 *   the synonym sets to widen the text of a search with, by name; empty
	 *   when the object carries none or could not be honoured
	 * @param typoExclusions
	 *   the word lists to match as they are spelled, by name; empty when the
	 *   object carries none or could not be honoured
	 * @param fields
	 *   how a search reads single fields, by field name; empty when the
	 *   object carries none or could not be honoured
	 * @param unsupportedFeatures
	 *   what the object needs that this build does not have, sorted; empty
	 *   when it is in force
	 * @param version
	 */
	public record Snapshot(
		SearchSettingsStore stored,
		RankingConfig ranking,
		Map<String, QuerySynonyms> synonyms,
		Map<String, QueryTypoExclusions> typoExclusions,
		Map<String, FieldSettings> fields,
		ListIterable<String> unsupportedFeatures,
		String version
	) {
	}

	/**
	 * What this node holds for one index name: the settings as last read, and
	 * when they were last read and asked for.
	 */
	private static final class Entry {
		/**
		 * The last successful read - a snapshot, or {@code null} inside the
		 * holder when the index is known to have no settings. {@code null}
		 * as a whole means no read has succeeded yet.
		 */
		volatile Cached cached;

		volatile long lastAccessNanos;

		/**
		 * The registry's version hint as it stood when the refresh last read
		 * the object, which is what a later hint is compared against - a
		 * hint that has not moved since the last read says the read would
		 * find what the copy already holds. {@code null} until a refresh has
		 * read under a hint. Deliberately not the copy's own version: a hint
		 * that is simply wrong then costs one read, not one per interval.
		 */
		volatile String lastSeenHint;

		/**
		 * When the object was last read from the storage, whatever the read
		 * answered, as {@link System#nanoTime()}. What the verify interval
		 * is measured against.
		 */
		volatile long lastReadNanos;

		/**
		 * Lock held while deciding whether an access that found nothing may go
		 * and read the storage, so a run of them causes one read rather than
		 * one each.
		 */
		final Object forcedReadLock = new Object();
		long lastForcedReadNanos;
		boolean forcedReadEver;
	}

	private record Cached(Snapshot snapshot) {
	}

	public SearchSettings(
		SearchSettingsStorage storage,
		IndexRegistry registry,
		RegistryHints registryHints,
		@ConfigProperty(name = "exofind.settings.refresh-interval", defaultValue = "10s")
		Duration refreshInterval,
		@ConfigProperty(name = "exofind.indexes.verify-interval", defaultValue = "10m")
		Duration verifyInterval
	) {
		this.storage = storage;
		this.registry = registry;
		this.registryHints = registryHints;
		this.refreshInterval = refreshInterval;
		this.verifyInterval = verifyInterval;
		this.minRead = refreshInterval.compareTo(verifyInterval) < 0
			? refreshInterval
			: verifyInterval;

		this.executor = Executors.newSingleThreadExecutor(runnable -> {
			var thread = new Thread(runnable, "search-settings");
			thread.setDaemon(true);
			return thread;
		});
	}

	@PreDestroy
	void stop() {
		executor.shutdownNow();
	}

	@Override
	public Optional<Duration> pollInterval() {
		/*
		 * An index nobody has asked about has no copy to keep current, so a
		 * node serving no searches asks for no reads at all.
		 */
		return entries.isEmpty() ? Optional.empty() : Optional.of(refreshInterval);
	}

	@Override
	public Executor executor() {
		return executor;
	}

	@Override
	public void onRegistryPolled(boolean changed) {
		refresh();
	}

	/**
	 * Get the settings of an index as this node holds them, for a search to
	 * run with. Never reaches the storage once a copy is held - the refresh is
	 * what keeps it current - so a search costs no read; an index this node
	 * has not held settings for is looked up at most once per refresh
	 * interval, so brand-new settings work without waiting for the next read.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @return
	 *   the settings, empty when the index has none or they could not be read
	 */
	public Optional<Snapshot> get(String index) {
		var entry = entryOf(index);

		var cached = entry.cached;
		if(cached != null) {
			return Optional.ofNullable(cached.snapshot());
		}

		if(forcedReadAllowed(entry)) {
			readInto(index, entry);
			cached = entry.cached;
		}

		return cached == null ? Optional.empty() : Optional.ofNullable(cached.snapshot());
	}

	/**
	 * Read the settings of an index from the storage, for answering what is
	 * stored rather than what this node happens to hold.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @return
	 *   the settings, empty when the index has none
	 * @throws SearchSettingsException
	 *   if the storage could not be read
	 */
	public Optional<Snapshot> read(String index) {
		var entry = entryOf(index);

		if(!readInto(index, entry)) {
			throw SearchSettingsException.ioError(null);
		}

		var cached = entry.cached;
		return cached == null ? Optional.empty() : Optional.ofNullable(cached.snapshot());
	}

	/**
	 * Replace the settings of an index.
	 *
	 * <p>Takes effect for searches on this node at once and on every other
	 * node within its refresh interval.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @param settings
	 *   the object to store, its required features already described
	 * @param expectedVersion
	 *   version the stored settings are expected to be at, or {@code null} to
	 *   replace whatever is there - rebuilt on top of a concurrent change
	 *   rather than overwriting it
	 * @return
	 *   the settings as stored
	 * @throws SearchSettingsVersionMismatchException
	 *   if a version was expected and the stored settings are no longer at it
	 * @throws SearchSettingsException
	 *   if this node cannot keep settings, the storage could not be reached,
	 *   or the settings kept being changed by someone else
	 */
	public Snapshot put(String index, SearchSettingsStore settings, String expectedVersion) {
		if(!storage.isAvailable()) {
			throw SearchSettingsException.unavailable();
		}

		var entry = entryOf(index);

		if(expectedVersion != null) {
			/*
			 * The caller named the version to build on, so a mismatch is theirs
			 * to resolve - retrying on a fresher one would overwrite the very
			 * change they asked to be told about.
			 */
			var version = write(index, settings, expectedVersion);
			if(version == null) {
				throw new SearchSettingsVersionMismatchException(index);
			}

			return adopt(index, entry, settings, version);
		}

		for(var attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
			String current;
			try {
				current = switch(storage.read(index, null)) {
					case SearchSettingsStorage.Read.Loaded loaded -> loaded.version();
					case SearchSettingsStorage.Read.Absent absent -> null;
					case SearchSettingsStorage.Read.Unchanged unchanged ->
						throw new IllegalStateException(
							"A read holding no version cannot be answered as unchanged"
						);
				};
			} catch(IOException e) {
				throw SearchSettingsException.ioError(e);
			}

			var version = write(index, settings, current);
			if(version != null) {
				return adopt(index, entry, settings, version);
			}
		}

		throw SearchSettingsException.conflict();
	}

	private String write(String index, SearchSettingsStore settings, String expectedVersion) {
		try {
			return storage.write(index, settings, expectedVersion);
		} catch(IOException e) {
			throw SearchSettingsException.ioError(e);
		}
	}

	private Snapshot adopt(String index, Entry entry, SearchSettingsStore settings, String version) {
		var snapshot = decode(settings, version);
		entry.cached = new Cached(snapshot);
		entry.lastReadNanos = System.nanoTime();

		/*
		 * Reported so every other node learns of the version from the registry
		 * it already reads. Seen as already acted on here - the write is what
		 * the hint describes - so this node's next refresh does not read back
		 * what it just stored.
		 */
		entry.lastSeenHint = version;
		registryHints.reportSettings(index, version);

		return snapshot;
	}

	/**
	 * Remove the settings of an index, returning it to searching with its
	 * definition alone.
	 *
	 * <p>Takes effect for searches on this node at once and on every other
	 * node within its refresh interval. Removing what is not there changes
	 * nothing, so the call can be repeated.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @throws SearchSettingsException
	 *   if this node cannot keep settings or the storage could not be reached
	 */
	public void delete(String index) {
		if(!storage.isAvailable()) {
			throw SearchSettingsException.unavailable();
		}

		try {
			storage.delete(index);
		} catch(IOException e) {
			throw SearchSettingsException.ioError(e);
		}

		var entry = entryOf(index);
		entry.cached = new Cached(null);
		entry.lastReadNanos = System.nanoTime();
		entry.lastSeenHint = "";
		registryHints.reportSettings(index, null);
	}

	private Entry entryOf(String index) {
		var entry = entries.computeIfAbsent(index, key -> new Entry());
		entry.lastAccessNanos = System.nanoTime();

		return entry;
	}

	private boolean forcedReadAllowed(Entry entry) {
		var now = System.nanoTime();

		synchronized(entry.forcedReadLock) {
			if(entry.forcedReadEver
				&& now - entry.lastForcedReadNanos < refreshInterval.toNanos()) {
				return false;
			}

			entry.forcedReadEver = true;
			entry.lastForcedReadNanos = now;
			return true;
		}
	}

	/**
	 * Bring this node's copy of every index it was recently asked about up to
	 * date, and let go of the ones nobody asks about any more. Works from the
	 * registry as the node already holds it, so a pass costs no read of its
	 * own.
	 *
	 * <p>The registry says whether a copy is worth questioning. An index's own
	 * object is read only when its hint moved since the copy was last read,
	 * the registry says nothing about it, or the verify interval has passed -
	 * and never twice within the refresh interval. Skipping on an unmoved hint
	 * turns the steady state from one request per index per interval into
	 * none.
	 */
	void refresh() {
		var now = System.nanoTime();

		for(var pair : entries.entrySet()) {
			var entry = pair.getValue();

			if(now - entry.lastAccessNanos > IDLE_PERIOD.toNanos()) {
				entries.remove(pair.getKey(), entry);
				continue;
			}

			if(entry.cached != null && now - entry.lastReadNanos < minRead.toNanos()) {
				continue;
			}

			var hint = registry.get(pair.getKey())
				.map(registered -> registered.settingsVersion())
				.orElse(null);

			if(hint != null
				&& hint.equals(entry.lastSeenHint)
				&& now - entry.lastReadNanos < verifyInterval.toNanos()) {
				continue;
			}

			if(readInto(pair.getKey(), entry) && hint != null) {
				entry.lastSeenHint = hint;
			}
		}
	}

	/**
	 * Bring this node's copy of one index's settings up to date.
	 *
	 * @return
	 *   whether the storage could be read, {@code false} leaving the copy as
	 *   it was
	 */
	private boolean readInto(String index, Entry entry) {
		var current = entry.cached;
		var knownVersion = current == null || current.snapshot() == null
			? null
			: current.snapshot().version();

		try {
			switch(storage.read(index, knownVersion)) {
				case SearchSettingsStorage.Read.Unchanged unchanged -> {
					// The copy this node holds is the stored one
				}
				case SearchSettingsStorage.Read.Absent absent ->
					entry.cached = new Cached(null);
				case SearchSettingsStorage.Read.Loaded loaded -> {
					var snapshot = decode(loaded.settings(), loaded.version());

					if(snapshot.unsupportedFeatures().notEmpty()
						&& (current == null
							|| current.snapshot() == null
							|| !loaded.version().equals(current.snapshot().version()))) {
						logger.atError()
							.addKeyValue("index", index)
							.addKeyValue(
								"features",
								snapshot.unsupportedFeatures().makeString(", ")
							)
							.log(
								"Setting the search settings aside, they need features"
									+ " this node does not have; searching with the"
									+ " definition alone"
							);
					}

					entry.cached = new Cached(snapshot);
				}
			}

			entry.lastReadNanos = System.nanoTime();
			return true;
		} catch(IOException e) {
			logger.atWarn()
				.addKeyValue("index", index)
				.setCause(e)
				.log(
					"Could not read the search settings, using the copy this node"
						+ " holds; " + e.getMessage()
				);

			return false;
		}
	}

	/**
	 * Work out what a stored object means on this node: what it puts in force,
	 * or the names of what stops it.
	 */
	private static Snapshot decode(SearchSettingsStore stored, String version) {
		var unsupported = SearchSettingsFeatures.unsupportedIn(stored).toSortedList();

		return new Snapshot(
			stored,
			unsupported.isEmpty() && stored.hasRanking() ? stored.getRanking() : null,
			unsupported.isEmpty() ? stored.getSynonymsMap() : Map.of(),
			unsupported.isEmpty() ? stored.getTypoExclusionsMap() : Map.of(),
			unsupported.isEmpty() ? stored.getFieldsMap() : Map.of(),
			unsupported,
			version
		);
	}
}
