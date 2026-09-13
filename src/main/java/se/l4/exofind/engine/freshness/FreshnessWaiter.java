package se.l4.exofind.engine.freshness;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.registry.RegisteredIndex;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.logging.Log;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Brings this node to a {@link Freshness state} a read demands before the
 * read is answered, or says that it could not.
 *
 * <p>A node answers reads from what it holds, and what it holds is behind the
 * writer by a commit interval and a refresh interval. A read that hands back
 * the state a change landed in asks for that gap to be closed for it alone,
 * so the requests this costs are proportional to the reads that demand
 * freshness rather than to the number of indexes:
 *
 * <ul>
 *   <li>A generation the node does not answer from yet is looked for with one
 *     conditional read of the registry. A generation created before the one
 *     the node answers from was promoted over, so it is satisfied at once.
 *   <li>A settings version the node does not hold is looked for with one
 *     conditional read of the settings object. The storage holds the version
 *     any state carries, or a later one, so a read made after the request
 *     arrived satisfies whatever version the state carries.
 *   <li>A commit sequence the node has not reached is waited for: the writer
 *     is asked to commit what it holds, and a copy that does not write pulls,
 *     until the sequence is reached or {@code exofind.search.freshness.wait}
 *     runs out. Every wait on one generation shares one pull at a time.
 * </ul>
 *
 * <p>What was found satisfied is remembered per index, so a state that an
 * older answer named - a generation promoted over, a settings version replaced
 * - costs a request once rather than on every read that hands it back.
 *
 * <p>Safe for concurrent use.
 */
@Singleton
public class FreshnessWaiter {
	private static final Log logger = Log.of(FreshnessWaiter.class);

	/**
	 * How long a wait sleeps between its first two checks. Doubled after every
	 * check up to {@link #MAX_BACKOFF}, so a commit that lands at once is
	 * seen at once while a wait that goes on costs two requests a second.
	 */
	private static final Duration FIRST_BACKOFF = Duration.ofMillis(20);

	private static final Duration MAX_BACKOFF = Duration.ofMillis(500);

	/**
	 * How many generations and settings versions are remembered per index as
	 * satisfied. An index has few generations and its settings change rarely,
	 * so this holds every generation and version an answer in flight can carry.
	 */
	private static final int REMEMBERED = 16;

	/**
	 * How long the memory of an index is kept after the last read that named
	 * it, and how often the memories are swept for ones to let go of.
	 */
	private static final Duration IDLE_PERIOD = Duration.ofMinutes(10);

	private final Indexes indexes;
	private final SearchSettings searchSettings;

	/**
	 * How long a read may wait for a commit sequence.
	 */
	private final Duration wait;

	private final ConcurrentHashMap<String, Memory> memories = new ConcurrentHashMap<>();

	private final Object sweepLock = new Object();
	private long lastSweepNanos = System.nanoTime();

	/**
	 * Held while the registry is read for a read that demands a generation,
	 * so a run of such reads causes one read of the registry rather than one
	 * each.
	 */
	private final Object registryLock = new Object();
	private long registryReadNanos;
	private boolean registryReadEver;

	/**
	 * What this node found satisfied for one index, and the locks the reads
	 * that demand something of it share.
	 */
	private static final class Memory {
		volatile long lastAccessNanos;

		/**
		 * Generations the index was found not to answer from after a read of
		 * the registry, or created before the one it answers from: promoted
		 * over, or removed. Guarded by itself.
		 */
		final LinkedHashSet<String> settledGenerations = new LinkedHashSet<>();

		/**
		 * Settings versions the node has held, or read past. Guarded by
		 * itself.
		 */
		final LinkedHashSet<String> settledSettings = new LinkedHashSet<>();

		/**
		 * Held while the settings are read for a read that demands a version,
		 * so a run of such reads causes one read of the storage.
		 */
		final Object settingsLock = new Object();
		long settingsReadNanos;
		boolean settingsReadEver;

		/**
		 * Held around a pull made for a read that demands a commit sequence,
		 * so waits on one generation share one pull at a time.
		 */
		final Object pullLock = new Object();
	}

	/**
	 * @param wait
	 *   how long a read may wait for a commit sequence it demands. Zero
	 *   checks once and fails if the sequence is not there
	 */
	@Inject
	public FreshnessWaiter(
		Indexes indexes,
		SearchSettings searchSettings,
		@ConfigProperty(name = "exofind.search.freshness.wait", defaultValue = "10s")
		Duration wait
	) {
		this.indexes = indexes;
		this.searchSettings = searchSettings;
		this.wait = wait;
	}

