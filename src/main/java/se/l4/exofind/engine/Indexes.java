package se.l4.exofind.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.CommitPolicy;
import se.l4.exofind.engine.index.DocumentCache;
import se.l4.exofind.engine.index.FacetCacheStats;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.SearchThreads;
import se.l4.exofind.engine.index.IndexNotFoundException;
import se.l4.exofind.engine.index.IndexState;
import se.l4.exofind.engine.index.IndexStorageHeldException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.RegisteredIndex;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.registry.RegistryPoller;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.state.IndexRemovals;
import se.l4.exofind.engine.index.state.IndexUsageFile;
import se.l4.exofind.engine.index.state.NoopIndexRemovals;
import se.l4.exofind.engine.index.state.LocalCopy;
import se.l4.exofind.engine.index.state.StateSyncProvider;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.metrics.RequestMetrics;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Indexes manages the access to instances of {@link Index}.
 *
 * <p>An {@link Index} is one generation of an index rather than the index
 * itself. The {@link IndexRegistry} says which indexes exist, which generations
 * each of them has and which generation its name answers for; everything here
 * is keyed by the full name of a generation, which is also the name its
 * directory takes - so what lies on disk says which generation it belongs to
 * without anything else being read.
 */
@ApplicationScoped
public class Indexes implements RegistryPoller.Listener {
	private static final Log logger = Log.of(Indexes.class);

	private static final ErrorType GENERATION_NOT_CREATABLE =
		ErrorType.withCode("index:generation:not_creatable")
			.withArguments("name")
			.withMessage(
				"`{{name}}` names a generation, and an index is created by its own name."
					+ " Create the index, then add generations to it"
			);

	private static final ErrorType GENERATION_NAME_REQUIRED =
		ErrorType.withCode("index:generation:name_required")
			.withArguments("name")
			.withMessage("`{{name}}` names an index rather than one generation of it");

	/**
	 * How long to wait for a retiring instance to finish closing before its
	 * directory is used again. A close that takes longer is abandoned - the
	 * lock inside the Lucene directory is what then still keeps a new writer
	 * out until the old one is really gone.
	 */
	private static final Duration RETIRING_WAIT = Duration.ofSeconds(30);

	/**
	 * Write load below which an index counts as idle.
	 *
	 * <p>The leadership table stores write load in coarse buckets. Each
	 * bucket covers a doubling of the load, and the lowest one covers
	 * everything below a single changed document. Using the same value here
	 * means an index is idle to this node when it is idle to the nodes that
	 * divide the indexes between them.
	 */
	private static final double IDLE_WRITE_LOAD = 1;

	/**
	 * The {@link #preloadIdleLimit} used by the constructors that do not take
	 * one. Kept the same as the default of
	 * {@code exofind.indexes.preload.idle-limit}.
	 */
	private static final int DEFAULT_PRELOAD_IDLE_LIMIT = 16;

	/**
	 * The {@link #preloadMaxIndexes} used by the constructors that do not take
	 * one: none. A test works over a directory it has just filled, where every
	 * copy would be opened again, so a test turns the startup preload on when
	 * it means to test it.
	 */
	private static final int DEFAULT_PRELOAD_MAX_INDEXES = 0;

	private final NodeState nodeState;
	private final StateSyncProvider syncProvider;
	private final IndexRegistry registry;
	private final RegistryHints registryHints;

	/**
	 * What a delete leaves in the shared storage and what a creation clears
	 * out of it, see {@link IndexRemovals}.
	 */
	private final IndexRemovals removals;

	/**
	 * Handed to every index opened here, so that commits, pushes and pulls are
	 * reported wherever they happen.
	 */
	private final RequestMetrics metrics;

	private final Path indexRoot;

	/**
	 * The open generations, by their full name.
	 */
	private final LoadingCache<String, Index> indexes;

	/**
	 * Lock held while the set of indexes is changed, so that two callers do
	 * not create or delete the same index at the same time.
	 */
	private final ReentrantLock lifecycleLock;

	/**
	 * One lock per index name, held while the directory of the index is being
	 * loaded into and while the disk sweep removes it. The cache serializes
	 * loads of the same name but says nothing while a load is in flight, so
	 * this is what keeps the sweep from pulling a directory out from under a
	 * load. The sweep only ever tries the lock: finding it held means the
	 * index is wanted, which is reason enough to keep its files.
	 */
	private final ConcurrentHashMap<String, ReentrantLock> nameLocks;

	/**
	 * Runs the refresh passes {@link RegistryPoller} hands over, the disk
	 * sweep and the reopening an ownership change asks for. One thread, so
	 * none of them ever runs next to another. Owned here so that shutting this
	 * down also stops the node from synchronizing.
	 */
	private final ScheduledExecutorService refreshExecutor;

	/**
	 * Runs the pulls of a refresh next to each other, so that one index that
	 * is slow to pull does not hold up the others.
	 */
	private final ExecutorService pullExecutor;

	/**
	 * The threads a search on this node spreads over, handed to every index
	 * opened here.
	 */
	private final SearchThreads searchThreads;

	/**
	 * Closes retired instances, so that flushing and closing an evicted index
	 * never runs on the thread whose access caused the eviction.
	 */
	private final ScheduledExecutorService closeExecutor;

	/**
	 * How long a retired instance stays open before it is closed. A caller
	 * that was handed the instance just before it was evicted gets to finish
	 * with it instead of finding it closed mid-request.
	 */
	private final Duration closeGracePeriod;

	/**
	 * Instances that have been evicted but not closed yet, by name. Anything
	 * that wants the directory of one of these - loading the index again,
	 * deleting it - closes the retired instance first through
	 * {@link #drainRetiring(String, boolean)}.
	 */
	private final ConcurrentHashMap<String, RetiringIndex> retiring;

	/**
	 * Shortest time between two manifest requests for the same open
	 * generation. A pass that arrives sooner leaves the generation alone
	 * however far its version hint has moved, so the interval bounds what a
	 * continuously written index costs a node that reads it.
	 */
	private final Duration refreshInterval;

	/**
	 * How long an open generation may go without its manifest being asked for
	 * from the storage, when the registry's version hint says the copy is
	 * current. A hint makes skipping the request safe enough; this bounds the
	 * staleness when a hint is stale or was lost.
	 */
	private final Duration verifyInterval;

	/**
	 * {@link #refreshInterval} held under {@link #verifyInterval}, so a
	 * refresh interval configured above the verify interval cannot suppress
	 * the check the verify interval promises.
	 */
	private final Duration minManifestCheck;

	/**
	 * When each open generation last had its manifest asked for, as
	 * {@link System#nanoTime()}. Both {@link #minManifestCheck} and
	 * {@link #verifyInterval} are measured against it. Entries of generations
	 * no longer open are dropped at the end of a refresh pass.
	 */
	private final ConcurrentHashMap<String, Long> lastManifestChecks;

	/**
	 * Whether the local copies have been checked against the registry since
	 * this node started. Until they have, a registry that has not changed is
	 * still reason to look for copies of indexes it does not hold.
	 */
	private boolean sweptUnregistered;

	/**
	 * When the refresh loop last finished a pass, as {@link System#nanoTime()}.
	 * Set before the first pass is scheduled, so that the time since a refresh
	 * is measured from the node starting rather than from zero.
	 */
	private volatile long lastRefreshNanos;

	/**
	 * Whether a pass is running right now, which is what keeps a long pull from
	 * looking like a loop that has stopped.
	 */
	private volatile boolean refreshing;

	/**
	 * Reacts to this node gaining or losing an index by reopening its open
	 * generations in the right mode. Kept so it can be unregistered.
	 */
	private final NodeState.Listener nodeStateListener;

	/**
	 * How many generations may be open at the same time. The open cache
	 * evicts against this number. Empty means there is no limit.
	 *
	 * <p>Read again before this node opens an index it was given to write. A
	 * node that is already at the limit opens nothing, so opening an index
	 * early never closes one that is answering requests.
	 */
	private final OptionalInt maxOpen;

	/**
	 * How many indexes this node may hold before it stops opening idle ones
	 * early.
	 *
	 * <p>A node below the limit opens every index it is given to write,
	 * because it has room for all of them. A node above the limit opens only
	 * the indexes that were being written when they changed hands. Holding
	 * hundreds of indexes that nobody writes then costs no writers and no
	 * merge threads. Zero means that only an index that was being written is
	 * opened.
	 */
	private final int preloadIdleLimit;

