package se.l4.exofind.engine.metrics;

/**
 * Names of the meters the engine registers, and of the tags they carry.
 *
 * <p>A name here is a persistent identifier. Dashboards, recording rules and
 * alerts are written against it and live outside this repository, so renaming
 * one breaks them silently: the query keeps parsing and returns no data. Add a
 * name rather than change one, and leave a removed name out of use.
 *
 * <p>Every meter is documented in {@code docs/reference/metrics.md}, which is
 * the copy an operator reads.
 */
public final class Meters {
	private Meters() {
	}

	/**
	 * Time a search took end to end, as the endpoint measures it. Tagged with
	 * {@link #TAG_OUTCOME}, and with {@link #TAG_INDEX} when
	 * {@code EXOFIND_METRICS_INDEX_SEARCH_HISTOGRAM} is on.
	 */
	public static final String SEARCH = "exofind.search";

	/**
	 * Words a search let go of before it matched, one for each word dropped.
	 * Tagged with {@link #TAG_REASON}.
	 *
	 * <p>Counts words rather than searches, so a search that dropped three
	 * words adds three.
	 */
	public static final String SEARCH_RELAXATION = "exofind.search.relaxation";

	/**
	 * Pieces of search work handed to the search threads of the node - the
	 * segments a search collects and the facets it counts - by which thread
	 * ran each. Tagged with {@link #TAG_THREAD}.
	 *
	 * <p>The slices Lucene ranks on the same threads are not counted here.
	 * Pieces run on the request thread while the pool holds threads are the
	 * pool being busy when the search reached it.
	 */
	public static final String SEARCH_PIECES = "exofind.search.pieces";

	/**
	 * Time a write request took on the node that served it. Tagged with
	 * {@link #TAG_OPERATION} and {@link #TAG_OUTCOME}.
	 */
	public static final String WRITE = "exofind.write";

	/**
	 * Documents covered by write requests. Tagged with
	 * {@link #TAG_OPERATION}.
	 */
	public static final String WRITE_DOCUMENTS = "exofind.write.documents";

	/**
	 * Write requests this node handed to the node holding the index. Tagged
	 * with {@link #TAG_OUTCOME}.
	 */
	public static final String WRITE_FORWARDED = "exofind.write.forwarded";

	/**
	 * Time a Lucene commit took. Tagged with {@link #TAG_TRIGGER} and
	 * {@link #TAG_OUTCOME}.
	 */
	public static final String COMMIT = "exofind.commit";

	/**
	 * Time pushing an index to remote storage took. Tagged with
	 * {@link #TAG_OUTCOME}.
	 */
	public static final String SYNC_PUSH = "exofind.sync.push";

	/**
	 * Time pulling an index from remote storage took. Tagged with
	 * {@link #TAG_OUTCOME}.
	 */
	public static final String SYNC_PULL = "exofind.sync.pull";

	/**
	 * Synchronizations refused because another node had written the index.
	 * Tagged with {@link #TAG_OPERATION}.
	 *
	 * <p>Any value above zero means two nodes held the same index at once.
	 */
	public static final String SYNC_CONFLICT = "exofind.sync.conflict";

	/**
	 * Time an object storage request took. Tagged with {@link #TAG_OPERATION},
	 * {@link #TAG_OUTCOME} and {@code status}, which carries the HTTP status
	 * the request was answered with.
	 */
	public static final String STORAGE_OPERATION = "exofind.storage.operation";

	/**
	 * Index names this node gained or lost. Tagged with
	 * {@link #TAG_DIRECTION}.
	 */
	public static final String OWNERSHIP_CHANGE = "exofind.ownership.change";

	/**
	 * Requests answered with an error code. Tagged with {@link #TAG_CODE}.
	 */
	public static final String API_ERROR = "exofind.api.error";

	/**
	 * Requests refused for their credential. Tagged with
	 * {@link #TAG_REASON}.
	 */
	public static final String AUTH_FAILURE = "exofind.auth.failure";

	/**
	 * Generations open on this node.
	 */
	public static final String INDEXES_OPEN = "exofind.indexes.open";

	/**
	 * Index names this node currently writes.
	 */
	public static final String INDEXES_OWNED = "exofind.indexes.owned";

	/**
	 * Index names the deployment holds, as this node last read them.
	 */
	public static final String INDEXES_TOTAL = "exofind.indexes.total";

	/**
	 * Open generations per synchronization state. Tagged with
	 * {@link #TAG_STATE}, carrying one series per state and none per index.
	 */
	public static final String INDEX_STATE = "exofind.index.state";

	/**
	 * Present with the value {@code 1} for each open generation that is not
	 * {@code USABLE}. Tagged with {@link #TAG_INDEX}, {@link #TAG_GENERATION}
	 * and {@link #TAG_STATE}.
	 *
	 * <p>Carries no series while every generation is usable, so the index
	 * names it names are only the ones needing attention.
	 */
	public static final String INDEX_UNHEALTHY = "exofind.index.unhealthy";

	/**
	 * Documents in an index. Tagged with {@link #TAG_INDEX} and
	 * {@link #TAG_GENERATION}, and registered only on the node writing the
	 * index.
	 */
	public static final String INDEX_DOCUMENTS = "exofind.index.documents";