	/**
	 * Get the state this node answers a name from right now, for an answer to
	 * carry so that a later read can demand at least this.
	 *
	 * @param index
	 *   the generation the answer came from
	 * @return
	 */
	public Freshness stateOf(Index index) {
		var name = IndexName.parse(index.getId());
		var settings = searchSettings.get(name.index())
			.map(SearchSettings.Snapshot::version)
			.orElse("");

		return new Freshness(name.index(), name.generation(), index.visibleCommit(), settings);
	}

	/**
	 * Bring this node to a state and get the generation a name answers from
	 * once it is there.
	 *
	 * <p>A name that carries a generation answers from that generation
	 * whatever generation the state carries, and a commit sequence is only
	 * waited for on the generation it belongs to.
	 *
	 * @param name
	 *   the index, or one generation of it by name
	 * @param freshness
	 *   the state to reach, or {@code null} to answer from what the node
	 *   holds
	 * @return
	 *   the generation the name answers from
	 * @throws IllegalArgumentException
	 *   if the state is of another index than the name
	 * @throws FreshnessUnavailableException
	 *   if the state was not reached within the wait
	 */
	public Index await(String name, Freshness freshness) {
		if(freshness == null) {
			return indexes.getOrThrow(name);
		}

		var startedAt = System.nanoTime();
		var requested = IndexName.parse(name);
		if(!requested.index().equals(freshness.index())) {
			throw new IllegalArgumentException(
				"The state is of `" + freshness.index() + "`, not of `" + requested.index() + "`"
			);
		}

		var memory = memoryOf(freshness.index());

		if(freshness.hasGeneration() && !requested.isPinned()) {
			awaitGeneration(requested, freshness.generation(), memory, startedAt);
		}

		if(freshness.hasSettingsVersion()) {
			awaitSettings(freshness.index(), freshness.settingsVersion(), memory, startedAt);
		}

		var index = indexes.getOrThrow(name);
		if(freshness.hasCommit()
			&& freshness.generation().equals(IndexName.parse(index.getId()).generation())) {
			index = awaitCommit(name, index, freshness.commit(), memory, startedAt);
		}

		return index;
	}

	/**
	 * See to it that the registry this node holds says which generation the
	 * index answers from, as of after the read arrived, unless it already
	 * answers from the one demanded or from one created after it.
	 */
	private void awaitGeneration(IndexName requested, String generation, Memory memory, long startedAt) {
		var registered = indexes.getRegistered(requested.index()).orElse(null);
		if(registered != null) {
			if(generation.equals(registered.live())) {
				return;
			}

			synchronized(memory.settledGenerations) {
				if(memory.settledGenerations.contains(generation)) {
					return;
				}
			}

			if(createdBefore(registered, generation, registered.live())) {
				remember(memory.settledGenerations, generation);
				return;
			}
		}

		var read = refreshRegistry(startedAt);

		/*
		 * Read since the request arrived, so what the registry says now is
		 * the state demanded or a later one. A generation it still does not
		 * answer from was promoted over or removed, and is not asked about
		 * again.
		 */
		registered = indexes.getRegistered(requested.index()).orElse(null);
		if(registered != null && !generation.equals(registered.live())) {
			if(!read) {
				throw new FreshnessUnavailableException(requested.toString(), Duration.ZERO);
			}

			remember(memory.settledGenerations, generation);
		}
	}

	/**
	 * Whether one generation of an index was created before another, as the
	 * registry records it. Generations named by hand are not ordered by their
	 * names, so what was created first is the only order there is.
	 */
	private static boolean createdBefore(RegisteredIndex index, String generation, String other) {
		if(other == null) {
			return false;
		}

		var first = index.generation(generation).orElse(null);
		var second = index.generation(other).orElse(null);

		return first != null
			&& second != null
			&& first.createdAt() != null
			&& second.createdAt() != null
			&& first.createdAt().isBefore(second.createdAt());
	}