	/**
	 * How many of the local copies this node opens again when it starts.
	 * Zero means it opens none of them.
	 *
	 * <p>The copies are the indexes this node served before it restarted, so
	 * opening them takes the work out of the first request for each of them.
	 * Held down as well by {@link #maxOpen}, which no preload ever pushes
	 * past.
	 */
	private final int preloadMaxIndexes;

	/**
	 * How long the startup preload may go on opening copies. Opens that have
	 * started finish, and what is left is opened by the requests that ask for
	 * it. This bounds how long a node with slow storage spends opening copies
	 * nothing has asked for.
	 */
	private final Duration preloadMaxDuration;

	/**
	 * How long this node waits for the startup preload before it reports
	 * itself ready. Measured from when the node started, so it bounds the
	 * delay a restart adds however far the preload has come. Zero reports the
	 * node ready without waiting.
	 */
	private final Duration preloadReadinessWait;

	/**
	 * When this node started, as {@link System#nanoTime()}. The
	 * {@link #preloadReadinessWait} is measured from here.
	 */
	private final long startedNanos;

	/**
	 * Whether the startup preload has been started. Read and written on the
	 * refresh thread alone, like {@link #sweptUnregistered}.
	 */
	private boolean preloadStarted;

	/**
	 * Whether the startup preload has stopped opening copies, however many it
	 * opened. Stays {@code false} on a node that never read its registry, and
	 * {@link #preloadReadinessWait} then ends the wait.
	 */
	private volatile boolean preloadFinished;

	/**
	 * How many bytes the local copies may take together before the disk sweep
	 * starts removing the coldest ones. Empty means the disk is not bounded
	 * and no sweep runs.
	 */
	private final OptionalLong diskMaxSize;

	/**
	 * How recently a copy has to have been used to be safe from the disk
	 * sweep regardless of the budget, so that a burst of evictions can never
	 * take out what was just in use.
	 */
	private final Duration diskMinIdle;

	/**
	 * Half-life of the open count in the usage records: how long it takes an
	 * unused index to count for half of what it does now when the sweep ranks
	 * the copies.
	 */
	private final Duration diskHalfLife;

	/**
	 * When an index this node opens commits without being asked to. Only the
	 * indexer ever has anything to commit, so this says nothing on a node that
	 * only searches.
	 */
	private final CommitPolicy commitPolicy;

	/**
	 * Segment size in bytes under which Lucene merges the segments of a
	 * written index toward that size, ahead of its usual tiers. Empty leaves
	 * Lucene's default floor.
	 */
	private final OptionalLong mergeFloorSegment;

	/**
	 * Cache the stored fields of documents are read through, one for the node
	 * so that every index draws on the same budget - see {@link DocumentCache}
	 * for the argument. Disabled unless {@code indexes.document-cache.max-size}
	 * says what it may hold.
	 */
	private final DocumentCache documentCache;

	/**
	 * Open indexes that report nothing, for a test that is not measuring.
	 */
	public Indexes(
		NodeState nodeState,
		StateSyncProvider syncProvider,
		IndexRegistry registry,
		RegistryHints registryHints,
		Path storageDirectory,
		OptionalInt maxOpen,
		Duration refreshInterval,
		Duration verifyInterval,
		int refreshConcurrency,
		Duration closeGracePeriod,
		int commitMaxChanges,
		Duration commitMaxInterval,
		Optional<String> diskMaxSize,
		Optional<String> documentCacheMaxSize,
		Duration diskMinIdle,
		Duration diskHalfLife,
		Duration diskSweepInterval
	) throws IOException {
		this(
			nodeState,
			syncProvider,
			registry,
			registryHints,
			new NoopIndexRemovals(),
			storageDirectory,
			maxOpen,
			refreshInterval,
			verifyInterval,
			refreshConcurrency,
			closeGracePeriod,
			commitMaxChanges,
			commitMaxInterval,
			diskMaxSize,
			documentCacheMaxSize,
			diskMinIdle,
			diskHalfLife,
			diskSweepInterval
		);
	}

	/**
	 * Open indexes that report nothing, over a storage a delete leaves marks
	 * in, for a test of what a delete and a creation do to the storage.
	 */
	public Indexes(
		NodeState nodeState,
		StateSyncProvider syncProvider,
		IndexRegistry registry,
		RegistryHints registryHints,
		IndexRemovals removals,
		Path storageDirectory,
		OptionalInt maxOpen,
		Duration refreshInterval,
		Duration verifyInterval,
		int refreshConcurrency,
		Duration closeGracePeriod,
		int commitMaxChanges,
		Duration commitMaxInterval,
		Optional<String> diskMaxSize,
		Optional<String> documentCacheMaxSize,
		Duration diskMinIdle,
		Duration diskHalfLife,
		Duration diskSweepInterval
	) throws IOException {
		this(
			nodeState,
			syncProvider,
			registry,
			registryHints,
			removals,
			RequestMetrics.none(),
			storageDirectory,
			maxOpen,
			refreshInterval,
			verifyInterval,
			refreshConcurrency,
			closeGracePeriod,
			commitMaxChanges,
			commitMaxInterval,
			diskMaxSize,
			documentCacheMaxSize,
			diskMinIdle,
			diskHalfLife,
			diskSweepInterval,
			Optional.empty(),
			DEFAULT_PRELOAD_IDLE_LIMIT,
			DEFAULT_PRELOAD_MAX_INDEXES,
			Duration.ZERO,
			Duration.ZERO,
			SearchThreads.inline()
		);
	}