	/**
	 * Changes waiting for a commit. Tagged with {@link #TAG_INDEX} and
	 * {@link #TAG_GENERATION}, and registered only on the node writing the
	 * index.
	 */
	public static final String INDEX_PENDING_CHANGES = "exofind.index.pending.changes";

	/**
	 * Seconds the oldest change waiting for a commit has waited. Tagged with
	 * {@link #TAG_INDEX} and {@link #TAG_GENERATION}, and registered only on
	 * the node writing the index.
	 */
	public static final String INDEX_PENDING_AGE = "exofind.index.pending.age";

	/**
	 * Bytes an index occupies in this node's directory. Tagged with
	 * {@link #TAG_INDEX} and {@link #TAG_GENERATION}, and registered on every
	 * node holding a copy.
	 */
	public static final String INDEX_DISK_BYTES = "exofind.index.disk.bytes";

	/**
	 * Seconds since the refresh loop finished a pass.
	 */
	public static final String REGISTRY_REFRESH_AGE = "exofind.registry.refresh.age";

	/**
	 * Bytes the index directory occupies.
	 */
	public static final String DISK_USED_BYTES = "exofind.disk.used.bytes";

	/**
	 * Bytes the index directory may occupy, as
	 * {@code EXOFIND_INDEXES_DISK_MAX_SIZE} names it. Absent when no limit is
	 * configured.
	 */
	public static final String DISK_MAX_BYTES = "exofind.disk.max.bytes";

	/**
	 * Reads served from the document cache.
	 */
	public static final String DOCUMENT_CACHE_HITS = "exofind.document.cache.hits";

	/**
	 * Reads the document cache did not hold.
	 */
	public static final String DOCUMENT_CACHE_MISSES = "exofind.document.cache.misses";

	/**
	 * Entries the document cache dropped.
	 */
	public static final String DOCUMENT_CACHE_EVICTIONS = "exofind.document.cache.evictions";

	/**
	 * Facets answered from what an earlier search counted over the same
	 * scope.
	 */
	public static final String FACET_CACHE_HITS = "exofind.facet.cache.hits";

	/**
	 * Facets that had to be counted.
	 */
	public static final String FACET_CACHE_MISSES = "exofind.facet.cache.misses";

	/**
	 * Facet scope entries dropped to make room for ones asked for more
	 * recently.
	 */
	public static final String FACET_CACHE_EVICTIONS = "exofind.facet.cache.evictions";

	/**
	 * Segments whose counts over everything the index holds were reused by a
	 * facet.
	 */
	public static final String FACET_SEGMENT_HITS = "exofind.facet.segment.hits";

	/**
	 * Segments a facet had to count over everything the index holds.
	 */
	public static final String FACET_SEGMENT_MISSES = "exofind.facet.segment.misses";

	/**
	 * Reindex jobs this node knows of. Tagged with {@link #TAG_PHASE},
	 * carrying one series per phase and none per index.
	 */
	public static final String REINDEX_ACTIVE = "exofind.reindex.active";

	/**
	 * Name of an index, without a generation.
	 *
	 * <p>Only meters documented as per-index carry this. A deployment holding
	 * many indexes pays for one series per index per meter, so a meter that
	 * can answer without the name does not take it.
	 */
	public static final String TAG_INDEX = "index";

	/**
	 * Generation the values were read from, as {@code IndexName} names it.
	 *
	 * <p>Carried beside {@link #TAG_INDEX} by the per-index meters, which
	 * report one row per open generation. A query wanting the index rather
	 * than the generation sums over this.
	 */
	public static final String TAG_GENERATION = "generation";

	/** Whether the measured work succeeded: {@code success} or {@code error}. */
	public static final String TAG_OUTCOME = "outcome";

	/** Phase of a job, naming a value of the phase enum the meter reports. */
	public static final String TAG_PHASE = "phase";

	/** Kind of work, named by the meter that carries it. */
	public static final String TAG_OPERATION = "operation";

	/** What started a commit, one of the {@code TRIGGER_} constants. */
	public static final String TAG_TRIGGER = "trigger";

	/** Synchronization state of an index, naming an {@code IndexState}. */
	public static final String TAG_STATE = "state";

	/** Whether an index name was gained or lost. */
	public static final String TAG_DIRECTION = "direction";

	/** Error code from the API, as {@code docs/reference/errors.md} lists it. */
	public static final String TAG_CODE = "code";

	/**
	 * Which kind of thread ran a piece of search work: {@link #THREAD_POOL}
	 * or {@link #THREAD_REQUEST}.
	 */
	public static final String TAG_THREAD = "thread";

	/** A piece ran on one of the search threads of the node. */
	public static final String THREAD_POOL = "pool";

	/** A piece ran on the thread of the request that handed it over. */
	public static final String THREAD_REQUEST = "request";

	/**
	 * Why something was refused or given up: the reason a credential was
	 * turned down, or what made a word the one a search let go of.
	 */
	public static final String TAG_REASON = "reason";

	public static final String OUTCOME_SUCCESS = "success";
	public static final String OUTCOME_ERROR = "error";

	public static final String TRIGGER_CHANGES = "changes";
	public static final String TRIGGER_INTERVAL = "interval";
	public static final String TRIGGER_MERGES = "merges";
	public static final String TRIGGER_EXPLICIT = "explicit";

	public static final String DIRECTION_GAINED = "gained";
	public static final String DIRECTION_LOST = "lost";
	public static final String DIRECTION_REVOKED = "revoked";
}