	/**
	 * Read the registry, unless it was read since the request arrived.
	 *
	 * @return
	 *   whether the registry this node holds was read since the request
	 *   arrived
	 */
	private boolean refreshRegistry(long startedAt) {
		synchronized(registryLock) {
			if(registryReadEver && registryReadNanos - startedAt > 0) {
				return true;
			}

			var read = indexes.refreshRegistry();
			if(read) {
				registryReadNanos = System.nanoTime();
				registryReadEver = true;
			}

			return read;
		}
	}

	/**
	 * See to it that this node has held a settings version, or read the
	 * storage since the read arrived.
	 */
	private void awaitSettings(String index, String version, Memory memory, long startedAt) {
		var current = currentSettingsVersion(index);
		if(version.equals(current)) {
			return;
		}

		synchronized(memory.settledSettings) {
			if(memory.settledSettings.contains(version)) {
				return;
			}
		}

		synchronized(memory.settingsLock) {
			if(!(memory.settingsReadEver && memory.settingsReadNanos - startedAt > 0)) {
				searchSettings.read(index);
				memory.settingsReadNanos = System.nanoTime();
				memory.settingsReadEver = true;
			}
		}

		/*
		 * Read since the request arrived, so the copy holds the version
		 * demanded or a later one. Both are remembered: the one held, so a
		 * read naming it after the next change costs nothing, and the one
		 * demanded, which nothing will hold again.
		 */
		remember(memory.settledSettings, currentSettingsVersion(index));
		remember(memory.settledSettings, version);
	}

	/**
	 * The settings version this node holds for an index, the empty string
	 * when it holds none.
	 */
	private String currentSettingsVersion(String index) {
		return searchSettings.get(index)
			.map(SearchSettings.Snapshot::version)
			.orElse("");
	}

	/**
	 * Wait until the searches of a generation answer from a commit sequence.
	 *
	 * @return
	 *   the generation, which may be another instance than the one given when
	 *   the node reopened it while the wait ran
	 */
	private Index awaitCommit(String name, Index index, long commit, Memory memory, long startedAt) {
		var deadline = startedAt + wait.toNanos();
		var backoff = FIRST_BACKOFF;

		while(true) {
			if(index.visibleCommit() >= commit) {
				return index;
			}

			/*
			 * The writer holds what the sequence covers and only has to commit
			 * it. A copy that does not write, and a writer that has to be
			 * pulled over, ask the remote. A pull made by another wait on the
			 * same generation serves this one as well, so the check is made
			 * again once it is done.
			 */
			index.commitSoon();
			synchronized(memory.pullLock) {
				if(index.visibleCommit() < commit) {
					index.pull();
				}
			}

			if(index.visibleCommit() >= commit) {
				return index;
			}

			var remaining = deadline - System.nanoTime();
			if(remaining <= 0) {
				logger.atDebug()
					.addKeyValue("index", index.getId())
					.addKeyValue("commit", commit)
					.addKeyValue("visible", index.visibleCommit())
					.log("A read waited for a commit sequence the node did not reach");

				throw new FreshnessUnavailableException(name, wait);
			}

			var sleep = Math.min(backoff.toNanos(), remaining);
			try {
				Thread.sleep(Duration.ofNanos(sleep));
			} catch(InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new FreshnessUnavailableException(name, wait);
			}

			backoff = backoff.multipliedBy(2);
			if(backoff.compareTo(MAX_BACKOFF) > 0) {
				backoff = MAX_BACKOFF;
			}

			// The node may have closed and reopened the generation meanwhile
			index = indexes.getOrThrow(name);
		}
	}

	private static void remember(LinkedHashSet<String> set, String value) {
		synchronized(set) {
			set.add(value);

			while(set.size() > REMEMBERED) {
				var oldest = set.iterator();
				oldest.next();
				oldest.remove();
			}
		}
	}

	private Memory memoryOf(String index) {
		var memory = memories.computeIfAbsent(index, key -> new Memory());
		var now = System.nanoTime();
		memory.lastAccessNanos = now;

		sweep(now);

		return memory;
	}

	/**
	 * Let go of the memories of indexes nothing has asked about for a while,
	 * so a deployment that creates and removes indexes does not grow this
	 * without bound.
	 */
	private void sweep(long now) {
		synchronized(sweepLock) {
			if(now - lastSweepNanos < IDLE_PERIOD.toNanos()) {
				return;
			}

			lastSweepNanos = now;
		}

		memories.entrySet().removeIf(
			entry -> now - entry.getValue().lastAccessNanos > IDLE_PERIOD.toNanos()
		);
	}
}