	@Inject
	public Indexes(
		NodeState nodeState,
		StateSyncProvider syncProvider,
		IndexRegistry registry,
		RegistryHints registryHints,
		IndexRemovals removals,
		RequestMetrics metrics,
		@ConfigProperty(name = "exofind.storage.local.directory") Path storageDirectory,
		@ConfigProperty(name = "exofind.indexes.max-open") OptionalInt maxOpen,
		@ConfigProperty(name = "exofind.indexes.refresh-interval", defaultValue = "30s") Duration refreshInterval,
		@ConfigProperty(name = "exofind.indexes.verify-interval", defaultValue = "10m") Duration verifyInterval,
		@ConfigProperty(name = "exofind.indexes.refresh-concurrency", defaultValue = "4") int refreshConcurrency,
		@ConfigProperty(name = "exofind.indexes.close-grace-period", defaultValue = "10s") Duration closeGracePeriod,
		@ConfigProperty(name = "exofind.indexes.commit.max-changes", defaultValue = "10000") int commitMaxChanges,
		@ConfigProperty(name = "exofind.indexes.commit.max-interval", defaultValue = "5s") Duration commitMaxInterval,
		@ConfigProperty(name = "exofind.indexes.disk.max-size") Optional<String> diskMaxSize,
		@ConfigProperty(name = "exofind.indexes.document-cache.max-size") Optional<String> documentCacheMaxSize,
		@ConfigProperty(name = "exofind.indexes.disk.min-idle", defaultValue = "24h") Duration diskMinIdle,
		@ConfigProperty(name = "exofind.indexes.disk.half-life", defaultValue = "168h") Duration diskHalfLife,
		@ConfigProperty(name = "exofind.indexes.disk.sweep-interval", defaultValue = "1h") Duration diskSweepInterval,
		@ConfigProperty(name = "exofind.indexes.merge.floor-segment") Optional<String> mergeFloorSegment,
		@ConfigProperty(name = "exofind.indexes.preload.idle-limit", defaultValue = "16") int preloadIdleLimit,
		@ConfigProperty(name = "exofind.indexes.preload.max-indexes", defaultValue = "32") int preloadMaxIndexes,
		@ConfigProperty(name = "exofind.indexes.preload.max-duration", defaultValue = "5m") Duration preloadMaxDuration,
		@ConfigProperty(name = "exofind.indexes.preload.readiness-wait", defaultValue = "30s") Duration preloadReadinessWait,
		SearchThreads searchThreads
	) throws IOException {
		this.nodeState = nodeState;
		this.syncProvider = syncProvider;
		this.searchThreads = searchThreads;
		this.registry = registry;
		this.registryHints = registryHints;
		this.removals = removals;
		this.metrics = metrics;
		this.verifyInterval = verifyInterval;
		this.lastManifestChecks = new ConcurrentHashMap<>();
		this.lifecycleLock = new ReentrantLock();
		this.nameLocks = new ConcurrentHashMap<>();
		this.closeGracePeriod = closeGracePeriod;
		this.retiring = new ConcurrentHashMap<>();
		this.maxOpen = maxOpen;
		this.preloadIdleLimit = preloadIdleLimit;
		this.preloadMaxIndexes = preloadMaxIndexes;
		this.preloadMaxDuration = preloadMaxDuration;
		this.preloadReadinessWait = preloadReadinessWait;
		this.startedNanos = System.nanoTime();
		this.diskMaxSize = diskMaxSize.isPresent()
			? OptionalLong.of(parseSize(diskMaxSize.get()))
			: OptionalLong.empty();
		this.diskMinIdle = diskMinIdle;
		this.diskHalfLife = diskHalfLife;
		this.commitPolicy = new CommitPolicy(commitMaxChanges, commitMaxInterval);
		this.mergeFloorSegment = mergeFloorSegment.isPresent()
			? OptionalLong.of(parseSize(mergeFloorSegment.get()))
			: OptionalLong.empty();

		if(this.mergeFloorSegment.orElse(1) == 0) {
			throw new IllegalArgumentException(
				"exofind.indexes.merge.floor-segment can not be zero, leave it unset for Lucene's default"
			);
		}
		this.documentCache = documentCacheMaxSize.isPresent()
			? DocumentCache.sized(parseSize(documentCacheMaxSize.get()))
			: DocumentCache.disabled();

		if(documentCacheMaxSize.isPresent()) {
			logger.atInfo()
				.addKeyValue("maxSize", documentCacheMaxSize.get())
				.log("Caching the documents of search results in memory");
		}

		/*
		 * Which indexes exist is read from the registry rather than from what
		 * lies here, so a directory is only ever a local copy of something the
		 * deployment already holds.
		 */
		this.indexRoot = storageDirectory.resolve("indexes");
		Files.createDirectories(indexRoot);

		/*
		 * Eviction only retires an instance. The closing - which may flush
		 * unpushed changes over the network - happens later on the close
		 * executor, never inside the cache operation that evicted it.
		 */
		var builder = Caffeine.newBuilder()
			.<String, Index>evictionListener((key, value, cause) -> retire(key, value));

		if(maxOpen.isPresent()) {
			builder.maximumSize(maxOpen.getAsInt());
		}

		this.indexes = builder.build(this::loadIndex);
		this.closeExecutor = Executors.newSingleThreadScheduledExecutor();
		this.pullExecutor = Executors.newFixedThreadPool(refreshConcurrency);

		this.refreshInterval = refreshInterval;
		this.minManifestCheck = refreshInterval.compareTo(verifyInterval) < 0
			? refreshInterval
			: verifyInterval;
		this.lastRefreshNanos = System.nanoTime();
		this.refreshExecutor = Executors.newSingleThreadScheduledExecutor();

		/*
		 * The sweep shares the refresh thread, so it can never run next to a
		 * refresh that is opening and pulling indexes.
		 */
		if(this.diskMaxSize.isPresent()) {
			this.refreshExecutor.scheduleWithFixedDelay(
				this::sweepDisk,
				diskSweepInterval.toMillis(),
				diskSweepInterval.toMillis(),
				TimeUnit.MILLISECONDS
			);
		}

		/*
		 * Gaining or losing an index changes the mode its open generations
		 * need. Gaining one also opens the index. Both run on the refresh
		 * thread, because the notification arrives on the thread that
		 * coordinates ownership, and that thread must not do Lucene work.
		 */
		this.nodeStateListener = new NodeState.Listener() {
			@Override
			public void onOwnershipChanged(NodeState state, String index) {
				refreshExecutor.execute(() -> {
					reopenOwned(index, true);
					openGainedIndex(index);
				});
			}

			@Override
			public void onOwnershipRevoked(NodeState state, String index) {
				refreshExecutor.execute(() -> reopenOwned(index, false));
			}
		};
		nodeState.addListener(nodeStateListener);
	}

	/**
	 * Reopen open indexes to match whether this node may write them, used
	 * when the node gains or loses an index.
	 *
	 * @param name
	 *   name of the index whose ownership changed, reopening its open
	 *   generations, or {@code null} to reopen everything
	 * @param flush
	 *   whether a generation being handed over may still push what it holds,
	 *   {@code false} when another node may already write the index
	 */
	private void reopenOwned(String name, boolean flush) {
		for(var entry : indexes.asMap().entrySet()) {
			if(name != null && !IndexName.parse(entry.getKey()).index().equals(name)) {
				continue;
			}

			try {
				entry.getValue().reopen(flush);
			} catch(RuntimeException e) {
				logger.atWarn()
					.addKeyValue("index", entry.getValue().getId())
					.setCause(e)
					.log("Could not reopen index; " + e.getMessage());
			}
		}
	}

	/**
	 * Open the generation an index answers from, after this node was given
	 * the index to write.
	 *
	 * <p>The copy and the {@code IndexWriter} are made ready here instead of
	 * on the request thread of the first write. Without this, that write
	 * waits for a full copy after a restart, a failover or a rebalance, and
	 * every other write for the index waits behind it.
	 *
	 * <p>A node writes every index it is given, so no index is opened here
	 * without a use for it. An open index costs a writer and its merge
	 * threads, so two limits apply: {@link #maxOpen}, so this never closes a
	 * generation that is answering requests, and {@link #preloadIdleLimit},
	 * above which only the indexes that were being written are opened.
	 *
	 * <p>The generations a reindex job uses are not opened here. The same
	 * change of ownership starts the job again, and the job opens its own
	 * source and target on its own thread pool.
	 *
	 * @param index
	 *   name of the index, without a generation, or {@code null} when this
	 *   node was given every index at once. A node that holds every index is
	 *   the only node in the deployment, and its copies are the deployment,
	 *   so there is nothing to fetch before a write.
	 */
	private void openGainedIndex(String index) {
		if(index == null || !nodeState.isIndexer(index)) {
			return;
		}

		if(maxOpen.isPresent() && indexes.asMap().size() >= maxOpen.getAsInt()) {
			return;
		}

		if(
			nodeState.ownedCount() > preloadIdleLimit
				&& nodeState.writeLoad(index) < IDLE_WRITE_LOAD
		) {
			return;
		}

		String name;
		try {
			name = registry.resolve(IndexName.parse(index)).toString();
		} catch(RuntimeException e) {
			/*
			 * A create takes the index before it writes the registry, so the
			 * registry does not name it yet. The create opens the generation
			 * itself. A name that stays unknown is answered when a request
			 * asks for it.
			 */
			logger.atDebug()
				.addKeyValue("index", index)
				.setCause(e)
				.log("Nothing to open for the index this node was given; " + e.getMessage());

			return;
		}

		if(indexes.getIfPresent(name) != null) {
			return;
		}

		try {
			/*
			 * On the pull executor, not the refresh thread: a full copy can
			 * take minutes, and the refresh thread also runs the flush that a
			 * handover waits for.
			 */
			pullExecutor.execute(() -> {
				try {
					indexes.get(name);
				} catch(RuntimeException e) {
					logger.atWarn()
						.addKeyValue("index", name)
						.setCause(e)
						.log(
							"Could not open the index this node was given to write,"
								+ " the first write for it opens it instead; "
								+ e.getMessage()
						);
				}
			});
		} catch(RejectedExecutionException e) {
			// Shutting down, and nothing is written from here anymore
		}
	}

	/**
	 * Bring the open generations of an index into the mode this node holds
	 * it in, here and now. The reopening an ownership change queues runs on
	 * the refresh thread a moment later; a request that was admitted by
	 * claiming the index calls this first, so the writer it needs exists by
	 * the time it is served. A generation already open in the right mode is
	 * left as it is.
	 *
	 * @param index
	 *   name of the index, without a generation
	 */
	public void reopenForWriting(String index) {
		reopenOwned(index, false);
	}

