package se.l4.exofind.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.lucene.index.SegmentInfos;
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
import se.l4.exofind.engine.index.FacetWarmer;
import se.l4.exofind.engine.index.SearchCaches;
import se.l4.exofind.engine.index.SearchThreads;
import se.l4.exofind.engine.index.IndexNotFoundException;
import se.l4.exofind.engine.index.IndexState;
import se.l4.exofind.engine.index.IndexStorageHeldException;
import se.l4.exofind.engine.index.IndexUnsupportedException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.RegisteredIndex;
import se.l4.exofind.engine.index.registry.RegistryException;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.registry.RegistryPoller;
import se.l4.exofind.engine.index.registry.RegistryRemovalUnknownException;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.state.IndexRemovals;
import se.l4.exofind.engine.index.state.IndexUsageFile;
import se.l4.exofind.engine.index.state.NoopIndexRemovals;
import se.l4.exofind.engine.index.state.PendingRemovalsFile;
import se.l4.exofind.engine.index.state.LocalCopy;
import se.l4.exofind.engine.index.state.StateSyncProvider;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.metrics.Meters;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.storage.LocalModeFiles;
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
			.withStatus(400)
			.withArguments("name")
			.withMessage(
				"`{{name}}` names a generation, and an index is created by its own name."
					+ " Create the index, then add generations to it"
			);

	private static final ErrorType GENERATION_NAME_REQUIRED =
		ErrorType.withCode("index:generation:name_required")
			.withStatus(400)
			.withArguments("name")
			.withMessage("`{{name}}` names an index rather than one generation of it");

	private static final ErrorType LIVE_GENERATION_UNSETTLED =
		ErrorType.withCode("index:generation:unsettled")
			.withStatus(400)
			.withArguments("name")
			.withMessage(
				"The generation `{{name}}` answers from changed while the change"
					+ " was being made, repeatedly - send it again"
			);

	/**
	 * How many times {@link #write(String, WriteAction)} resolves the name
	 * again before giving up. Each attempt past the first means a promote
	 * landed in the moment between resolving the name and taking the write
	 * gate, which one more attempt settles unless promotes are arriving
	 * without pause.
	 */
	private static final int WRITE_RESOLVE_ATTEMPTS = 8;

	/**
	 * How long to wait for a retiring instance to finish closing before its
	 * directory is used again. A close that takes longer is abandoned - the
	 * lock inside the Lucene directory is what then still keeps a new writer
	 * out until the old one is really gone. A handover flush that abandons a
	 * close fails, as the close may still be pushing.
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
	 * How many times the node lost each index without handing it over, by
	 * index name. A generation that is being opened is not in
	 * {@link #indexes} yet, so a revoke does not reach it. Every instance
	 * follows the count of its index, see
	 * {@link Index#followRevocations(java.util.function.LongSupplier)}, and
	 * the open reads the count before it pulls and again after, see
	 * {@link #loadIndex(String, String)}.
	 */
	private final ConcurrentHashMap<String, AtomicLong> revocations = new ConcurrentHashMap<>();

	/**
	 * Lock held while the set of indexes is changed, so that two callers do
	 * not create or delete the same index at the same time.
	 */
	private final ReentrantLock lifecycleLock;

	/**
	 * Removals whose registry write may have landed without this node
	 * finding out. Each is settled when the registry can be read again, see
	 * {@link #settlePendingRemovals()}.
	 */
	private final PendingRemovalsFile pendingRemovals;

	/**
	 * Told about every index and generation this node deleted, see
	 * {@link RemovalListener}.
	 */
	private final List<RemovalListener> removalListeners;

	/**
	 * The generations held in the cache, by full name, with how many holds
	 * each has. See {@link #hold(String...)}.
	 */
	private final ConcurrentHashMap<String, Integer> held;

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
	 * One lock per index name, between the writes to the index and a manual
	 * promote of it. A write takes the read side inside the write gate of its
	 * generation, around the check that the name still answers from that
	 * generation and the change. A promote takes the write side around the
	 * registry write.
	 *
	 * <p>The {@link Index#holdWrites() hold} of a promote is on an instance
	 * in the cache. A write can be inside an instance the cache does not
	 * hold: one it is still opening, or one the cache evicted while the write
	 * ran. This lock makes the promote wait for such a write too.
	 *
	 * <p>Taken after the write gate, never before it. A reindex job holds the
	 * gate of its source and then promotes, and a write that waited for this
	 * lock outside the gate would wait for the job while the job waits for
	 * the gate.
	 */
	private final ConcurrentHashMap<String, ReentrantReadWriteLock> promoteLocks;

	/**
	 * Runs the refresh passes {@link RegistryPoller} hands over and the disk
	 * sweep. One thread, so neither ever runs next to the other. Owned here so
	 * that shutting this down also stops the node from synchronizing.
	 */
	private final ScheduledExecutorService refreshExecutor;

	/**
	 * Runs the pulls of a refresh, the opens of a preload and the work an
	 * ownership change asks for next to each other, so that one index that is
	 * slow to pull or slow to push does not hold up the others.
	 */
	private final ExecutorService pullExecutor;

	/**
	 * The last piece of ownership work queued for an index name, so that the
	 * next piece for the same name runs after it. Committing and pushing an
	 * index that is being handed over takes as long as the storage does, and
	 * two changes for one index must still reach it in the order they
	 * happened.
	 *
	 * <p>An entry is removed once nothing is queued behind it. The empty name
	 * stands for a change that names no index, which is this node gaining or
	 * losing every index at once.
	 */
	private final ConcurrentHashMap<String, CompletableFuture<Void>> ownershipQueues;

	/**
	 * Completed when {@link #close()} has closed every instance. A handover
	 * flush that can not be queued because the node is shutting down waits
	 * for it, and then answers with what the close pushed.
	 */
	private final CompletableFuture<Void> closeFinished;

	/**
	 * Names of the indexes, without a generation, that {@link #close()} could
	 * not push: a close that failed, or an evicted instance that did not
	 * finish closing in time. A handover flush asked for after the close
	 * fails for these, so that the claim on the index is left to lapse.
	 */
	private final Set<String> unpushedOnClose;

	/**
	 * The threads a search on this node spreads over, handed to every index
	 * opened here.
	 */
	private final SearchThreads searchThreads;

	/**
	 * Prepares the readers of every index opened here for counting facets
	 * before the first search asks, see {@link FacetWarmer}.
	 */
	private final FacetWarmer facetWarmer;

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
	 * The registry version that the last check of the local copies read. Many
	 * paths other than the poller read the registry, so a poll can find the
	 * registry already at a new version. A version that differs from this
	 * one starts a new check, whatever the poll reports.
	 */
	private String sweptRegistryVersion;

	/**
	 * Whether this node has read a registry that lists at least one index since
	 * it started. Until it has, a registry that lists none can be a registry
	 * that was lost, and the copies this node holds are kept.
	 */
	private boolean registryListedIndexes;

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
	 * The caches a search reads through, one set for the node - see
	 * {@link SearchCaches} for what each holds and how it is sized.
	 */
	private final SearchCaches searchCaches;

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
			SearchThreads.inline(),
			FacetWarmer.none(),
			SearchCaches.defaults()
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
		SearchThreads searchThreads,
		FacetWarmer facetWarmer,
		SearchCaches searchCaches
	) throws IOException {
		this.nodeState = nodeState;
		this.syncProvider = syncProvider;
		this.searchThreads = searchThreads;
		this.facetWarmer = facetWarmer;
		this.searchCaches = searchCaches;
		this.registry = registry;
		this.registryHints = registryHints;
		this.removals = removals;
		this.metrics = metrics;
		this.verifyInterval = verifyInterval;
		this.lastManifestChecks = new ConcurrentHashMap<>();
		this.lifecycleLock = new ReentrantLock();
		this.nameLocks = new ConcurrentHashMap<>();
		this.promoteLocks = new ConcurrentHashMap<>();
		this.closeGracePeriod = closeGracePeriod;
		this.retiring = new ConcurrentHashMap<>();
		this.closeFinished = new CompletableFuture<>();
		this.unpushedOnClose = ConcurrentHashMap.newKeySet();
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

		/*
		 * With a remote, a directory the registry does not name is a copy of a
		 * removed index, and the sweep and a creation remove it. In a
		 * directory that local mode wrote, such a directory is the only copy
		 * of a local index, so the node refuses to start there.
		 */
		if(syncProvider.hasRemote()) {
			LocalModeFiles.requireNoneIn(storageDirectory);
		}

		Files.createDirectories(indexRoot);
		this.pendingRemovals = PendingRemovalsFile.open(storageDirectory);
		this.removalListeners = new CopyOnWriteArrayList<>();
		this.held = new ConcurrentHashMap<>();

		/*
		 * Eviction only retires an instance. The closing - which may flush
		 * unpushed changes over the network - happens later on the close
		 * executor, never inside the cache operation that evicted it.
		 */
		var builder = Caffeine.newBuilder()
			.<String, Index>evictionListener((key, value, cause) -> retire(key, value));

		if(maxOpen.isPresent()) {
			/*
			 * Counted by weight rather than by size, so that a held generation
			 * weighs nothing and is never the one evicted, see hold.
			 */
			builder.maximumWeight(maxOpen.getAsInt())
				.weigher((String key, Index value) -> held.containsKey(key) ? 0 : 1);
		}

		this.indexes = builder.build(key -> loadIndex(key, Meters.SOURCE_BACKGROUND));
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
		if(this.diskMaxSize.isPresent() && syncProvider.hasRemote()) {
			this.refreshExecutor.scheduleWithFixedDelay(
				this::sweepDisk,
				diskSweepInterval.toMillis(),
				diskSweepInterval.toMillis(),
				TimeUnit.MILLISECONDS
			);
		}

		/*
		 * Gaining or losing an index changes the mode its open generations
		 * need. Gaining one also opens the index. Both are handed off, because
		 * the notification arrives on the thread that coordinates ownership,
		 * and that thread must not do Lucene work.
		 */
		this.ownershipQueues = new ConcurrentHashMap<>();
		this.nodeStateListener = new NodeState.Listener() {
			@Override
			public void onOwnershipChanged(NodeState state, String index) {
				queueOwnership(index, () -> {
					reopenOwned(index, true);
					openGainedIndex(index);
				});
			}

			@Override
			public void onOwnershipRevoked(NodeState state, String index) {
				/*
				 * Said to the open generations here rather than in the queued
				 * work: a flush queued for a handover this node had chosen may
				 * still be ahead of it, and it must not push over what a
				 * successor has already written.
				 */
				revokeWriting(index);

				queueOwnership(index, () -> reopenOwned(index, false));
			}
		};
		nodeState.addListener(nodeStateListener);
	}

	/**
	 * Run a piece of ownership work for one index, after everything queued
	 * for that index before it and next to the work queued for every other
	 * index.
	 *
	 * <p>Reopening an index this node is handing over commits and pushes it,
	 * which takes as long as the storage does. Running those next to each
	 * other keeps one index that is slow to push from delaying the failover
	 * of every other, and keeps both away from the refresh thread that reads
	 * the registry.
	 *
	 * @param index
	 *   name of the index the work belongs to, without a generation, or
	 *   {@code null} when the change names no index
	 * @param task
	 *   the work to run, which must handle its own failures
	 * @return
	 *   completes when the work has run, and completes exceptionally only
	 *   when the work did not run because the node is shutting down. Work
	 *   that was queued when {@link #close()} stopped the executor can also
	 *   never complete
	 */
	private CompletableFuture<Void> queueOwnership(String index, Runnable task) {
		var key = index == null ? "" : index;

		/*
		 * The queued work never fails, so the next piece for the same index is
		 * never skipped for a failure of the one before it.
		 */
		Runnable guarded = () -> {
			try {
				task.run();
			} catch(RuntimeException e) {
				logger.atLevel(Interruptions.levelOf(e))
					.addKeyValue("index", key)
					.setCause(e)
					.log("Could not follow the ownership of an index; " + e.getMessage());
			}
		};

		CompletableFuture<Void> queued;
		try {
			queued = ownershipQueues.compute(key, (name, tail) -> tail == null
				? CompletableFuture.runAsync(guarded, pullExecutor)
				: tail.thenRunAsync(guarded, pullExecutor)
			);
		} catch(RejectedExecutionException e) {
			// Shutting down; the close path flushes everything that is still open
			return CompletableFuture.failedFuture(e);
		}

		/*
		 * Removed only while it is still the last piece, so that an entry
		 * something is queued behind is left where it is.
		 */
		queued.whenComplete((result, e) -> ownershipQueues.remove(key, queued));

		return queued;
	}

	/**
	 * Get how many times the node lost an index without handing it over, see
	 * {@link #revocations}.
	 *
	 * @param name
	 *   name of the index, without a generation
	 */
	private AtomicLong revocationsOf(String name) {
		return revocations.computeIfAbsent(name, n -> new AtomicLong());
	}

	/**
	 * Stop every generation of an index from pushing what it holds, because
	 * the node lost the index without choosing to hand it over. Covers the
	 * instances that are closing as well, which push while they do.
	 *
	 * @param name
	 *   name of the index, without a generation
	 */
	private void revokeWriting(String name) {
		// Counted first, so that an open that is still pulling sees the count
		revocationsOf(name).incrementAndGet();

		for(var entry : indexes.asMap().entrySet()) {
			if(IndexName.parse(entry.getKey()).index().equals(name)) {
				entry.getValue().revokeWriting();
			}
		}

		for(var retired : retiring.values()) {
			var parsed = IndexName.tryParse(retired.name).orElse(null);
			if(parsed != null && parsed.index().equals(name)) {
				retired.index.revokeWriting();
			}
		}
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
	 * @return
	 *   {@code false} when a generation could not push what it holds and kept
	 *   its writer, so the handover has to be called off. Every other
	 *   generation is still reopened, and a failure after the push does not
	 *   count - what the successor pulls is already written by then
	 */
	private boolean reopenOwned(String name, boolean flush) {
		var flushed = true;

		for(var entry : indexes.asMap().entrySet()) {
			if(name != null && !IndexName.parse(entry.getKey()).index().equals(name)) {
				continue;
			}

			try {
				if(!entry.getValue().reopen(flush)) {
					flushed = false;
				}
			} catch(RuntimeException e) {
				logger.atLevel(Interruptions.levelOf(e))
					.addKeyValue("index", entry.getValue().getId())
					.setCause(e)
					.log("Could not reopen index; " + e.getMessage());
			}
		}

		return flushed;
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
			 * Queued again rather than opened here: a full copy can take
			 * minutes, and the next ownership change for the index waits
			 * behind the work this runs in.
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
	 * it in, here and now. The reopening an ownership change queues runs a
	 * moment later; a request that was admitted by claiming the index calls
	 * this first, so the writer it needs exists by the time it is served. A
	 * generation already open in the right mode is left as it is.
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
	 * closing are waited out. A local copy that no instance has open is
	 * pushed when it holds changes the remote never got, which a close whose
	 * push failed leaves behind. When the returned future completes, nothing
	 * held here can reach the remote anymore. The claim on the index is
	 * released only then, which is what keeps the successor from pulling a
	 * manifest the flush had not written yet.
	 *
	 * <p>Meaningful only after the ownership change that took the index away
	 * from this node has reached {@link NodeState} - reopening reads it, and
	 * flushes nothing for an index the node still writes.
	 *
	 * <p>When the node is shutting down, {@link #close()} pushes instead. A
	 * flush asked for then waits for the close to finish, and answers with
	 * what the close could push.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @return
	 *   completes when nothing more can be pushed from here, and exceptionally
	 *   when a generation could not be pushed, when an evicted instance did not
	 *   finish closing in time, or when a local copy still holds changes the
	 *   remote never got - the index keeps its writer and is served here until
	 *   the handover is tried again
	 */
	public CompletableFuture<Void> flushForHandover(String index) {
		var flushed = new CompletableFuture<Void>();

		/*
		 * Queued like an ownership change rather than run here, so that it
		 * follows the reopening the change that took the index away already
		 * asked for.
		 */
		queueOwnership(index, () -> {
			try {
				var pushed = reopenOwned(index, true);

				// An evicted generation that is still closing may push while it does
				for(var name : retiring.keySet()) {
					var parsed = IndexName.tryParse(name).orElse(null);
					if(parsed != null && parsed.index().equals(index) && !drainRetiring(name, true)) {
						pushed = false;
					}
				}

				if(!pushStrandedCopies(index)) {
					pushed = false;
				}

				if(pushed) {
					flushed.complete(null);
				} else {
					flushed.completeExceptionally(new IOException(
						"Could not push everything the index `" + index
							+ "` holds before handing it over"
					));
				}
			} catch(Throwable t) {
				flushed.completeExceptionally(t);
			}
		}).whenComplete((result, e) -> {
			if(e != null) {
				/*
				 * Never run, as the node is shutting down. The close path
				 * pushes what is still open, and the container can run it
				 * before the ownership asks for this flush, or while it does.
				 * The answer is thus what the close pushed.
				 */
				answerAfterClose(index, flushed);
			}
		});

		return flushed;
	}

	/**
	 * Answer a handover flush that could not run because the node is
	 * shutting down, when {@link #close()} has finished. Nothing is pushed
	 * from here: the answer only says whether the close left anything of the
	 * index that the remote never got.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @param flushed
	 *   completed normally when the close pushed everything the index held,
	 *   and exceptionally when the close could not push a generation of the
	 *   index, or when a local copy of the index still holds changes the
	 *   remote never got
	 */
	private void answerAfterClose(String index, CompletableFuture<Void> flushed) {
		closeFinished.whenComplete((result, e) -> {
			try {
				if(unpushedOnClose.contains(index) || holdsUnpushedCopies(index)) {
					flushed.completeExceptionally(new IOException(
						"Could not push everything the index `" + index
							+ "` holds before shutting down"
					));
				} else {
					flushed.complete(null);
				}
			} catch(Throwable t) {
				flushed.completeExceptionally(t);
			}
		});
	}

	/**
	 * Whether a local copy of an index holds changes the remote never got,
	 * judged from the files alone. A copy that can not be read is reported as
	 * holding changes.
	 *
	 * @param index
	 *   name of the index, without a generation
	 */
	private boolean holdsUnpushedCopies(String index) {
		List<Path> paths;
		try(var listing = Files.list(indexRoot)) {
			paths = listing.filter(Files::isDirectory).toList();
		} catch(NoSuchFileException e) {
			return false;
		} catch(IOException e) {
			logger.atWarn()
				.addKeyValue("index", index)
				.setCause(e)
				.log("Could not list the local copies of the index; " + e.getMessage());

			return true;
		}

		for(var path : paths) {
			var name = path.getFileName().toString();
			var parsed = IndexName.tryParse(name).orElse(null);
			if(parsed != null && parsed.index().equals(index) && LocalCopy.hasUnpushedChanges(path)) {
				logger.atWarn()
					.addKeyValue("index", name)
					.log("A local copy holds changes the remote never got after the close");

				return true;
			}
		}

		return false;
	}

	/**
	 * Push the local copies of an index that hold changes the remote never
	 * got and that no instance has open. A close whose push failed leaves its
	 * commit in such a copy, and nothing else pushes it before the successor
	 * pulls.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @return
	 *   whether no local copy of the index is left holding changes the remote
	 *   never got
	 */
	private boolean pushStrandedCopies(String index) {
		List<Path> paths;
		try(var listing = Files.list(indexRoot)) {
			paths = listing.filter(Files::isDirectory).toList();
		} catch(NoSuchFileException e) {
			return true;
		} catch(IOException e) {
			logger.atWarn()
				.addKeyValue("index", index)
				.setCause(e)
				.log("Could not list the local copies of the index; " + e.getMessage());

			return false;
		}

		var clean = true;
		for(var path : paths) {
			var name = path.getFileName().toString();
			var parsed = IndexName.tryParse(name).orElse(null);
			if(parsed == null || !parsed.index().equals(index)) {
				continue;
			}

			// Held so that no instance opens the directory while it is pushed
			var lock = nameLock(name);
			lock.lock();
			try {
				if(!LocalCopy.hasUnpushedChanges(path)) {
					continue;
				}

				if(indexes.getIfPresent(name) != null || retiring.containsKey(name)) {
					/*
					 * The reopen and the drain above pushed what the instances
					 * held, so this copy holds something an open instance
					 * could not push. Pushing beside it would race its pulls.
					 */
					logger.atWarn()
						.addKeyValue("index", name)
						.log("An open instance holds changes the remote never got");

					clean = false;
					continue;
				}

				var registered = parsed.isPinned() && registry.get(parsed.index())
					.map(entry -> entry.hasGeneration(parsed.generation()))
					.orElse(false);

				if(!registered) {
					/*
					 * A generation the registry does not name is being removed
					 * or created. A push could write a manifest under a prefix
					 * that a removal is clearing, so the handover waits until
					 * the registry settles which of the two it is.
					 */
					logger.atWarn()
						.addKeyValue("index", name)
						.log("A generation the registry does not name holds changes the remote never got");

					clean = false;
					continue;
				}

				var createdAt = createdAtOf(parsed);
				var sync = syncProvider.createSync(parsed, path, createdAt);
				Index.pushLocalCopy(path, sync);

				sync.syncedVersion().ifPresent(version -> registryHints.reportManifest(
					parsed.index(),
					parsed.generation(),
					createdAt,
					version
				));

				logger.atInfo()
					.addKeyValue("index", name)
					.log("Pushed changes that a close left behind, before the handover");
			} catch(IOException | RuntimeException e) {
				logger.atLevel(Interruptions.levelOf(e))
					.addKeyValue("index", name)
					.setCause(e)
					.log("Could not push changes that a close left behind; " + e.getMessage());

				clean = false;
			} finally {
				lock.unlock();
			}
		}

		return clean;
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
	 * @return
	 *   {@code true} when there was no retiring instance, or its close finished
	 *   without a failed push. {@code false} when the close could not push what
	 *   the instance held, or did not finish within {@link #RETIRING_WAIT} and
	 *   may still be pushing
	 */
	private boolean drainRetiring(String name, boolean commit) {
		var retired = retiring.get(name);
		if(retired == null) {
			return true;
		}

		retired.closeNow(commit);
		if(!retired.await(RETIRING_WAIT)) {
			logger.atWarn()
				.addKeyValue("index", name)
				.log("Retired instance of this index did not finish closing, continuing without it");

			return false;
		}

		return retired.pushed();
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

		/**
		 * Completed when the close has finished, with whether it did so
		 * without a failed push.
		 */
		private final CompletableFuture<Boolean> done;

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

			var pushed = false;
			try {
				try {
					index.close(commit);
					pushed = true;
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
				done.complete(pushed);
			}
		}

		/**
		 * Whether the close finished without a failed push. {@code false}
		 * while the close has not finished.
		 */
		boolean pushed() {
			return done.getNow(false);
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

	/**
	 * Close every instance, which commits and pushes what this node holds.
	 * What could not be pushed is kept for {@link #flushForHandover(String)},
	 * which the ownership can ask for after this or while this runs, as
	 * nothing orders the two when the container shuts down.
	 */
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

		try {
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
				if(!retired.await(RETIRING_WAIT) || !retired.pushed()) {
					markUnpushedOnClose(retired.name);
				}
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
					markUnpushedOnClose(index.getId());

					logger.atWarn()
						.addKeyValue("index", index.getId())
						.setCause(e)
						.log("Could not close index; " + e.getMessage());
				}

				recordUsed(index.getId());
			}

			indexes.invalidateAll();
		} finally {
			closeFinished.complete(null);
		}
	}

	/**
	 * Keep the index of a generation that {@link #close()} could not push,
	 * for the handover flushes asked for after it.
	 *
	 * @param name
	 *   name of the generation
	 */
	private void markUnpushedOnClose(String name) {
		unpushedOnClose.add(IndexName.tryParse(name).map(IndexName::index).orElse(name));
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
	 *   of indexes the registry no longer holds are looked for only then,
	 *   once when the node starts, and when the copy of the registry is at a
	 *   version that the last look did not read.
	 */
	void refresh(boolean registryChanged) {
		refreshing = true;
		try {
			settlePendingRemovals();

			var registryVersion = registry.version();
			if(registryChanged
				|| !sweptUnregistered
				|| !Objects.equals(registryVersion, sweptRegistryVersion))
			{
				sweptUnregistered = true;
				sweptRegistryVersion = registryVersion;
				removeUnregisteredCopies();
				removeCopiesOfReplacedGenerations();
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
						logger.atLevel(Interruptions.levelOf(e))
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
			logger.atLevel(Interruptions.levelOf(e))
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
	 * generation's manifest, or when {@link #verifyInterval} has passed.
	 *
	 * <p>Only a hint standing exactly where the copy does defers the request.
	 * A hint above the copy is the ordinary case of a writer having pushed,
	 * and one below it says the generation was replaced - a name deleted and
	 * created again writes a first manifest whose version is under the one
	 * the copy of the deleted index reached, and the copy would go on
	 * answering with the deleted documents until the verify interval. The
	 * copy can also stand a version or two above a hint that has not been
	 * flushed yet, which costs one manifest request and settles.
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
		if(hint.getAsLong() != index.getSyncedManifestVersion().orElse(0)) {
			/*
			 * A writer reported a version this copy is not at. A failed pull
			 * lands here again on the next pass, as the copy stays away from
			 * the hint until one succeeds.
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
			indexes.get(name, key -> loadIndex(key, Meters.SOURCE_PRELOAD));
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
	 * Close and remove the open generations whose copy belongs to a deleted
	 * generation of the same name.
	 *
	 * <p>The first generation of every index has the same name. When an index
	 * is deleted and created again on another node between two reads of the
	 * registry, the registry still names the generation this node holds
	 * open, and the copy is not removed as unregistered. It holds the deleted
	 * documents, and a pull does not replace it until the new index pushes
	 * its first manifest. The manifest of the copy records when its
	 * generation was created, and a time that is not the one the registry
	 * states means the copy is of the deleted generation. Its changes belong
	 * to no generation the registry names, so it closes without a push. The
	 * next request opens the generation the registry names.
	 *
	 * <p>A copy or a registry entry that states no time is kept: nothing tells
	 * that it is of another generation.
	 */
	private void removeCopiesOfReplacedGenerations() {
		if(!registry.hasBeenRead()) {
			return;
		}

		for(var name : List.copyOf(indexes.asMap().keySet())) {
			var parsed = IndexName.tryParse(name).orElse(null);
			if(parsed == null || !parsed.isPinned()) {
				continue;
			}

			var createdAt = createdAtOf(parsed);
			if(createdAt == null) {
				continue;
			}

			try {
				var copyCreatedAt = LocalCopy.generationCreatedAt(indexRoot.resolve(name));
				if(copyCreatedAt.isEmpty()
					|| copyCreatedAt.get().toEpochMilli() == createdAt.toEpochMilli()) {
					continue;
				}

				logger.atWarn()
					.addKeyValue("index", name)
					.addKeyValue("copyCreatedAt", copyCreatedAt.get().toString())
					.addKeyValue("createdAt", createdAt.toString())
					.log("Closing the open copy of a deleted generation that had the same name");

				lifecycleLock.lock();
				try {
					removeLocalCopy(name);
				} finally {
					lifecycleLock.unlock();
				}
			} catch(IOException e) {
				logger.atWarn()
					.addKeyValue("index", name)
					.setCause(e)
					.log("Could not check the open copy against the registry; " + e.getMessage());
			}
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
	 *
	 * <p>A registry that has never listed an index to this node is the
	 * exception. A registry that was lost, restored without its file, or
	 * emptied by a power loss looks the same as a deployment with no indexes,
	 * and removing copies on that answer deletes the whole deployment when the
	 * copies are the storage. The node keeps the copies and logs an error
	 * instead. Once this node has read a registry that lists an index, a later
	 * read that lists none is a delete, and the copies go.
	 *
	 * <p>In local storage mode no copy is removed here. The directories are the
	 * only copy of the data, and a delete on this node removes its own copy. A
	 * directory the registry does not name is one a delete could not remove,
	 * or one that a registry put back from an older backup does not know. The
	 * node keeps it and logs an error, as only the operator can tell the two
	 * apart.
	 */
	private void removeUnregisteredCopies() {
		if(!registry.hasBeenRead()) {
			return;
		}

		if(registry.list().notEmpty()) {
			registryListedIndexes = true;
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

		var vanished = new ArrayList<String>();
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

			vanished.add(directory);
		}

		if(vanished.isEmpty()) {
			return;
		}

		if(!registryListedIndexes) {
			logger.atError()
				.addKeyValue("directories", vanished.size())
				.log(
					"The index registry lists no indexes, but this node has index"
						+ " directories on disk. A registry that was lost looks the same as"
						+ " a deployment with no indexes, so the node keeps the directories"
						+ " instead of deleting them. Requests for those indexes return not"
						+ " found until the registry lists them again. Restore the registry"
						+ " from a backup, or rebuild it with the registry repair endpoint"
						+ " if you store indexes in object storage"
				);

			return;
		}

		if(!syncProvider.hasRemote()) {
			logger.atError()
				.addKeyValue("directories", String.join(", ", vanished.stream().sorted().toList()))
				.log(
					"The index registry does not name some index directories on disk. In"
						+ " local storage mode these directories are the only copy of their"
						+ " data, so the node keeps them. Requests for those indexes return"
						+ " not found. Put back a registry that names them, or delete the"
						+ " directories by hand if their indexes were deleted"
				);

			return;
		}

		for(var directory : vanished) {
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
			/*
			 * The pass read the registry before it took the lock, and a create
			 * of the same name can have run in between. A create writes the
			 * registry before it lets the lock go, so a generation it made is
			 * named here, and its copy is not one that vanished.
			 */
			var parsed = IndexName.tryParse(name).orElse(null);
			if(parsed != null && registry.names(parsed)) {
				return;
			}

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

			/*
			 * An open holds this lock for the open and the pull. The registry
			 * named the generation when that open started, so a create on
			 * another node has made the name again, and the copy is in use.
			 * The cache is not changed while the lock is held: an open in
			 * flight holds its cache entry while it waits for the lock.
			 */
			var lock = nameLock(name);
			if(!lock.tryLock()) {
				logger.atInfo()
					.addKeyValue("index", name)
					.log("Index is being opened again, keeping its local copy");
				return;
			}

			try {
				if((parsed != null && registry.names(parsed)) || indexes.getIfPresent(name) != null) {
					logger.atInfo()
						.addKeyValue("index", name)
						.log("Index was created again, keeping its local copy");
					return;
				}

				/*
				 * A failed push on close leaves a copy that holds a commit the
				 * remote never got. A registry that lost the name, for example
				 * one put back from an older backup, looks the same as a
				 * delete, and the copy is then the only one of those writes.
				 */
				var path = indexRoot.resolve(name);
				if(LocalCopy.hasUnpushedChanges(path)) {
					logger.atWarn()
						.addKeyValue("index", name)
						.log("Index was removed in the remote but its local copy holds changes that were never pushed, keeping it");
					return;
				}

				// A retired instance would write into the files being removed
				drainRetiring(name, false);

				deleteRecursively(path);
			} catch(IOException e) {
				logger.atWarn()
					.addKeyValue("index", name)
					.setCause(e)
					.log("Could not remove the local files of the index; " + e.getMessage());
			} finally {
				lock.unlock();
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
	 *
	 * <p>Does nothing in local storage mode, where the directories are the
	 * only copy of the data. Only directories named as a generation count as
	 * copies. Other directories are not counted and not removed.
	 */
	void sweepDisk() {
		if(diskMaxSize.isEmpty() || !syncProvider.hasRemote()) {
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
					var copyName = IndexName.tryParse(path.getFileName().toString());
					if(copyName.isEmpty() || !copyName.get().isPinned()) {
						// Not a copy this node made, such as lost+found on a mounted volume
						continue;
					}

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
			logger.atLevel(Interruptions.levelOf(e))
				.setCause(e)
				.log("Could not sweep the local copies; " + e.getMessage());
		}
	}

	/**
	 * Remove remote objects that no manifest names anymore, for every open
	 * generation this node writes. A generation that is written is swept by
	 * its pushes; this pass reaches the ones that receive no writes.
	 *
	 * <p>Sweeps only generations that are open. A held index that nothing
	 * opened has no manifest in memory, and opening it costs a writer and a
	 * pull, so its remote is left for the next time it opens.
	 *
	 * <p>Blocks on the remote, once per generation whose sweep is due. A
	 * generation that fails is logged and stepped over.
	 */
	public void sweepRemoteOrphans() {
		for(var index : indexes.asMap().values()) {
			if(!nodeState.isIndexer(IndexName.parse(index.getId()).index())) {
				continue;
			}

			try {
				index.sweepRemote();
			} catch(IOException | RuntimeException e) {
				logger.atLevel(Interruptions.levelOf(e))
					.addKeyValue("index", index.getId())
					.setCause(e)
					.log("Could not sweep the remote objects of the index; " + e.getMessage());
			}
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
	public static long parseSize(String value) {
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
	 * Open a generation for the cache.
	 *
	 * @param index
	 *   full name of the generation
	 * @param source
	 *   what asked for the open, one of the {@code SOURCE_} constants of
	 *   {@link Meters}. Everything but {@link Meters#SOURCE_PRELOAD} counts
	 *   an open against the index, so the count says how often the index was
	 *   wanted; the preload decides what to open from those counts and would
	 *   otherwise decide from what it opened last time
	 */
	private Index loadIndex(String index, String source) {
		var recordOpen = !Meters.SOURCE_PRELOAD.equals(source);

		var parsed = IndexName.parse(index);

		/*
		 * The caller resolved the name through the registry, but a delete can
		 * have gone through since. Opening the generation then would make its
		 * directory again and leave an instance of it here, which answers for
		 * the name if it is created again. A delete that starts after this
		 * check waits for the open in removeLocalCopy and closes what it made.
		 */
		if(!registry.names(parsed)) {
			throw new IndexNotFoundException(index);
		}

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
			var createdAt = createdAtOf(parsed);

			if(!syncProvider.hasRemote() && !Files.isDirectory(dataPath)) {
				refuseMissingLocalData(parsed, dataPath);
			}

			try {
				dropCopyOfAnotherGeneration(index, dataPath, createdAt);
				Files.createDirectories(dataPath);
			} catch(IOException e) {
				throw new UncheckedIOException(e);
			}

			if(recordOpen) {
				recordOpened(index, dataPath);
			}

			var revocationCount = revocationsOf(parsed.index());
			var revocationsAtOpen = revocationCount.get();

			var loaded = new Index(
				nodeState,
				index,
				dataPath,
				syncProvider.createSync(parsed, dataPath, createdAt),
				commitPolicy,
				documentCache,
				metrics,
				mergeFloorSegment,
				searchThreads,
				facetWarmer,
				searchCaches
			);

			/*
			 * Every push reports its version, so the other nodes learn from
			 * their next read of the registry that this generation moved -
			 * or that it did not, which is what lets them skip asking for
			 * its manifest. The report carries when the generation this
			 * instance opened was created, so that it is dropped rather than
			 * raise the hint of a generation created again under the name.
			 */
			loaded.followRevocations(revocationCount::get);

			loaded.onPushed(version -> registryHints.reportManifest(
				parsed.index(),
				parsed.generation(),
				createdAt,
				version
			));

			/*
			 * A freshly opened index starts out in NEEDS_PULL and can neither be
			 * read nor modified until it has been pulled.
			 */
			loaded.pull();
			lastManifestChecks.put(index, System.nanoTime());

			/*
			 * Counted once the generation is open, so the split between what
			 * the preload caught and what a request had to wait for is a
			 * split of the opens that actually happened.
			 */
			metrics.recordIndexOpen(source);

			if(revocationCount.get() != revocationsAtOpen) {
				/*
				 * The node lost this index while this generation was pulled,
				 * and the claim of its writer may be the one that was taken.
				 * The revoke and the reopen it queued did not reach this
				 * instance, so both are done here. The reopen opens a writer
				 * on a fresh claim if the node holds the index again. Asked
				 * last, as the instance is in no map until this method
				 * returns. A revoke after this point does not reach the
				 * instance either, but the instance follows the count and
				 * does not push on its old claim.
				 */
				loaded.revokeWriting();
				loaded.reopen(false);
			}

			return loaded;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Refuse to open a generation that the registry names in local storage
	 * mode and whose directory is gone.
	 *
	 * <p>In local storage mode the directory is the only copy of the data. It
	 * can be gone after a partial restore, a removal by hand, or a volume that
	 * is not mounted. The writer creates an index where it finds no commit,
	 * so an open here would serve an empty index under the name, and the next
	 * write would start a new index where a restore puts the old one back.
	 * The creation of an index or a generation makes its directory before the
	 * first open, see {@link #openWithDefinition(IndexName, IndexDef)}.
	 *
	 * @throws IndexNotFoundException
	 *   if a delete removed the generation from the registry after the caller
	 *   resolved it
	 * @throws UncheckedIOException
	 *   always otherwise, with a {@link NoSuchFileException} for the directory
	 */
	private void refuseMissingLocalData(IndexName generation, Path dataPath) {
		// A delete removes the registration before the directory
		if(!registry.names(generation)) {
			throw new IndexNotFoundException(generation.toString());
		}

		logger.atError()
			.addKeyValue("index", generation.toString())
			.addKeyValue("path", dataPath.toString())
			.log("The registry names an index whose local directory is gone, so the index"
				+ " is not opened. Restore the directory, or delete the index");

		throw new UncheckedIOException(new NoSuchFileException(
			dataPath.toString(),
			null,
			"the local data of the registered index " + generation + " is missing"
		));
	}

	/**
	 * When the registry says a generation was created, or {@code null} when it
	 * does not say or does not name the generation.
	 */
	/**
	 * Remove a local copy that belongs to another generation than the one the
	 * registry names under the same name.
	 *
	 * <p>The first generation of every index has the same name. A node that
	 * was away while the index was deleted and created again still holds its
	 * copy of the deleted generation, under the name the new generation has.
	 * Opened as it is, the copy would answer with the deleted documents, and
	 * a writer opened on it would push them as the new index. The manifest of
	 * the copy states when its generation was created, and a time that is not
	 * the one the registry states means the copy is of a deleted generation.
	 * Its changes belong to no generation the registry names, so nothing is
	 * left to push them to.
	 *
	 * <p>A copy or a registry entry that states no time is kept: nothing tells
	 * that it is of another generation.
	 *
	 * @param name
	 *   full name of the generation
	 * @param dataPath
	 *   the directory of the copy
	 * @param createdAt
	 *   when the registered generation was created, or {@code null}
	 */
	private void dropCopyOfAnotherGeneration(String name, Path dataPath, Instant createdAt)
		throws IOException {
		if(createdAt == null) {
			return;
		}

		var copyCreatedAt = LocalCopy.generationCreatedAt(dataPath);
		if(copyCreatedAt.isEmpty() || copyCreatedAt.get().toEpochMilli() == createdAt.toEpochMilli()) {
			return;
		}

		logger.atWarn()
			.addKeyValue("index", name)
			.addKeyValue("copyCreatedAt", copyCreatedAt.get().toString())
			.addKeyValue("createdAt", createdAt.toString())
			.log("Removing the local copy of a deleted generation that had the same name");

		deleteRecursively(dataPath);
	}

	private Instant createdAtOf(IndexName generation) {
		return registry.get(generation.index())
			.flatMap(entry -> entry.generation(generation.generation()))
			.map(RegisteredIndex.Generation::createdAt)
			.orElse(null);
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
		var resolved = registry.resolve(IndexName.parse(name)).toString();

		/*
		 * Answered from what is open without loading, which is what separates
		 * the callers that wait from the ones that do not. A generation that
		 * is open is the common case and is not timed.
		 */
		var open = indexes.asMap().get(resolved);
		if(open != null) {
			return open;
		}

		/*
		 * Timed across the whole wait rather than around the open alone: a
		 * caller that arrives while another thread is opening the same
		 * generation waits just as long without opening anything.
		 */
		var start = System.nanoTime();
		var ok = false;
		try {
			var index = indexes.get(resolved, key -> loadIndex(key, Meters.SOURCE_REQUEST));
			ok = true;
			return index;
		} finally {
			metrics.recordIndexOpenWait(System.nanoTime() - start, ok);
		}
	}

	/**
	 * Change the generation a name answers from, with the name resolved again
	 * at the moment the change is made.
	 *
	 * <p>Every change to the contents of an index goes through here. A caller
	 * that resolved the name earlier and then wrote would write into the
	 * generation the name answered from at that earlier moment: a promote in
	 * between - which is how a reindex hands over, and how an operator rolls a
	 * generation back - leaves that write in a generation nothing answers from
	 * anymore, acknowledged and unreachable. That window is what a request
	 * indexing a stream of documents is open across for as long as the stream
	 * runs.
	 *
	 * <p>The name is therefore resolved inside the write gate of the
	 * generation it names, and the action runs only while that generation is
	 * still the one it answers from. A promote is made under a
	 * {@link Index#holdWrites() hold} on the generation it replaces, so a
	 * change either goes through before the promote or resolves the new
	 * generation after it. A name that already carries a generation resolves
	 * to itself and is written where it says.
	 *
	 * @param name
	 *   the index, which is written to whichever generation is live, or one
	 *   generation of it by name
	 * @param action
	 *   the change, called with the generation to make it in
	 * @return
	 *   what the action returned
	 * @throws IndexNotFoundException
	 *   if the deployment holds no such index or generation
	 */
	public <T> T write(String name, WriteAction<T> action) {
		for(var attempt = 0; attempt < WRITE_RESOLVE_ATTEMPTS; attempt++) {
			var index = getOrThrow(name);
			try(var entry = index.enterWrite()) {
				var promoteLock = promoteLockOf(name).readLock();
				promoteLock.lock();
				try {
					if(resolves(name, index)) {
						return action.apply(index);
					}
				} finally {
					promoteLock.unlock();
				}
			}
		}

		throw new ValidationException(
			LIVE_GENERATION_UNSETTLED.toMessage(ObjectLocation.root(), "name", name)
		);
	}

	/**
	 * Get the lock between the writes to an index and a manual promote of it,
	 * see {@link #promoteLocks}.
	 *
	 * @param name
	 *   the index, or one generation of it
	 */
	private ReentrantReadWriteLock promoteLockOf(String name) {
		return promoteLocks.computeIfAbsent(
			IndexName.parse(name).index(),
			n -> new ReentrantReadWriteLock()
		);
	}

	/**
	 * Get whether a name still answers from the generation it was resolved to.
	 */
	private boolean resolves(String name, Index index) {
		return registry.resolve(IndexName.parse(name)).toString().equals(index.getId());
	}

	/**
	 * One change to an index, as {@link #write(String, WriteAction)} runs it.
	 */
	@FunctionalInterface
	public interface WriteAction<T> {
		/**
		 * Make the change in the generation the name answers from.
		 *
		 * @param index
		 *   the generation, which answers for the name for as long as this
		 *   call runs
		 */
		T apply(Index index);
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
	 * Keep generations open in the cache until the returned hold is closed.
	 *
	 * <p>A node that caps how many indexes it keeps open, see
	 * {@link #maxOpen}, closes the least used one when it opens another. Use
	 * is counted by the lookups through this class. Work that looks a
	 * generation up once and then uses the instance for a long time, such as
	 * a reindex job, looks unused, and the instance is closed under it. A
	 * held generation is never the one closed for the cap. It still closes
	 * when it is deleted, when the node loses it, and when the node stops.
	 *
	 * <p>Take the hold before the lookup, so that the instance the lookup
	 * answers with is held from the start. Held generations are not counted
	 * against the cap, so a node with holds can keep more than the cap open.
	 *
	 * @param names
	 *   full names of the generations, as {@code index@generation}
	 * @return
	 *   the hold, which lets the generations go when closed. Each hold
	 *   counts, so two holds of one generation keep it until both are closed
	 */
	public Hold hold(String... names) {
		var list = List.of(names);
		for(var name : list) {
			held.merge(name, 1, Integer::sum);
			reweigh(name);
		}

		return new Hold(list);
	}

	/**
	 * Weigh an open generation again, after it was held or let go.
	 */
	private void reweigh(String name) {
		indexes.asMap().computeIfPresent(name, (key, index) -> index);
	}

	/**
	 * Generations held open in the cache, see {@link Indexes#hold(String...)}.
	 */
	public final class Hold implements AutoCloseable {
		private final List<String> names;
		private final AtomicBoolean closed;

		private Hold(List<String> names) {
			this.names = names;
			this.closed = new AtomicBoolean();
		}

		/**
		 * Let the generations go. The cache can then close them for the cap
		 * again. Closing a hold twice changes nothing.
		 */
		@Override
		public void close() {
			if(!closed.compareAndSet(false, true)) {
				return;
			}

			for(var name : names) {
				held.computeIfPresent(name, (key, count) -> count > 1 ? count - 1 : null);
				reweigh(name);
			}
		}
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
	 * Get the caches a search reads through, for the meters a node reports.
	 *
	 * @return
	 */
	public SearchCaches getSearchCaches() {
		return searchCaches;
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
	 * Read the registry now, for a caller that has to know which generation
	 * a name answers from as of this moment rather than as of the last poll.
	 * One conditional request, and nothing is opened or pulled: a name
	 * resolved after this answers from what the registry says now, and the
	 * generation it names is opened by the request that asks for it.
	 *
	 * @return
	 *   whether the registry could be read
	 */
	public boolean refreshRegistry() {
		return registry.refresh();
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
	 * @throws IndexStorageHeldException
	 *   if the storage of the first generation, or in local storage mode a
	 *   directory of any generation of the name, holds data that nothing said
	 *   was deleted, or if the registry is gone from the object storage and
	 *   the storage holds generations that a registry repair registers
	 * @throws RegistryException
	 *   if the registry could not be read or written, or if another node
	 *   deleted the index while this creation ran
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
			var registered = registry.create(index.index(), generation);

			var removedMeanwhile = false;
			var reReadFailed = false;
			try {
				/*
				 * Cleared after winning the registration, so that only the
				 * winner touches the storage. A name deleted earlier lands on
				 * the prefix the delete marked and on the directory the old
				 * copy used, and the first generation is opened by pulling -
				 * so what the delete left has to go before the open, or the
				 * new index starts out holding the old documents.
				 */
				prepareForCreation(index.withGeneration(generation), true);

				/*
				 * A delete on another node can land between the registration
				 * and the clearing, and the clearing then takes the mark that
				 * delete left. Without the mark nothing removes the objects,
				 * and a later create of the name finds them held. So the
				 * stored registry is read again: when it no longer names the
				 * index, the mark is put back and the create is answered as
				 * the conflict it is. A delete after this read leaves its
				 * mark after the clearing, where it stays.
				 *
				 * A read that fails can not tell, so the create fails too.
				 */
				if(!registry.refresh()) {
					reReadFailed = true;
					throw reReadFailure();
				}

				if(registry.get(index.index()).isEmpty()) {
					removedMeanwhile = true;
					markRemoved(index);
					throw RegistryException.conflict();
				}

				return openWithDefinition(index.withGeneration(generation), def);
			} catch(RuntimeException e) {
				if(!removedMeanwhile) {
					/*
					 * Only this registration. Another node can delete the
					 * name and create it again before the failure, and its
					 * index is registered under the same name.
					 */
					try {
						registry.removeCreated(registered);
					} catch(RuntimeException removeFailed) {
						e.addSuppressed(removeFailed);
					}

					if(reReadFailed) {
						restoreMarkAfterFailedReRead(index);
					}
				}

				throw e;
			}
		} finally {
			lifecycleLock.unlock();
		}
	}

	/**
	 * The failure of a create whose read of the registry after the
	 * registration failed.
	 */
	private static RegistryException reReadFailure() {
		return RegistryException.ioError(new IOException(
			"The registry could not be read after the registration"
		));
	}

	/**
	 * Put back the mark that a create cleared, when the read after the
	 * registration failed. A delete on another node can have come between the
	 * registration and the clearing, and its mark is then gone. The mark is
	 * put back only when a new read finds that the registry does not name the
	 * index or generation.
	 */
	private void restoreMarkAfterFailedReRead(IndexName target) {
		try {
			if(registry.refresh() && !registryNames(target)) {
				markRemoved(target);
			}
		} catch(RuntimeException e) {
			logger.atWarn()
				.addKeyValue("index", target.toString())
				.setCause(e)
				.log("Could not check the registry after a failed create; " + e.getMessage());
		}
	}

	/**
	 * Clear what a delete left where a generation is about to be created: the
	 * prefix it takes in the shared storage, see {@link IndexRemovals}, and
	 * the local copy this node holds of it.
	 *
	 * <p>The shared storage goes first, so that a prefix that is held refuses
	 * the creation before this node gives up anything of its own.
	 *
	 * <p>The local copy goes with it because a node removes its copy of a
	 * deleted index only when a registry poll tells it the index is gone,
	 * which a creation can well arrive before. Opening the generation on the
	 * copy the delete left would bring every document of the deleted index
	 * back: the pull finds nothing in the cleared prefix, the writer opens on
	 * the old segments, and the first push uploads them under the new name.
	 * A copy holding changes that were never pushed goes too - the generation
	 * it belonged to is no longer registered, so there is nothing left to push
	 * them to.
	 *
	 * <p>In local storage mode a directory is not a copy, it is the data. A
	 * delete there removes its directories at once and writes no mark, so a
	 * directory that is still there is not known to be deleted: it can be
	 * one that a registry put back from an older backup does not name. A
	 * directory of the name that holds a definition, a change log or a Lucene
	 * commit refuses the creation, and nothing is removed. A directory that
	 * holds none of these has no data to lose, and goes.
	 *
	 * @param generation
	 * @param wholeIndex
	 *   whether the index itself is being created, in which case a mark over
	 *   the index clears everything under it, settings and all, and every
	 *   local copy of the name goes rather than only the one being opened
	 * @throws IndexStorageHeldException
	 *   if the prefix holds a manifest nothing said was deleted, or in local
	 *   storage mode if a directory of the name holds data
	 * @throws UncheckedIOException
	 *   if the storage could not be asked or cleared
	 */
	private void prepareForCreation(IndexName generation, boolean wholeIndex) {
		try {
			if(wholeIndex) {
				removals.prepareForIndex(generation.index());
			}

			removals.prepareForGeneration(generation);

			var copies = new LinkedHashSet<String>();
			copies.add(generation.toString());

			if(wholeIndex) {
				copies.addAll(localCopiesOf(generation.index()));
			}

			if(!syncProvider.hasRemote()) {
				// Every directory is looked at before the first one goes
				for(var copy : copies) {
					if(holdsData(indexRoot.resolve(copy))) {
						throw IndexStorageHeldException.inLocalDirectory(IndexName.parse(copy));
					}
				}
			}

			for(var copy : copies) {
				removeLocalCopy(copy);
			}
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Whether the directory of a generation holds something that a removal
	 * would lose: the definition, the change log, or a Lucene commit.
	 */
	private static boolean holdsData(Path directory) throws IOException {
		if(!Files.isDirectory(directory)) {
			return false;
		}

		String[] files;
		try(var paths = Files.list(directory)) {
			files = paths.map(path -> path.getFileName().toString()).toArray(String[]::new);
		}

		for(var file : files) {
			if(file.equals(Index.DEFINITION_FILE) || file.equals(Index.CHANGES_FILE)) {
				return true;
			}
		}

		return SegmentInfos.getLastCommitGeneration(files) >= 0;
	}

	/**
	 * The names of the local copies this node holds of the generations of one
	 * index.
	 */
	private List<String> localCopiesOf(String index) throws IOException {
		try(var paths = Files.list(indexRoot)) {
			return paths.filter(Files::isDirectory)
				.map(path -> path.getFileName().toString())
				.filter(name -> IndexName.tryParse(name)
					.filter(parsed -> parsed.isPinned() && parsed.index().equals(index))
					.isPresent()
				)
				.toList();
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
	 * @throws IndexStorageHeldException
	 *   if the storage, or in local storage mode the directory, of the
	 *   generation holds data that nothing said was deleted
	 * @throws RegistryException
	 *   if the registry could not be read or written, or if another node
	 *   deleted the generation, or deleted and created it again, while this
	 *   creation ran
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
			var registered = registry.addGeneration(generation.index(), generation.generation())
				.generation(generation.generation())
				.orElseThrow();

			var removedMeanwhile = false;
			var reReadFailed = false;
			try {
				// Only this generation; the rest of the index is in use
				prepareForCreation(generation, false);

				/*
				 * A delete on another node can land between the registration
				 * and the clearing, and the clearing then takes the mark that
				 * delete left. So the stored registry is read again, as the
				 * create of an index does. When the generation is gone, the
				 * mark is put back. When another node created the generation
				 * again, that node cleared the prefix for its own generation,
				 * and this create must not open it. A read that fails can not
				 * tell, so the create fails too.
				 */
				if(!registry.refresh()) {
					reReadFailed = true;
					throw reReadFailure();
				}

				var current = registry.get(generation.index())
					.flatMap(entry -> entry.generation(generation.generation()));

				if(current.isEmpty()) {
					removedMeanwhile = true;
					markRemoved(generation);
					throw RegistryException.conflict();
				}

				if(!current.get().isSameRegistration(registered)) {
					throw RegistryException.conflict();
				}

				return openWithDefinition(generation, def);
			} catch(RuntimeException e) {
				if(!removedMeanwhile) {
					/*
					 * Only this registration. Another node can delete the
					 * generation and create it again before the failure, and
					 * its generation is registered under the same name.
					 */
					try {
						registry.removeCreatedGeneration(generation.index(), registered);
					} catch(RuntimeException removeFailed) {
						e.addSuppressed(removeFailed);
					}

					if(reReadFailed) {
						restoreMarkAfterFailedReRead(generation);
					}
				}

				throw e;
			}
		} finally {
			lifecycleLock.unlock();
		}
	}

	/**
	 * Open a generation and give it its definition, taking the local copy away
	 * again if the definition is refused or could not be written.
	 */
	private Index openWithDefinition(IndexName generation, IndexDef def) {
		var name = generation.toString();

		if(!syncProvider.hasRemote()) {
			// The open refuses a registered generation with no directory
			try {
				Files.createDirectories(indexRoot.resolve(name));
			} catch(IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		var index = indexes.get(name);

		try {
			index.updateDefinition(def);
			return index;
		} catch(IOException | RuntimeException e) {
			/*
			 * The caller takes the registration back, so the directory is
			 * one that nothing names. In local storage mode a directory that
			 * holds a definition refuses the next creation of the name, so
			 * it goes here, where it is known to hold no data of its own.
			 */
			try {
				index.close(false);
				indexes.invalidate(name);
				deleteRecursively(indexRoot.resolve(name));
			} catch(IOException e2) {
				e.addSuppressed(e2);
			}

			if(e instanceof IOException io) {
				throw new UncheckedIOException(io);
			}

			throw (RuntimeException) e;
		}
	}

	/**
	 * Make an index answer from one of its generations. Every caller using the
	 * index by name reads the promoted generation from here on, on this node at
	 * once and on every other within its refresh interval.
	 *
	 * <p>When this node holds the replaced generation open, the promote is
	 * made under a {@link Index#holdWrites() hold} on it. The promote also
	 * waits for the writes on this node that are inside an instance the cache
	 * does not hold, see {@link #promoteLocks}. Writes on this node that are
	 * already inside the replaced generation finish before the switch. Writes
	 * that start during the promote wait and then go to the promoted
	 * generation.
	 *
	 * @param name
	 *   name of the generation, as {@code index@generation}
	 * @return
	 *   the index as it is now registered
	 * @throws IndexNotFoundException
	 *   if the deployment holds no such index or generation
	 * @throws IndexUnsupportedException
	 *   if the index needs something this build does not have; the registry
	 *   is not changed
	 */
	public RegisteredIndex promote(String name) {
		var generation = IndexName.parse(name);
		if(!generation.isPinned()) {
			throw new ValidationException(
				GENERATION_NAME_REQUIRED.toMessage(ObjectLocation.root(), "name", name)
			);
		}

		try(var hold = holdWritesToLive(generation.index())) {
			var promoteLock = promoteLockOf(name).writeLock();
			promoteLock.lock();
			try {
				return registry.promote(generation.index(), generation.generation());
			} finally {
				promoteLock.unlock();
			}
		}
	}

	/**
	 * Hold the writes to the generation an index answers from, for a promote
	 * that replaces it.
	 *
	 * @return
	 *   the hold, or a hold that does nothing when this node does not hold
	 *   the generation open
	 */
	private Index.WriteHold holdWritesToLive(String index) {
		IndexName live;
		try {
			live = registry.resolve(IndexName.parse(index));
		} catch(RuntimeException e) {
			// The promote itself reports why the index can not be promoted
			return () -> {};
		}

		/*
		 * Not opened here. An open can wait for a hold on an instance that
		 * was evicted, and that hold can be the one the caller of the promote
		 * keeps. A promote is also how an operator moves an index off a
		 * generation that can not be opened. A write that is still opening
		 * the generation is held by the promote lock instead.
		 */
		var open = indexes.asMap().get(live.toString());
		return open == null ? () -> {} : open.holdWrites();
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
	 * prefix and the local copies of the name first, on whichever node runs
	 * it.
	 *
	 * <p>The mark is written after the registry, so that every mark stands
	 * for a delete that went through, and whether or not the local copies
	 * could be removed: the registry no longer names the index, so a caller
	 * cannot ask for the delete again. A mark that could not be written is
	 * logged and leaves the objects where they are; the registry audit
	 * reports them as held but not registered. Another node can create the
	 * name again before the mark is written. The delete then reads the
	 * registry again and takes its mark back, so that the mark does not
	 * stand over the new index.
	 *
	 * <p>A registry write whose answer is lost, and whose outcome a read of
	 * the registry cannot tell either, fails the delete with
	 * {@link RegistryRemovalUnknownException}. The delete may have landed all
	 * the same, and then its mark is still needed, so the removal is kept on
	 * this node until a later read of the registry tells. A delete sent again
	 * in the meantime finds the name gone and is answered as done when the
	 * first one landed.
	 *
	 * @param name
	 *   the index, or one generation of it as {@code index@generation}
	 * @throws IndexNotFoundException
	 *   if the deployment holds no such index or generation
	 * @throws ValidationException
	 *   if the generation named is the one its index answers from
	 * @throws RegistryException
	 *   if the registry could not be read or written; a
	 *   {@link RegistryRemovalUnknownException} when the delete may have
	 *   landed
	 * @throws IOException
	 *   if a local copy could not be removed; the delete itself went through
	 *   and the storage is marked, the files of the copy stay on this node
	 */
	public void delete(String name) throws IOException {
		var parsed = IndexName.parse(name);

		IOException copyFailure = null;
		lifecycleLock.lock();
		try {
			deleteLocked(parsed);
		} catch(IOException e) {
			// The delete went through, only a local copy stays
			copyFailure = e;
		} catch(RegistryRemovalUnknownException e) {
			keepPendingRemoval(e);
			throw e;
		} catch(IndexNotFoundException e) {
			/*
			 * A client that was told an earlier delete failed sends it again,
			 * and finds the name gone when that delete landed after all. The
			 * removal settles now, from the registry the removal just read.
			 */
			if(!settlePendingRemovalOf(parsed)) {
				throw e;
			}
		} finally {
			lifecycleLock.unlock();
		}

		notifyRemoved(parsed);

		if(copyFailure != null) {
			throw copyFailure;
		}
	}

	/**
	 * Something told about every index and generation this node deletes.
	 *
	 * <p>Told after the registry no longer names what was deleted and its
	 * removal mark was left, and outside the lock that orders creates and
	 * deletes, so a listener may wait for work that creates or deletes. A
	 * delete on another node is not told: a listener that must know about
	 * every delete also reads the registry.
	 */
	@FunctionalInterface
	public interface RemovalListener {
		/**
		 * The index, or the generation as {@code index@generation}, was
		 * deleted.
		 */
		void onRemoved(IndexName target);
	}

	/**
	 * Tell a listener about every index and generation this node deletes
	 * from now on.
	 */
	public void addRemovalListener(RemovalListener listener) {
		removalListeners.add(listener);
	}

	/**
	 * Stop telling a listener about deletes.
	 */
	public void removeRemovalListener(RemovalListener listener) {
		removalListeners.remove(listener);
	}

	/**
	 * Tell the listeners that a delete landed. A listener that fails is
	 * logged: the delete itself went through.
	 */
	private void notifyRemoved(IndexName target) {
		for(var listener : removalListeners) {
			try {
				listener.onRemoved(target);
			} catch(RuntimeException e) {
				logger.atWarn()
					.addKeyValue("index", target.toString())
					.setCause(e)
					.log("Could not act on a deleted index; " + e.getMessage());
			}
		}
	}

	/**
	 * Delete an index or a generation while {@link #lifecycleLock} is held.
	 */
	private void deleteLocked(IndexName parsed) throws IOException {
		if(parsed.isPinned()) {
			registry.removeGeneration(parsed.index(), parsed.generation());

			try {
				removeLocalCopy(parsed.toString());
			} finally {
				markRemoved(parsed);
			}

			return;
		}

		/*
		 * The generations are taken from what the removal read from the
		 * storage. The copy this node holds can be a refresh behind a
		 * create on another node, and would answer that the index does
		 * not exist.
		 */
		var index = registry.remove(parsed.index());

		// Every copy is tried, and the mark left, before the first failure
		// is thrown - the registry no longer names the index, so nothing
		// asks for this delete a second time
		IOException failure = null;
		for(var generation : index.generations()) {
			try {
				removeLocalCopy(parsed.withGeneration(generation.name()).toString());
			} catch(IOException e) {
				if(failure == null) {
					failure = e;
				} else {
					failure.addSuppressed(e);
				}
			}
		}

		markRemoved(parsed);

		if(failure != null) {
			throw failure;
		}
	}

	/**
	 * Keep a removal whose outcome is not known, so that it is settled when
	 * the registry can be read again.
	 */
	private void keepPendingRemoval(RegistryRemovalUnknownException unknown) {
		try {
			pendingRemovals.add(
				new PendingRemovalsFile.Removal(unknown.target(), unknown.createdAt())
			);
		} catch(IOException e) {
			unknown.addSuppressed(e);
			logger.atError()
				.addKeyValue("index", unknown.target().toString())
				.setCause(e)
				.log(
					"Could not record a delete whose outcome is not known; if it landed, its"
						+ " objects stay without a removal mark and the registry audit reports"
						+ " them as held but not registered; " + e.getMessage()
				);
		}
	}

	/**
	 * Settle every removal whose outcome was not known, when the registry can
	 * be read. Nothing is settled from a registry this node did not just read
	 * successfully, or from one it never read: an empty or stale copy would
	 * make every removal look as if it landed.
	 */
	private void settlePendingRemovals() {
		if(pendingRemovals.isEmpty()) {
			return;
		}

		var removed = new ArrayList<IndexName>();
		lifecycleLock.lock();
		try {
			if(!registry.refresh() || registry.version() == null) {
				return;
			}

			for(var removal : pendingRemovals.list()) {
				if(settle(removal)) {
					removed.add(removal.target());
				}
			}
		} catch(RuntimeException e) {
			logger.atWarn()
				.setCause(e)
				.log("Could not settle the deletes whose outcome is not known; " + e.getMessage());
		} finally {
			lifecycleLock.unlock();
		}

		removed.forEach(this::notifyRemoved);
	}

	/**
	 * Settle the removal of one target whose outcome was not known, from the
	 * registry this node read last. Called with {@link #lifecycleLock} held,
	 * right after a read of the registry.
	 *
	 * @return
	 *   whether a removal of the target was pending, landed, and left its mark
	 */
	private boolean settlePendingRemovalOf(IndexName target) {
		if(registry.version() == null) {
			return false;
		}

		for(var removal : pendingRemovals.list()) {
			if(removal.target().equals(target)) {
				return settle(removal);
			}
		}

		return false;
	}

	/**
	 * Settle one removal whose outcome was not known, from the registry this
	 * node read last.
	 *
	 * <p>A registry that names the index or generation with the time of
	 * creation the removal recorded still holds it, so the removal did not
	 * land. One that names it with another time holds an index or generation
	 * created again under the name. Its creation cleared what the removal
	 * left, and a mark now would lie over data that was not deleted. Only a
	 * registry that does not name it at all says the removal landed, and then
	 * the mark is left and the local copies go, as the delete would have done.
	 *
	 * <p>A generation of an index that is no longer registered is left
	 * without a mark of its own: the removal of the index marked everything
	 * under it.
	 *
	 * @return
	 *   whether the removal landed and nothing was created again under the
	 *   name since
	 */
	private boolean settle(PendingRemovalsFile.Removal removal) {
		var target = removal.target();
		var entry = registry.get(target.index()).orElse(null);

		boolean landed;
		boolean mark;
		if(target.isPinned()) {
			var generation = entry == null
				? null
				: entry.generation(target.generation()).orElse(null);
			landed = generation == null
				|| !sameTime(generation.createdAt(), removal.createdAt());
			mark = entry != null && generation == null;
		} else {
			landed = entry == null || !sameTime(entry.createdAt(), removal.createdAt());
			mark = entry == null;
		}

		logger.atInfo()
			.addKeyValue("index", target.toString())
			.addKeyValue("landed", landed)
			.log("Settled a delete whose outcome was not known");

		if(mark) {
			try {
				for(var copy : target.isPinned()
					? List.of(target.toString())
					: localCopiesOf(target.index())) {
					removeLocalCopy(copy);
				}
			} catch(IOException e) {
				logger.atWarn()
					.addKeyValue("index", target.toString())
					.setCause(e)
					.log("Could not remove a local copy of a deleted index; " + e.getMessage());
			}

			markRemoved(target);
		}

		try {
			pendingRemovals.remove(removal);
		} catch(IOException e) {
			logger.atWarn()
				.addKeyValue("index", target.toString())
				.setCause(e)
				.log("Could not forget a settled delete, it is settled again later; " + e.getMessage());
		}

		return mark;
	}

	/**
	 * Whether two times of creation are the same to the millisecond, the
	 * precision the registry stores them in. Two unknown times are the same.
	 */
	private static boolean sameTime(Instant a, Instant b) {
		if(a == null || b == null) {
			return a == b;
		}

		return a.toEpochMilli() == b.toEpochMilli();
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
						+ " and the registry audit reports them as held but not registered;"
						+ " deleting the index again is not possible, the registry no longer"
						+ " names it; " + e.getMessage()
				);
			return;
		}

		/*
		 * A create on another node can register the name and clear the prefix
		 * after the registry write of this delete and before its mark. The
		 * mark then stands over the new index, and after a registry loss a
		 * sweep would remove that index. A registry that can not be read
		 * keeps the mark.
		 */
		try {
			if(removals.withdraw(target, () -> registry.refresh() && registryNames(target))) {
				logger.atInfo()
					.addKeyValue("index", target.toString())
					.log("The index was created again before its removal mark was written, took the mark back");
			}
		} catch(IOException e) {
			logger.atWarn()
				.addKeyValue("index", target.toString())
				.setCause(e)
				.log("Could not check the removal mark against the registry; " + e.getMessage());
		}
	}

	/**
	 * Whether this node's copy of the registry names an index, or one
	 * generation of it.
	 */
	private boolean registryNames(IndexName target) {
		return target.isPinned()
			? registry.names(target)
			: registry.get(target.index()).isPresent();
	}

	/**
	 * Take the local copy of a generation out of use and remove its files.
	 * Changes that were never pushed go with it - the generation is no longer
	 * registered, so there is nothing left to push them to.
	 */
	private void removeLocalCopy(String name) throws IOException {
		/*
		 * Removed from the map rather than looked up first. A lookup answers
		 * nothing while an open of the name is in flight, and that open would
		 * then put an instance of the removed generation in the cache after
		 * this returns. The removal waits for an open in flight to finish and
		 * hands back what it opened.
		 */
		var index = indexes.asMap().remove(name);
		if(index != null) {
			index.close(false);
		}

		// A retired instance would write into the files being removed
		drainRetiring(name, false);

		deleteRecursively(indexRoot.resolve(name));
	}

	/**
	 * Remove a directory and everything in it.
	 *
	 * <p>Each manifest goes first. A manifest says that the files next to it
	 * are present, so no file goes while it stays. A removal that stops part
	 * way then leaves a directory that was never synchronized, and opening the
	 * index pulls it whole. A manifest left behind would name a commit that is
	 * gone. A directory without a manifest, such as one in local storage mode,
	 * is removed in the same way.
	 */
	private static void deleteRecursively(Path path) throws IOException {
		if(!Files.exists(path)) {
			return;
		}

		List<Path> paths;
		try(var walk = Files.walk(path)) {
			paths = walk.sorted(Comparator.reverseOrder()).toList();
		}

		for(var p : paths) {
			if(p.getFileName().toString().equals(LocalCopy.MANIFEST_FILE)) {
				Files.deleteIfExists(p);
			}
		}

		for(var p : paths) {
			Files.deleteIfExists(p);
		}
	}
}