	/**
	 * Push everything this node still holds for an index, for a handover
	 * this node chose. The open generations of the index are committed,
	 * pushed and reopened read-only, and evicted instances that are still
	 * closing are waited out - so when the returned future completes,
	 * nothing held here can reach the remote anymore. The claim on the index
	 * is released only then, which is what keeps the successor from pulling
	 * a manifest the flush had not written yet.
	 *
	 * <p>Meaningful only after the ownership change that took the index away
	 * from this node has reached {@link NodeState} - reopening reads it, and
	 * flushes nothing for an index the node still writes.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @return
	 *   completes when nothing more can be pushed from here; a flush that
	 *   failed completes too, having given the changes up
	 */
	public CompletableFuture<Void> flushForHandover(String index) {
		var flushed = new CompletableFuture<Void>();

		try {
			refreshExecutor.execute(() -> {
				try {
					reopenOwned(index, true);

					// An evicted generation that is still closing may push while it does
					for(var name : retiring.keySet()) {
						var parsed = IndexName.tryParse(name).orElse(null);
						if(parsed != null && parsed.index().equals(index)) {
							drainRetiring(name, true);
						}
					}

					flushed.complete(null);
				} catch(Throwable t) {
					flushed.completeExceptionally(t);
				}
			});
		} catch(RejectedExecutionException e) {
			// Shutting down; the close path flushes everything that is still open
			flushed.complete(null);
		}

		return flushed;
	}

	/**
	 * Take an evicted instance out of use. Closing happens on the close
	 * executor once the grace period has passed, so that a caller handed the
	 * instance just before the eviction gets to finish with it. Anything that
	 * needs the directory sooner goes through
	 * {@link #drainRetiring(String, boolean)}, which skips the rest of the
	 * grace.
	 */
	private void retire(String name, Index index) {
		var retired = new RetiringIndex(name, index);

		if(retiring.putIfAbsent(name, retired) != null) {
			/*
			 * An earlier instance of the same name is still closing, which can
			 * only happen when waiting for it was given up. This one is closed
			 * untracked - the lock inside the Lucene directory is what keeps
			 * the instances from writing next to each other.
			 */
			logger.atWarn()
				.addKeyValue("index", name)
				.log("An earlier instance of this index is still closing");

			closeExecutor.execute(() -> retired.closeNow(true));
			return;
		}

		closeExecutor.schedule(
			() -> retired.closeNow(true),
			closeGracePeriod.toMillis(),
			TimeUnit.MILLISECONDS
		);
	}

	/**
	 * Close the retiring instance of an index right away, when there is one,
	 * and wait for it to be gone. Called before the directory of the index is
	 * used again, so that two instances never hold it at once.
	 *
	 * @param commit
	 *   whether the instance may still flush what it holds, {@code false} when
	 *   the index is being removed and its changes are of no interest
	 */
	private void drainRetiring(String name, boolean commit) {
		var retired = retiring.get(name);
		if(retired == null) {
			return;
		}

		retired.closeNow(commit);
		if(!retired.await(RETIRING_WAIT)) {
			logger.atWarn()
				.addKeyValue("index", name)
				.log("Retired instance of this index did not finish closing, continuing without it");
		}
	}

	/**
	 * An instance that has been evicted but not closed yet. Whoever calls
	 * {@link #closeNow(boolean)} first does the closing - the scheduled
	 * grace-period close and a caller draining the name race for it through
	 * the flag.
	 */
	private class RetiringIndex {
		private final String name;
		private final Index index;
		private final AtomicBoolean closing;
		private final CompletableFuture<Void> done;

		RetiringIndex(String name, Index index) {
			this.name = name;
			this.index = index;
			this.closing = new AtomicBoolean();
			this.done = new CompletableFuture<>();
		}

		void closeNow(boolean commit) {
			if(!closing.compareAndSet(false, true)) {
				return;
			}

			try {
				try {
					index.close(commit);
				} catch(IOException | RuntimeException e) {
					logger.atWarn()
						.addKeyValue("index", name)
						.setCause(e)
						.log("Could not flush index while closing, giving up its local changes; " + e.getMessage());

					try {
						index.close(false);
					} catch(IOException | RuntimeException e2) {
						logger.atWarn()
							.addKeyValue("index", name)
							.setCause(e2)
							.log("Could not close index; " + e2.getMessage());
					}
				}
			} finally {
				recordUsed(name);
				retiring.remove(name, this);
				done.complete(null);
			}
		}

		boolean await(Duration timeout) {
			try {
				done.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
				return true;
			} catch(InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			} catch(ExecutionException | TimeoutException e) {
				return false;
			}
		}
	}

	@PreDestroy
	public void close() {
		/*
		 * Closing an index the node has changed pushes it, so shutting down is
		 * not always quick. Said before the work starts, so that the wait has
		 * something behind it in the log rather than looking like a node that
		 * stopped answering.
		 */
		logger.atInfo()
			.addKeyValue("open", indexes.asMap().size())
			.log("Closing the open indexes");

		nodeState.removeListener(nodeStateListener);
		refreshExecutor.shutdownNow();
		pullExecutor.shutdownNow();

		/*
		 * Shut down before the retired instances are closed by hand, so that a
		 * grace period that has not run out yet can not schedule a second
		 * attempt. A close that is already running finishes on its own.
		 */
		closeExecutor.shutdown();

		for(var retired : retiring.values()) {
			retired.closeNow(true);
			retired.await(RETIRING_WAIT);
		}

		/*
		 * Indexes are closed here rather than left to the cache, which only
		 * reports the entries it evicts of its own accord. One that will not
		 * close is reported and stepped over, as the rest still have changes
		 * of their own to commit and push before this node goes.
		 */
		for(var index : indexes.asMap().values()) {
			try {
				index.close();
			} catch(IOException | RuntimeException e) {
				logger.atWarn()
					.addKeyValue("index", index.getId())
					.setCause(e)
					.log("Could not close index; " + e.getMessage());
			}

			recordUsed(index.getId());
		}

		indexes.invalidateAll();
	}

	@Override
	public Optional<Duration> pollInterval() {
		return Optional.of(refreshInterval);
	}

	@Override
	public Executor executor() {
		return refreshExecutor;
	}

	@Override
	public void onRegistryPolled(boolean changed) {
		refresh(changed);
	}

	/**
	 * Read the registry and bring this node in step with what it says, for a
	 * caller that wants the node current here and now.
	 */
	void refresh() {
		var before = registry.version();
		var read = registry.refresh();

		refresh(read && !Objects.equals(before, registry.version()));
	}

	/**
	 * Bring this node in step with the remote, learning what the deployment
	 * holds and pulling the generations it holds open.
	 *
	 * A node that does not index has no other way of finding out that an index
	 * has changed, that a generation was promoted, or that either was removed;
	 * one that does needs this to pick up an index whose pull failed earlier.
	 *
	 * @param registryChanged
	 *   whether the last read moved this node's copy of the registry. Copies
	 *   of indexes the registry no longer holds are looked for only then, and
	 *   once when the node starts.
	 */
	void refresh(boolean registryChanged) {
		refreshing = true;
		try {
			if(registryChanged || !sweptUnregistered) {
				sweptUnregistered = true;
				removeUnregisteredCopies();
			}

			/*
			 * After the first read, so the registry can say which copies are
			 * still named, and after the sweep, so nothing opens a copy the
			 * registry no longer holds.
			 */
			if(!preloadStarted && registry.hasBeenRead()) {
				preloadStarted = true;
				preload();
			}

			/*
			 * Pulls run next to each other so that one slow index can not
			 * stretch the staleness of every other. The refresh still waits
			 * for all of them, keeping the interval one between full passes.
			 */
			var pulls = new ArrayList<CompletableFuture<Void>>();
			for(var entry : indexes.asMap().entrySet()) {
				if(!needsManifestCheck(entry.getKey(), entry.getValue())) {
					continue;
				}

				lastManifestChecks.put(entry.getKey(), System.nanoTime());

				var index = entry.getValue();
				pulls.add(CompletableFuture.runAsync(() -> {
					try {
						index.pull();
					} catch(RuntimeException e) {
						logger.atWarn()
							.addKeyValue("index", index.getId())
							.setCause(e)
							.log("Could not pull index; " + e.getMessage());
					}
				}, pullExecutor));
			}

			CompletableFuture.allOf(pulls.toArray(CompletableFuture[]::new)).join();

			lastManifestChecks.keySet().retainAll(indexes.asMap().keySet());
		} catch(RuntimeException e) {
			/*
			 * Letting this out would cancel the schedule, leaving the node on
			 * whatever it happens to hold until it is restarted.
			 */
			logger.atWarn()
				.setCause(e)
				.log("Could not refresh indexes; " + e.getMessage());
		} finally {
			/*
			 * A pass that failed is still a pass. What the time since one says
			 * is whether the loop is running, and a remote that will not answer
			 * is not something restarting the node would mend.
			 */
			lastRefreshNanos = System.nanoTime();
			refreshing = false;
		}
	}

	/**
	 * Whether a refresh pass has reason to ask the storage about an open
	 * generation.
	 *
	 * <p>Two intervals bound the answer. Nothing is asked within
	 * {@link #minManifestCheck} of the last request, so passes arriving faster
	 * than the refresh interval cost a busy index nothing. Past that, the
	 * registry's version hint decides: the storage is asked when the index is
	 * not in step with the remote, when the registry says nothing about the
	 * generation's manifest, or when {@link #verifyInterval} has passed. A
	 * hint only ever makes skipping a request safe, never wrong.
	 *
	 * <p>Skipping turns the steady state of many open indexes from one storage
	 * request each per pass into none. The registry, read once per pass for
	 * all of them, already said nothing changed.
	 */
	private boolean needsManifestCheck(String name, Index index) {
		var lastCheck = lastManifestChecks.get(name);
		if(lastCheck != null
			&& System.nanoTime() - lastCheck < minManifestCheck.toNanos()) {
			return false;
		}

		if(index.getState() != IndexState.USABLE) {
			return true;
		}

		var parsed = IndexName.parse(name);
		var hint = registry.get(parsed.index())
			.map(registered -> registered.manifestVersion(parsed.generation()))
			.orElse(OptionalLong.empty());
		if(hint.isEmpty()) {
			return true;
		}

		/*
		 * A copy that has never synchronized stands at nothing, which is what
		 * a hint of zero - a generation the remote holds no manifest for -
		 * matches, so an empty generation is not asked for over and over.
		 */
		if(hint.getAsLong() > index.getSyncedManifestVersion().orElse(0)) {
			/*
			 * A writer reported a version this copy is not at. A failed pull
			 * lands here again on the next pass, as the copy stays behind the
			 * hint until one succeeds.
			 */
			return true;
		}

		return lastCheck == null
			|| System.nanoTime() - lastCheck >= verifyInterval.toNanos();
	}

	/**
	 * Open the local copies this node held before it started, the ones it
	 * used most first.
	 *
	 * <p>A node that keeps its directory across a restart - a persistent
	 * volume, or a process restarted in place - comes back holding copies of
	 * the indexes it was serving. Opening one costs a manifest request and a
	 * Lucene reader, and without this every one of those is paid by the first
	 * request that asks for the index. A node holding hundreds of them meets
	 * every upgrade with hundreds of stalled requests.
	 *
	 * <p>What is opened is bounded three ways: {@link #preloadMaxIndexes},
	 * {@link #maxOpen}, so this never closes what it opens, and
	 * {@link #preloadMaxDuration}, after which the copies that are left are
	 * opened by the requests that ask for them. The opens run on the pull
	 * executor, so the refresh thread is free while they do, and readiness
	 * waits for them through {@link #hasSettledPreload()}.
	 *
	 * <p>Ranking uses the usage records the disk sweep keeps, so a node opens
	 * the indexes it was asked for most often. An index this node holds no
	 * copy of is left alone: fetching one is the work a request does, and
	 * doing it here would pull the whole deployment onto every node.
	 */
	private void preload() {
		var wanted = preloadMaxIndexes;
		if(maxOpen.isPresent()) {
			wanted = Math.min(wanted, maxOpen.getAsInt() - indexes.asMap().size());
		}

		if(wanted <= 0) {
			preloadFinished = true;
			return;
		}

		var names = preloadCandidates(wanted);
		if(names.isEmpty()) {
			preloadFinished = true;
			return;
		}

		logger.atInfo()
			.addKeyValue("indexes", names.size())
			.log("Opening the indexes this node held before it started");

		var opened = new AtomicInteger();
		var deadline = System.nanoTime() + preloadMaxDuration.toNanos();
		var opens = new ArrayList<CompletableFuture<Void>>();

		try {
			for(var name : names) {
				opens.add(CompletableFuture.runAsync(
					() -> openPreloaded(name, deadline, opened),
					pullExecutor
				));
			}
		} catch(RejectedExecutionException e) {
			// Shutting down, so there is nothing left to open early for
		}

		CompletableFuture.allOf(opens.toArray(CompletableFuture[]::new))
			.whenComplete((result, e) -> {
				logger.atInfo()
					.addKeyValue("opened", opened.get())
					.addKeyValue("indexes", names.size())
					.log("Opened the indexes this node held before it started");

				preloadFinished = true;
			});
	}

	/**
	 * The copies the startup preload opens, the ones this node used most
	 * first and the ones it used last first among equals. Only the generation
	 * an index answers for is named: an older generation answers a request
	 * that asks for it by name, and a reindex opens what it needs itself.
	 *
	 * @param limit
	 *   how many names to return at most
	 */
	private List<String> preloadCandidates(int limit) {
		record Candidate(String name, double score, long lastUsedMs) {
		}

		List<Path> copies;
		try(var paths = Files.list(indexRoot)) {
			copies = paths.filter(Files::isDirectory).toList();
		} catch(IOException e) {
			logger.atWarn()
				.setCause(e)
				.log("Could not look at the local copies to open them; " + e.getMessage());

			return List.of();
		}

		var now = Instant.now();
		var candidates = new ArrayList<Candidate>();

		for(var path : copies) {
			var directory = path.getFileName().toString();
			var name = IndexName.tryParse(directory).orElse(null);
			if(name == null || !name.isPinned() || indexes.getIfPresent(directory) != null) {
				continue;
			}

			var registered = registry.get(name.index()).orElse(null);
			if(registered == null
				|| !registered.isSupported()
				|| !registered.liveGeneration().orElse("").equals(name.generation())) {
				continue;
			}

			var usage = IndexUsageFile.read(path);
			candidates.add(new Candidate(
				directory,
				IndexUsageFile.decayedOpens(usage, now, diskHalfLife),
				usage.getLastUsedMs()
			));
		}

		candidates.sort(
			Comparator.comparingDouble(Candidate::score)
				.thenComparingLong(Candidate::lastUsedMs)
				.reversed()
		);

		var names = new ArrayList<String>();
		for(var candidate : candidates) {
			if(names.size() >= limit) {
				break;
			}

			names.add(candidate.name());
		}

		return names;
	}

	/**
	 * Open one copy for the startup preload, unless the preload has run out
	 * of time or the node has filled up while this one waited its turn.
	 *
	 * @param name
	 *   full name of the generation
	 * @param deadline
	 *   {@link System#nanoTime()} after which nothing more is opened
	 * @param opened
	 *   counts what was opened, for the line saying how far the preload got
	 */
	private void openPreloaded(String name, long deadline, AtomicInteger opened) {
		if(System.nanoTime() - deadline >= 0) {
			return;
		}

		if(maxOpen.isPresent() && indexes.asMap().size() >= maxOpen.getAsInt()) {
			return;
		}

		try {
			/*
			 * Loaded without recording an open. A preload that counted itself
			 * would rank what it opened above what was asked for, and go on
			 * preloading the same copies whether or not anyone searched them.
			 */
			indexes.get(name, key -> loadIndex(key, false));
			opened.incrementAndGet();
		} catch(RuntimeException e) {
			/*
			 * The request that asks for the index opens it again and answers
			 * with what went wrong. This only says why the preload did not
			 * get there first.
			 */
			logger.atDebug()
				.addKeyValue("index", name)
				.setCause(e)
				.log("Could not open a held index before it was asked for; " + e.getMessage());
		}
	}

	/**
	 * Remove the local copies of generations the registry no longer holds,
	 * which is how a node finds out that an index or one of its generations was
	 * removed somewhere else. Removing a copy for any other reason - to make
	 * room - is what {@link #sweepDisk()} does.
	 *
	 * <p>The registry is one object read at one version, so a name being absent
	 * from it is an answer rather than a gap: nothing has to repeat before a
	 * copy is removed. Only a registry that has actually been read counts,
	 * which is what keeps a node that has never reached the storage from
	 * sweeping away everything it holds.
	 */
	private void removeUnregisteredCopies() {
		if(!registry.hasBeenRead()) {
			return;
		}

		List<Path> copies;
		try(var paths = Files.list(indexRoot)) {
			copies = paths.filter(Files::isDirectory).toList();
		} catch(IOException e) {
			logger.atWarn()
				.setCause(e)
				.log("Could not look at the local copies; " + e.getMessage());

			return;
		}

		for(var path : copies) {
			var directory = path.getFileName().toString();
			var name = IndexName.tryParse(directory).orElse(null);

			if(name == null || !name.isPinned()) {
				/*
				 * Not something this node could have created - a directory left
				 * by hand, or by a version that named them differently. Left
				 * alone rather than removed, as nothing here knows what it is.
				 */
				continue;
			}

			var index = registry.get(name.index()).orElse(null);
			if(index != null && index.hasGeneration(name.generation())) {
				continue;
			}

			removeVanished(directory);
		}
	}

	/**
	 * Remove the local copy of a generation the registry no longer holds. Local
	 * changes that were never pushed are not removed with it - a node that
	 * still holds something keeps its copy and says so instead.
	 */
	private void removeVanished(String name) {
		lifecycleLock.lock();
		try {
			var index = indexes.getIfPresent(name);
			if(index != null) {
				var state = index.getState();
				if(state == IndexState.MODIFIED || state == IndexState.PUSHING) {
					logger.atWarn()
						.addKeyValue("index", name)
						.log("Index was removed in the remote but holds changes that were never pushed, keeping it");
					return;
				}
			}

			logger.atInfo()
				.addKeyValue("index", name)
				.log("Index was removed in the remote, removing the local copy");

			if(index != null) {
				try {
					// The remote copy is gone, there is nothing to push to
					index.close(false);
				} catch(IOException e) {
					logger.atWarn()
						.addKeyValue("index", name)
						.setCause(e)
						.log("Could not close index; " + e.getMessage());
				}

				indexes.invalidate(name);
			}

			// A retired instance would write into the files being removed
			drainRetiring(name, false);

			try {
				deleteRecursively(indexRoot.resolve(name));
			} catch(IOException e) {
				logger.atWarn()
					.addKeyValue("index", name)
					.setCause(e)
					.log("Could not remove the local files of the index; " + e.getMessage());
			}
		} finally {
			lifecycleLock.unlock();
		}
	}

	/**
	 * Bound what the local copies take on disk, by removing the directories
	 * of the coldest indexes among those the remote fully holds. The remote
	 * keeps everything, so a removed copy costs a full pull if the index is
	 * asked for again - nothing else changes, the index stays known and
	 * usable.
	 *
	 * <p>Copies are ranked by their decayed open count, oldest use first
	 * among equals, and removed until the total is a tenth under the budget -
	 * going a bit further than the budget keeps the sweep from removing a
	 * copy on every run once the disk sits at the edge. A copy used more
	 * recently than the idle floor is never removed, however cold its count.
	 */
	void sweepDisk() {
		if(diskMaxSize.isEmpty()) {
			return;
		}

		record Copy(String name, Path path, long size) {
		}

		record Candidate(Copy copy, double score, long lastUsedMs) {
		}

		try {
			var budget = diskMaxSize.getAsLong();
			var now = Instant.now();

			var copies = new ArrayList<Copy>();
			var total = 0L;

			try(var paths = Files.list(indexRoot)) {
				for(var path : paths.filter(Files::isDirectory).toList()) {
					var size = sizeOf(path);
					total += size;
					copies.add(new Copy(path.getFileName().toString(), path, size));
				}
			}

			if(total <= budget) {
				return;
			}

			var candidates = new ArrayList<Candidate>();
			for(var copy : copies) {
				if(indexes.getIfPresent(copy.name()) != null || retiring.containsKey(copy.name())) {
					continue;
				}

				var usage = IndexUsageFile.read(copy.path());
				if(!usage.hasLastUsedMs()) {
					/*
					 * A directory from before usage was recorded. Stamped now,
					 * so it gets a full idle period before it can be removed -
					 * an upgrade must never take out copies wholesale for not
					 * carrying records nothing could have written yet.
					 */
					seedUsage(copy.name(), copy.path(), now);
					continue;
				}

				if(now.toEpochMilli() - usage.getLastUsedMs() < diskMinIdle.toMillis()) {
					continue;
				}

				candidates.add(new Candidate(
					copy,
					IndexUsageFile.decayedOpens(usage, now, diskHalfLife),
					usage.getLastUsedMs()
				));
			}

			candidates.sort(
				Comparator.comparingDouble(Candidate::score)
					.thenComparingLong(Candidate::lastUsedMs)
			);

			var target = budget - budget / 10;
			for(var candidate : candidates) {
				if(total <= target) {
					break;
				}

				var copy = candidate.copy();
				if(evictLocalCopy(copy.name(), copy.path())) {
					total -= copy.size();

					logger.atInfo()
						.addKeyValue("index", copy.name())
						.addKeyValue("freedBytes", copy.size())
						.addKeyValue(
							"idle",
							Duration.between(Instant.ofEpochMilli(candidate.lastUsedMs()), now)
						)
						.log("Removed the local copy of a cold index to free disk space");
				}
			}

			if(total > budget) {
				logger.atWarn()
					.addKeyValue("totalBytes", total)
					.addKeyValue("budgetBytes", budget)
					.log("Local copies exceed the disk budget and nothing more can be removed");
			}
		} catch(IOException | RuntimeException e) {
			// Letting this out would cancel the schedule, like a refresh would
			logger.atWarn()
				.setCause(e)
				.log("Could not sweep the local copies; " + e.getMessage());
		}
	}

	/**
	 * Give a directory without a usage record one saying it was last used
	 * now, which starts its idle period.
	 */
	private void seedUsage(String name, Path path, Instant now) {
		try {
			IndexUsageFile.write(
				path,
				IndexUsageFile.recordUse(IndexUsageFile.read(path), now)
			);
		} catch(IOException e) {
			logger.atDebug()
				.addKeyValue("index", name)
				.setCause(e)
				.log("Could not record the index being used");
		}
	}

	/**
	 * Remove the directory of an index that is not open, when the remote
	 * holds everything it does. The name stays known - only the local files
	 * go, and opening the index again pulls them back whole.
	 *
	 * @return
	 *   whether the directory was removed
	 */
	private boolean evictLocalCopy(String name, Path path) {
		lifecycleLock.lock();
		try {
			if(indexes.getIfPresent(name) != null || retiring.containsKey(name)) {
				return false;
			}

			var lock = nameLock(name);
			if(!lock.tryLock()) {
				// Held by a load, and an index being loaded is not cold
				return false;
			}

			try {
				if(LocalCopy.hasUnpushedChanges(path)) {
					logger.atWarn()
						.addKeyValue("index", name)
						.log("Index holds changes the remote never got, keeping its local copy");
					return false;
				}

				/*
				 * The manifest goes first: it claims the files next to it are
				 * present, so they must never be removed while it stays. An
				 * interrupted removal then reads as a directory that was never
				 * synchronized, which is pulled whole when the index is opened.
				 */
				Files.deleteIfExists(path.resolve(LocalCopy.MANIFEST_FILE));
				deleteRecursively(path);
				return true;
			} catch(IOException e) {
				logger.atWarn()
					.addKeyValue("index", name)
					.setCause(e)
					.log("Could not remove the local files of the index; " + e.getMessage());
				return false;
			} finally {
				lock.unlock();
			}
		} finally {
			lifecycleLock.unlock();
		}
	}

	private static long sizeOf(Path directory) {
		try(var files = Files.walk(directory)) {
			return files
				.filter(Files::isRegularFile)
				.mapToLong(f -> {
					try {
						return Files.size(f);
					} catch(IOException e) {
						return 0;
					}
				})
				.sum();
		} catch(IOException e) {
			return 0;
		}
	}

	/**
	 * Parse a size such as {@code "10G"} into bytes. The suffixes {@code K},
	 * {@code M}, {@code G} and {@code T} are binary multiples and a bare
	 * number is bytes.
	 *
	 * @throws IllegalArgumentException
	 *   if the value is not a size
	 */
	static long parseSize(String value) {
		var number = value.trim();
		var multiplier = 1L;

		if(!number.isEmpty()) {
			multiplier = switch(Character.toUpperCase(number.charAt(number.length() - 1))) {
				case 'K' -> 1L << 10;
				case 'M' -> 1L << 20;
				case 'G' -> 1L << 30;
				case 'T' -> 1L << 40;
				default -> 1L;
			};

			if(multiplier != 1) {
				number = number.substring(0, number.length() - 1).trim();
			}
		}

		try {
			var parsed = Long.parseLong(number);
			if(parsed < 0) {
				throw new IllegalArgumentException("A size can not be negative: " + value);
			}

			return Math.multiplyExact(parsed, multiplier);
		} catch(NumberFormatException | ArithmeticException e) {
			throw new IllegalArgumentException(
				"Not a size: " + value + ", expected bytes with an optional K, M, G or T suffix"
			);
		}
	}

	/**
	 * Open a generation for the cache, counting an open against the index.
	 * Every request for an index that is not open goes through here, so the
	 * count says how often the index was asked for.
	 */
	private Index loadIndex(String index) {
		return loadIndex(index, true);
	}

	/**
	 * Open a generation for the cache.
	 *
	 * @param index
	 *   full name of the generation
	 * @param recordOpen
	 *   whether to count this as the index having been wanted. False for the
	 *   startup preload, which decides what to open from the counts and would
	 *   otherwise decide from what it opened last time
	 */
	private Index loadIndex(String index, boolean recordOpen) {
		// Held until the pull is done, keeping the disk sweep off the directory
		var lock = nameLock(index);
		lock.lock();
		try {
			/*
			 * A retired instance may still hold the directory this one is about
			 * to open, so it is closed first rather than waiting out its grace.
			 */
			drainRetiring(index, true);

			var dataPath = indexRoot.resolve(index);

			try {
				Files.createDirectories(dataPath);
			} catch(IOException e) {
				throw new UncheckedIOException(e);
			}

			if(recordOpen) {
				recordOpened(index, dataPath);
			}

			var parsed = IndexName.parse(index);
			var loaded = new Index(
				nodeState,
				index,
				dataPath,
				syncProvider.createSync(parsed, dataPath),
				commitPolicy,
				documentCache,
				metrics,
				mergeFloorSegment,
				searchThreads
			);

			/*
			 * Every push reports its version, so the other nodes learn from
			 * their next read of the registry that this generation moved -
			 * or that it did not, which is what lets them skip asking for
			 * its manifest.
			 */
			loaded.onPushed(version -> registryHints.reportManifest(
				parsed.index(),
				parsed.generation(),
				version
			));

			/*
			 * A freshly opened index starts out in NEEDS_PULL and can neither be
			 * read nor modified until it has been pulled.
			 */
			loaded.pull();
			lastManifestChecks.put(index, System.nanoTime());
			return loaded;
		} finally {
			lock.unlock();
		}
	}

	private ReentrantLock nameLock(String name) {
		return nameLocks.computeIfAbsent(name, n -> new ReentrantLock());
	}

	/**
	 * Record in the directory of an index that it was opened, growing its
	 * decayed open count. The record is advisory - an open that could not be
	 * recorded still goes ahead.
	 */
	private void recordOpened(String name, Path dataPath) {
		try {
			IndexUsageFile.write(
				dataPath,
				IndexUsageFile.recordOpen(IndexUsageFile.read(dataPath), Instant.now(), diskHalfLife)
			);
		} catch(IOException e) {
			logger.atDebug()
				.addKeyValue("index", name)
				.setCause(e)
				.log("Could not record the index being opened");
		}
	}

	/**
	 * Record in the directory of an index that it was in use until now,
	 * without counting an open. Recorded when an instance closes, so the
	 * usage record covers the whole span the index was open.
	 */
	private void recordUsed(String name) {
		var dataPath = indexRoot.resolve(name);
		if(!Files.isDirectory(dataPath)) {
			return;
		}

		try {
			IndexUsageFile.write(
				dataPath,
				IndexUsageFile.recordUse(IndexUsageFile.read(dataPath), Instant.now())
			);
		} catch(IOException e) {
			logger.atDebug()
				.addKeyValue("index", name)
				.setCause(e)
				.log("Could not record the index being used");
		}
	}

	/**
	 * Get the generation a name answers from.
	 *
	 * @param name
	 *   the index, which answers from whichever generation is live, or one
	 *   generation of it by name
	 * @return
	 *   empty when the deployment holds no such index or generation
	 */
	public Optional<Index> get(String name) {
		try {
			return Optional.of(getOrThrow(name));
		} catch(IndexNotFoundException e) {
			return Optional.empty();
		}
	}

	/**
	 * Get the generation a name answers from, failing if there is none.
	 *
	 * @param name
	 * @return
	 * @throws IndexNotFoundException
	 *   if the deployment holds no such index or generation
	 */
	public Index getOrThrow(String name) {
		return indexes.get(registry.resolve(IndexName.parse(name)).toString());
	}

	/**
	 * Get the names of the indexes the deployment holds. These are the names
	 * callers use and grants are written against; the generations under them
	 * are named by {@link #getRegistered(String)}.
	 *
	 * @return
	 */
	public ImmutableSet<String> getIndexNames() {
		return registry.names();
	}

	/**
	 * Get whether this node has read the registry since it started.
	 *
	 * <p>Until it has, this node knows nothing of what the deployment holds:
	 * {@link #getIndexNames()} is empty whatever lies in its directory, and a
	 * name it is asked for has to be looked up before it can be answered at
	 * all. It stays {@code true} once a read has succeeded - a later one that
	 * fails leaves the node on the copy it has rather than on nothing.
	 *
	 * @return
	 */
	public boolean hasReadRegistry() {
		return registry.hasBeenRead();
	}

	/**
	 * Get whether this node has waited long enough for the copies it held
	 * before it started to be opened again.
	 *
	 * <p>A node that has just started holds copies of the indexes it served,
	 * and opens the most used of them before anything asks for one. A search
	 * arriving before that is done waits for its index to be opened, so a
	 * node keeps traffic away until this answers {@code true}.
	 *
	 * <p>The wait is capped, so this turns true whether or not the preload
	 * finished. What is left then opens in the background while the node
	 * serves. A cap of zero waits for nothing.
	 *
	 * @return
	 */
	public boolean hasSettledPreload() {
		if(preloadFinished || preloadReadinessWait.isZero() || preloadReadinessWait.isNegative()) {
			return true;
		}

		return System.nanoTime() - startedNanos >= preloadReadinessWait.toNanos();
	}

	/**
	 * Get how long it has been since the refresh loop finished a pass, which
	 * is at most {@link #getRefreshInterval()} on a node that is keeping up.
	 *
	 * <p>A pass that is running counts as none of it: pulling a large index
	 * takes as long as it takes, and a node fetching what it was asked for has
	 * not fallen behind. A pass that failed still counts as one, so this grows
	 * without bound only while the loop is not running at all - which is a
	 * state the node does not leave on its own.
	 *
	 * @return
	 */
	public Duration getTimeSinceRefresh() {
		if(refreshing) {
			return Duration.ZERO;
		}

		return Duration.ofNanos(System.nanoTime() - lastRefreshNanos);
	}

	/**
	 * Get the shortest time this node leaves between two storage requests for
	 * the same open generation, as {@code EXOFIND_INDEXES_REFRESH_INTERVAL}
	 * names it. Passes arrive at least this often.
	 *
	 * @return
	 */
	public Duration getRefreshInterval() {
		return refreshInterval;
	}

	/**
	 * Get the generations open on this node, by the name each was opened
	 * under.
	 *
	 * <p>A snapshot: an entry may be closed by the time it is read, and one
	 * evicted while a caller holds the map is absent from the next call.
	 * Opens nothing.
	 *
	 * @return
	 */
	public Map<String, Index> getOpen() {
		return Map.copyOf(indexes.asMap());
	}

	/**
	 * Get what each index directory takes on disk, by index name.
	 *
	 * <p>Covers every directory under the index root, including those of
	 * generations that are not open. Walks the directories, so it costs a
	 * {@code stat} per file.
	 *
	 * @return
	 * @throws IOException
	 *   if the index root can not be listed
	 */
	public Map<String, Long> getLocalCopySizes() throws IOException {
		var sizes = new HashMap<String, Long>();
		try(var paths = Files.list(indexRoot)) {
			for(var path : paths.filter(Files::isDirectory).toList()) {
				sizes.put(path.getFileName().toString(), sizeOf(path));
			}
		}

		return sizes;
	}

	/**
	 * Get how much the local copies may take on disk, as
	 * {@code EXOFIND_INDEXES_DISK_MAX_SIZE} names it.
	 *
	 * @return
	 *   empty when the copies are not bounded
	 */
	public OptionalLong getDiskMaxSize() {
		return diskMaxSize;
	}

	/**
	 * Get how the document cache has answered so far.
	 *
	 * @return
	 */
	public CacheStats getDocumentCacheStats() {
		return documentCache.stats();
	}

	/**
	 * Get how the facet caches have answered so far. The caches are shared
	 * by every index of the node, so this is one answer for all of them.
	 *
	 * @return
	 */
	public FacetCacheStats getFacetCacheStats() {
		return FacetCacheStats.current();
	}

	/**
	 * Get every index the deployment holds, with the generations under each.
	 *
	 * @return
	 */
	public ListIterable<RegisteredIndex> getRegistered() {
		return registry.list();
	}

	/**
	 * Get one index as the registry holds it, without opening anything.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @return
	 *   empty when the deployment holds no index by that name
	 */
	public Optional<RegisteredIndex> getRegistered(String index) {
		return registry.get(index);
	}

	/**
	 * Create an index with a first generation, making it available for indexing
	 * and searching.
	 *
	 * @param name
	 *   name of the index, which is what callers use from here on
	 * @param def
	 * @return
	 *   the generation that was created
	 * @throws ValidationException
	 *   if the name cannot be used, or the deployment already holds an index by
	 *   that name
	 */
	public Index create(String name, IndexDef def) {
		var index = IndexName.parse(name);
		if(index.isPinned()) {
			/*
			 * Creating an index is naming the index, not a generation of it -
			 * which generation comes first is the engine's to decide.
			 */
			throw new ValidationException(
				GENERATION_NOT_CREATABLE.toMessage(ObjectLocation.root(), "name", name)
			);
		}

		lifecycleLock.lock();
		try {
			var generation = IndexRegistry.nextGeneration(null);

			/*
			 * Registered before anything is written, so that two nodes creating
			 * the same name race for one conditional write and exactly one of
			 * them wins. What that leaves behind if the definition is then
			 * refused is an index with no definition yet, which the same request
			 * sent again fills in.
			 */
			registry.create(index.index(), generation);

			try {
				/*
				 * Cleared after winning the registration, so that only the
				 * winner touches the storage. A name deleted earlier lands on
				 * the prefix the delete marked, and the first generation is
				 * opened by pulling - so what the delete left has to go
				 * before the open, or the new index starts out holding the
				 * old documents.
				 */
				prepareStorage(index.withGeneration(generation), true);

				return openWithDefinition(index.withGeneration(generation), def);
			} catch(RuntimeException e) {
				registry.remove(index.index());
				throw e;
			}
		} finally {
			lifecycleLock.unlock();
		}
	}

	/**
	 * Clear what a delete left under the prefix a generation is about to be
	 * created on, see {@link IndexRemovals}.
	 *
	 * @param generation
	 * @param wholeIndex
	 *   whether the index itself is being created, in which case a mark over
	 *   the index clears everything under it, settings and all
	 * @throws IndexStorageHeldException
	 *   if the prefix holds a manifest nothing said was deleted
	 * @throws UncheckedIOException
	 *   if the storage could not be asked or cleared
	 */
	private void prepareStorage(IndexName generation, boolean wholeIndex) {
		try {
			if(wholeIndex) {
				removals.prepareForIndex(generation.index());
			}

			removals.prepareForGeneration(generation);
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Add a generation to an index, to be filled with documents and promoted
	 * once it holds them. The index goes on answering from the generation it
	 * already had.
	 *
	 * @param name
	 *   name of the generation, as {@code index@generation}
	 * @param def
	 *   definition for the new generation, which is what makes this the way to
	 *   roll out a definition the documents already indexed were not indexed
	 *   under
	 * @return
	 *   the generation that was created
	 * @throws IndexNotFoundException
	 *   if the deployment holds no such index
	 * @throws ValidationException
	 *   if the name cannot be used, or the index already has a generation by
	 *   that name
	 */
	public Index createGeneration(String name, IndexDef def) {
		var generation = IndexName.parse(name);
		if(!generation.isPinned()) {
			throw new ValidationException(
				GENERATION_NAME_REQUIRED.toMessage(ObjectLocation.root(), "name", name)
			);
		}

		lifecycleLock.lock();
		try {
			registry.addGeneration(generation.index(), generation.generation());

			try {
				// Only the generation's own prefix; the index around it is in use
				prepareStorage(generation, false);

				return openWithDefinition(generation, def);
			} catch(RuntimeException e) {
				registry.removeGeneration(generation.index(), generation.generation());
				throw e;
			}
		} finally {
			lifecycleLock.unlock();
		}
	}

	/**
	 * Open a generation and give it its definition, taking the local copy away
	 * again if the definition is refused.
	 */
	private Index openWithDefinition(IndexName generation, IndexDef def) {
		var name = generation.toString();
		var index = indexes.get(name);

		try {
			index.updateDefinition(def);
			return index;
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		} catch(RuntimeException e) {
			try {
				index.close(false);
				indexes.invalidate(name);
				deleteRecursively(indexRoot.resolve(name));
			} catch(IOException e2) {
				e.addSuppressed(e2);
			}

			throw e;
		}
	}

	/**
	 * Make an index answer from one of its generations. Every caller using the
	 * index by name reads the promoted generation from here on, on this node at
	 * once and on every other within its refresh interval.
	 *
	 * @param name
	 *   name of the generation, as {@code index@generation}
	 * @return
	 *   the index as it is now registered
	 * @throws IndexNotFoundException
	 *   if the deployment holds no such index or generation
	 */
	public RegisteredIndex promote(String name) {
		var generation = IndexName.parse(name);
		if(!generation.isPinned()) {
			throw new ValidationException(
				GENERATION_NAME_REQUIRED.toMessage(ObjectLocation.root(), "name", name)
			);
		}

		return registry.promote(generation.index(), generation.generation());
	}

	/**
	 * Delete an index and every generation of it, or one generation on its own.
	 *
	 * <p>The index is taken out of the registry, which is what makes it gone for
	 * the deployment rather than only for this node; every node removes its own
	 * copy when it next reads the registry. What the remote holds under it -
	 * the generations and the search settings object alike - is marked as
	 * deleted and removed by a sweep after a grace period, see
	 * {@link IndexRemovals}. An index created again under the same name
	 * before then starts empty all the same: the creation clears the marked
	 * prefix first.
	 *
	 * <p>The mark is written after the registry, so that every mark stands
	 * for a delete that went through. A mark that could not be written is
	 * logged and leaves the objects where they are; the registry audit
	 * reports them as held but not registered.
	 *
	 * @param name
	 *   the index, or one generation of it as {@code index@generation}
	 * @throws IndexNotFoundException
	 *   if the deployment holds no such index or generation
	 * @throws ValidationException
	 *   if the generation named is the one its index answers from
	 * @throws IOException
	 */
	public void delete(String name) throws IOException {
		var parsed = IndexName.parse(name);

		lifecycleLock.lock();
		try {
			if(parsed.isPinned()) {
				registry.removeGeneration(parsed.index(), parsed.generation());
				removeLocalCopy(parsed.toString());
				markRemoved(parsed);
				return;
			}

			var index = registry.get(parsed.index())
				.orElseThrow(() -> new IndexNotFoundException(name));

			registry.remove(parsed.index());

			for(var generation : index.generations()) {
				removeLocalCopy(parsed.withGeneration(generation.name()).toString());
			}

			markRemoved(parsed);
		} finally {
			lifecycleLock.unlock();
		}
	}

	/**
	 * Leave the mark a sweep removes the objects of a deleted index or
	 * generation by. Failing to leave it is not failing the delete - the
	 * registry no longer names what was deleted, which is what the caller
	 * asked for.
	 */
	private void markRemoved(IndexName target) {
		try {
			removals.mark(target);
		} catch(IOException e) {
			logger.atWarn()
				.addKeyValue("index", target.toString())
				.setCause(e)
				.log(
					"Could not mark the index as removed in the storage, its objects stay"
						+ " until it is deleted again; " + e.getMessage()
				);
		}
	}

	/**
	 * Take the local copy of a generation out of use and remove its files.
	 * Changes that were never pushed go with it - the generation is no longer
	 * registered, so there is nothing left to push them to.
	 */
	private void removeLocalCopy(String name) throws IOException {
		var index = indexes.getIfPresent(name);
		if(index != null) {
			index.close(false);
			indexes.invalidate(name);
		}

		// A retired instance would write into the files being removed
		drainRetiring(name, false);

		deleteRecursively(indexRoot.resolve(name));
	}

	private static void deleteRecursively(Path path) throws IOException {
		if(!Files.exists(path)) {
			return;
		}

		try(var paths = Files.walk(path)) {
			for(var p : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}
}
