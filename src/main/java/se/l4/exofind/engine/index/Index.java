package se.l4.exofind.engine.index;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsCollectorManager;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.KeepOnlyLastCommitDeletionPolicy;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SnapshotDeletionPolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.CollectionTerminatedException;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.QueryRescorer;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.QueryBitSetProducer;
import org.apache.lucene.search.join.ToChildBlockJoinQuery;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.factory.primitive.IntLists;
import org.eclipse.collections.api.factory.primitive.IntObjectMaps;
import org.eclipse.collections.api.factory.primitive.IntSets;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.api.map.ImmutableMap;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.map.primitive.IntObjectMap;
import org.eclipse.collections.api.set.MutableSet;
import org.eclipse.collections.api.set.SetIterable;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.tuple.Tuples;

import com.google.protobuf.CodedOutputStream;

import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.analysis.SynonymOverlay;
import se.l4.exofind.engine.index.analysis.TypoExclusions;
import se.l4.exofind.engine.index.locales.LocaleSupport;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.schema.DefinitionCompatibility;
import se.l4.exofind.engine.index.schema.Field;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.IndexFeatures;
import se.l4.exofind.engine.index.schema.IndexSchema;
import se.l4.exofind.engine.index.schema.RankingConfig;
import se.l4.exofind.engine.index.schema.RankingOverride;
import se.l4.exofind.engine.index.settings.QuerySynonyms;
import se.l4.exofind.engine.index.settings.QueryTypoExclusions;
import se.l4.exofind.engine.index.settings.SearchSettings;
import se.l4.exofind.engine.index.state.StateSync;
import se.l4.exofind.engine.index.state.SyncConflictException;
import se.l4.exofind.engine.index.state.SyncIncompatibleException;
import se.l4.exofind.engine.logging.Log;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.query.AndQuery;
import se.l4.exofind.engine.query.BoostQuery;
import se.l4.exofind.engine.query.Facet;
import se.l4.exofind.engine.query.FieldQuery;
import se.l4.exofind.engine.query.FuseQuery;
import se.l4.exofind.engine.query.KnnQuery;
import se.l4.exofind.engine.query.NestedQuery;
import se.l4.exofind.engine.query.NotQuery;
import se.l4.exofind.engine.query.OrQuery;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.Rescore;
import se.l4.exofind.engine.query.TextQuery;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchExplanation;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortKey;

/**
 * Index represents a single index that can be searched or updated.
 */
public class Index {
	public static final String DEFINITION_FILE = "definition.ef.bin";

	/**
	 * File the change log is kept in while this index tracks its changes,
	 * next to the Lucene files and pushed with them - see
	 * {@link #beginChangeTracking()}.
	 */
	public static final String CHANGES_FILE = "changes.ef.bin";

	private static final ErrorType ERROR_FIELD_NOT_FOUND =
		ErrorType.withCode("index:update:field_not_found")
			.withArguments("name")
			.withMessage("Field `{{name}}` does not exist in index");

	private static final ErrorType ERROR_REQUIRED_FIELD_MISSING =
		ErrorType.withCode("index:update:required_field_missing")
			.withArguments("name")
			.withMessage("Required field `{{name}}` is missing");

	private static final ErrorType ERROR_LOCALE_NOT_ALLOWED =
		ErrorType.withCode("index:update:locale_not_allowed")
			.withArguments("name", "locale")
			.withMessage(
				"Field `{{name}}` is not locale specific, but the value carries locale `{{locale}}`"
			);

	private static final ErrorType ERROR_LOCALE_NOT_DECLARED =
		ErrorType.withCode("index:update:locale_not_declared")
			.withArguments("name", "locale")
			.withMessage(
				"Field `{{name}}` does not hold values in locale `{{locale}}`"
			);

	private static final ErrorType ERROR_NOT_MULTIPLE =
		ErrorType.withCode("index:update:not_multiple")
			.withArguments("name")
			.withMessage(
				"Field `{{name}}` holds a single value, but the document gives it several"
			);

	private static final ErrorType ERROR_NOT_MULTIPLE_IN_LOCALE =
		ErrorType.withCode("index:update:not_multiple_in_locale")
			.withArguments("name", "locale")
			.withMessage(
				"Field `{{name}}` holds a single value per locale, but the document gives it several in `{{locale}}`"
			);

	private static final ErrorType ERROR_NOT_A_DOCUMENT =
		ErrorType.withCode("index:update:not_a_document")
			.withArguments("name")
			.withMessage(
				"Field `{{name}}` holds documents, but the value given is not one"
			);

	private static final ErrorType ERROR_OBJECT_KEY_DUPLICATE =
		ErrorType.withCode("index:update:object:key_duplicate")
			.withArguments("name", "key", "value")
			.withMessage(
				"Field `{{name}}` names `{{key}}` as what tells its values apart, but the "
				+ "document gives two values reading `{{value}}` for it"
			);

	private static final ErrorType ERROR_UNEXPECTED_DOCUMENT =
		ErrorType.withCode("index:update:unexpected_document")
			.withArguments("name")
			.withMessage(
				"Field `{{name}}` does not hold documents, but the value given is one"
			);

	private static final ErrorType ERROR_FIELD_INSIDE_OBJECT =
		ErrorType.withCode("index:update:field_inside_object")
			.withArguments("name", "path")
			.withMessage(
				"Field `{{name}}` is inside the object `{{path}}`, which is where the document gives its value"
			);

	private static final ErrorType ERROR_PRIMARY_KEY_REQUIRED =
		ErrorType.withCode("index:update:primary_key_required")
			.withArguments("name")
			.withMessage(
				"Changing some of the fields of a document needs its primary key in `{{name}}`"
			);

	private static final ErrorType ERROR_UNSUPPORTED_SEARCH_LOCALE =
		ErrorType.withCode("index:query:unsupported_locale")
			.withArguments("locale")
			.withMessage(
				"The search asks for locale `{{locale}}` which this version of the engine does not support"
			);

	private static final ErrorType ERROR_HITS_FACET_UNSUPPORTED =
		ErrorType.withCode("index:query:hits:facet_unsupported")
			.withArguments("field", "path")
			.withMessage(
				"Hits standing for the values of `{{path}}` can count facets over "
					+ "fields of the index and fields inside the path, but `{{field}}` "
					+ "is inside another object"
			);

	private static final ErrorType ERROR_EXPLAIN_DOCUMENT_NOT_FOUND =
		ErrorType.withCode("index:explain:document_not_found")
			.withArguments("key")
			.withMessage(
				"No document is indexed under the key `{{key}}`, so there is nothing to explain"
			);

	private static final ErrorType ERROR_EXPLAIN_VALUE_NOT_FOUND =
		ErrorType.withCode("index:explain:value_not_found")
			.withArguments("key", "path", "index")
			.withMessage(
				"The document `{{key}}` has no value of `{{path}}` at position {{index}}"
			);

	private static final Log logger = Log.of(Index.class);

	private static final LocaleSupport DEFAULT_LOCALE_SUPPORT = Locales.getDefault();

	/**
	 * Stands for a document that was removed since the merge reader was opened,
	 * where the reader still answers with the document that was there.
	 */
	private static final byte[] DELETED = new byte[0];

	/**
	 * How many buckets the per-document locks are spread over. Two documents in
	 * the same bucket wait for each other, which costs nothing unless they are
	 * being updated at the same moment.
	 */
	private static final int DOCUMENT_LOCKS = 64;

	/**
	 * How much of the documents written since the merge reader was opened is
	 * held in memory before they are forgotten and the reader is reopened for
	 * the next partial update that needs one.
	 */
	private static final long MAX_PENDING_SOURCE_BYTES = 16L * 1024 * 1024;

	private final NodeState nodeState;

	private final String id;

	/**
	 * Name of the index this is a generation of, which is what ownership is
	 * held by - every generation of a name is written by the same node.
	 */
	private final String indexName;
	private final Path localPath;
	private final StateSync sync;

	/**
	 * Cache the stored fields of documents are read through, shared with every
	 * other index of the node - see {@link DocumentCache} for why it is one
	 * cache and what it holds.
	 */
	private final DocumentCache documentCache;

	private final ReadWriteLock syncLock;

	/**
	 * Gate every change to the contents passes through, separate from
	 * {@link #syncLock} so that holding writes still does not hold searches.
	 * Writes take the read side before the sync lock and a
	 * {@link #holdWrites() hold} takes the write side alone - a holder is free
	 * to commit or push while it holds the gate, which the other order would
	 * deadlock on.
	 */
	private final ReadWriteLock writeGate;

	/**
	 * Which documents have changed since tracking began, or {@code null} while
	 * nothing tracks them. Written under the write lock; read by the write
	 * paths under the read lock.
	 */
	private volatile ChangeLog changeLog;

	private final IndexSchema schema;

	/**
	 * How text is scored, which reads the schema for how much the length of a
	 * value counts against it. Held here so that every searcher and the writer
	 * score the same way, and so that a changed definition is picked up without
	 * anything being rebuilt.
	 */
	private final IndexSimilarity similarity;

	/*
	 * Read as well as written while only the read lock is held, as pushing and
	 * indexing both run there and both move the index between states.
	 */
	private volatile IndexState state;

	/**
	 * Guards the pair of {@link #state} and {@link #modifications} for the two
	 * places that touch them while holding the read lock: recording a change
	 * and a push deciding what the index is once it is done. Everything else
	 * that moves the state holds the write lock and so excludes both.
	 */
	private final Object stateLock;

	/**
	 * How many changes have been made to the contents of this index, counted so
	 * that a push can tell whether anything arrived while it ran. A push only
	 * uploads the files of the commit it started from, so a change that arrived
	 * after that leaves the index holding something the remote does not.
	 */
	private long modifications;

	/**
	 * What {@link #modifications} stood at when the last Lucene commit was
	 * taken, which is what a push of that commit carries.
	 */
	private volatile long committedModifications;

	private IndexDef definition;
	private String definitionVersion;

	/**
	 * Told after every push with the manifest version the push ended at, so
	 * that the version can be reported onward as a hint. {@code null} when
	 * nobody asked. Must return quickly - it is called while the push still
	 * holds the sync lock.
	 */
	private volatile LongConsumer pushListener;

	/**
	 * Major Lucene version the index was created with, as far as this node can
	 * tell. Empty until the index has been pulled, and for one that has nothing
	 * recording a version and no commit to read one from.
	 *
	 * Read outside the lock by {@link #getLuceneCompatibility()}, so that
	 * asking for the status of an index does not wait behind a pull.
	 */
	private volatile OptionalInt luceneCreatedMajor;

	/**
	 * What {@link #luceneCreatedMajor} was last found to mean, kept so that an
	 * index nearing the end of what Lucene reads is said once rather than on
	 * every pull that brings changes.
	 */
	private LuceneCompatibility reportedCompatibility;

	private FSDirectory directory;
	private IndexWriter writer;

	/**
	 * Keeps a commit that is being pushed from being deleted by a merge
	 * finishing while the push is still reading its files. Belongs to the
	 * writer it was created with, so it is replaced along with it.
	 */
	private SnapshotDeletionPolicy snapshots;

	private IndexReader reader;

	/**
	 * Whether the reader in use is the empty one opened for a directory that
	 * held no commit. The reader answers no hits and nothing about the
	 * directory changes when the first commit arrives, so the next pull reopens
	 * whether or not it brought anything itself.
	 */
	private volatile boolean readerWithoutCommit;

	private final IndexSearcherManager searcherManager;

	/**
	 * Finds the documents of the index among the values of object fields,
	 * caching the answer per segment. Held here so the cache lives as long as
	 * the index rather than a single search.
	 */
	private final BitSetProducer nestedParents;

	/**
	 * Runs the background work of this index - the expiry of searchers that
	 * have been replaced by a newer one, and the commits it makes on its own.
	 * Owned by this index so that closing it also stops the thread.
	 */
	private final ScheduledExecutorService maintenanceExecutor;

	/**
	 * Commits this index without being asked to, following the policy it was
	 * opened with.
	 */
	private final IndexCommitManager commitManager;

	/**
	 * Where commits, pushes and pulls are reported. Never null - an index
	 * outside a node gets {@link RequestMetrics#none()}.
	 */
	private final RequestMetrics metrics;

	/**
	 * Segment size in bytes under which Lucene merges segments of this index
	 * toward that size, ahead of its usual tiers. Empty leaves Lucene's
	 * default floor.
	 */
	private final OptionalLong mergeFloorSegment;

	/**
	 * Guards {@link #pendingSources}, {@link #mergeReader} and
	 * {@link #mergeReaderStale}, which a partial update reads and every write
	 * updates - all of them while only the read lock of the index is held.
	 */
	private final Object mergeLock;

	/**
	 * One lock per bucket of primary keys, held while a document is read and
	 * written back, so that two partial updates of the same document can not
	 * each merge into what was there before the other.
	 */
	private final ReentrantLock[] documentLocks;

	/**
	 * The stored copy of every document written since {@link #mergeReader} was
	 * opened, by primary key, holding {@link #DELETED} for one that was
	 * removed. What is not here is in the reader, which is what lets a partial
	 * update read the document it changes without the reader being reopened for
	 * every write.
	 */
	private final Map<BytesRef, byte[]> pendingSources;

	/**
	 * How much {@link #pendingSources} is holding. Growing past
	 * {@link #MAX_PENDING_SOURCE_BYTES} drops what is remembered rather than
	 * reopening there and then, so an index that is only written to never pays
	 * for a reader nothing reads.
	 */
	private long pendingSourceBytes;

	/**
	 * Reads what the writer holds, including what has not been committed. Only
	 * partial updates read it, and searches never do - a search answers from
	 * the last commit. Opened when a partial update first needs it.
	 */
	private DirectoryReader mergeReader;

	/**
	 * Whether the index has been written in a way {@link #pendingSources} could
	 * not record, so the reader has to be reopened before it is read again.
	 */
	private boolean mergeReaderStale;

	/**
	 * Open an index that only commits when it is asked to.
	 */
	public Index(
		NodeState nodeState,
		String name,
		Path localPath,
		StateSync sync
	) {
		this(nodeState, name, localPath, sync, CommitPolicy.disabled());
	}

	/**
	 * Open an index that reads stored fields straight from Lucene, without a
	 * document cache.
	 */
	public Index(
		NodeState nodeState,
		String name,
		Path localPath,
		StateSync sync,
		CommitPolicy commitPolicy
	) {
		this(nodeState, name, localPath, sync, commitPolicy, DocumentCache.disabled());
	}

	public Index(
		NodeState nodeState,
		String name,
		Path localPath,
		StateSync sync,
		CommitPolicy commitPolicy,
		DocumentCache documentCache
	) {
		this(nodeState, name, localPath, sync, commitPolicy, documentCache, RequestMetrics.none());
	}

	/**
	 * Open an index that reports what it does to a node's metrics.
	 *
	 * @param metrics
	 *   told about commits, pushes and pulls. {@link RequestMetrics#none()}
	 *   for an index opened outside a node
	 */
	public Index(
		NodeState nodeState,
		String name,
		Path localPath,
		StateSync sync,
		CommitPolicy commitPolicy,
		DocumentCache documentCache,
		RequestMetrics metrics
	) {
		this(
			nodeState,
			name,
			localPath,
			sync,
			commitPolicy,
			documentCache,
			metrics,
			OptionalLong.empty()
		);
	}

	/**
	 * @param mergeFloorSegment
	 *   segment size in bytes under which Lucene merges segments toward that
	 *   size, ahead of its usual tiers. Empty leaves Lucene's default floor
	 */
	public Index(
		NodeState nodeState,
		String name,
		Path localPath,
		StateSync sync,
		CommitPolicy commitPolicy,
		DocumentCache documentCache,
		RequestMetrics metrics,
		OptionalLong mergeFloorSegment
	) {
		this.metrics = metrics;
		this.mergeFloorSegment = mergeFloorSegment;
		this.nodeState = nodeState;
		this.id = name;
		this.indexName = IndexName.parse(name).index();
		this.localPath = localPath;
		this.sync = sync;
		this.documentCache = documentCache;

		this.schema = new IndexSchema();
		this.similarity = new IndexSimilarity(schema);
		this.syncLock = new ReentrantReadWriteLock();
		this.writeGate = new ReentrantReadWriteLock();
		this.stateLock = new Object();

		this.mergeLock = new Object();
		this.pendingSources = new HashMap<>();
		this.documentLocks = new ReentrantLock[DOCUMENT_LOCKS];
		for(var i = 0; i < documentLocks.length; i++) {
			this.documentLocks[i] = new ReentrantLock();
		}

		this.state = IndexState.NEEDS_PULL;

		this.definition = IndexDef.getDefaultInstance();
		this.definitionVersion = version(this.definition);

		this.luceneCreatedMajor = OptionalInt.empty();
		this.reportedCompatibility = LuceneCompatibility.UNKNOWN;

		this.maintenanceExecutor = Executors.newScheduledThreadPool(1);
		this.searcherManager =
			new IndexSearcherManager(Duration.ofMinutes(5), maintenanceExecutor);
		this.commitManager =
			new IndexCommitManager(this, maintenanceExecutor, commitPolicy, metrics);

		this.nestedParents = new QueryBitSetProducer(NestedDocuments.parentsQuery());
	}

	public String getId() {
		return id;
	}

	/**
	 * Get if this index is read-only, which is decided per name - this node
	 * may write some indexes and only read others.
	 *
	 * @return
	 */
	public boolean isReadOnly() {
		return !nodeState.isIndexer(indexName);
	}

	/**
	 * Get whether this index keeps a copy of each document as it was given.
	 *
	 * <p>Says what the definition asks for now rather than what every document
	 * in the index has: turning the copies on does not write them for the
	 * documents that are already there.
	 *
	 * @return
	 */
	public boolean isSourceStored() {
		return schema.isSourceStored();
	}

	/**
	 * Have a listener told after every push, with the manifest version the
	 * push ended at, which is how the version is reported onward as a hint.
	 * One listener at a time, set when the index is opened.
	 *
	 * @param listener
	 */
	public void onPushed(LongConsumer listener) {
		this.pushListener = listener;
	}

	/**
	 * Get the version of the manifest this node's copy was last synchronized
	 * at, whether it was pulled or pushed. Empty when nothing recorded one.
	 * A copy already at the version a writer reported has no reason to ask
	 * the remote for the manifest.
	 *
	 * @return
	 */
	public OptionalLong getSyncedManifestVersion() {
		return sync.syncedVersion();
	}

	/**
	 * Pull changes to this index from the remote.
	 */
	public void pull() {
		IndexState startState;

		syncLock.writeLock().lock();
		try {
			if(state == IndexState.PULLING) {
				/*
				 * A pull is already running and will end with the index holding
				 * whatever the remote has, which is what this call is after.
				 */
				return;
			}

			if(state == IndexState.CLOSED) {
				/*
				 * Pulling would reopen the Lucene directory on an instance that
				 * has been retired, next to the instance that replaced it.
				 */
				return;
			}

			if(!isReadOnly() && state != IndexState.NEEDS_PULL) {
				/*
				 * When an index is not read-only it will only be automatically
				 * pulled when it is first created.
				 */
				return;
			}

			startState = state;
			state = IndexState.PULLING;
		} finally {
			syncLock.writeLock().unlock();
		}

		/*
		 * Perform the pull outside the lock so normal operations can continue
		 * while synchronization is in progress.
		 */
		var started = System.nanoTime();
		boolean hasChanges;
		try {
			hasChanges = this.sync.pull();
			metrics.recordPull(System.nanoTime() - started, true);

			if(startState == IndexState.NEEDS_PULL || readerWithoutCommit) {
				// At start no changes may be pulled but an out of date index
				// should be treated as having changes
				hasChanges = true;
			}
		} catch(SyncIncompatibleException e) {
			metrics.recordPull(System.nanoTime() - started, false);

			/*
			 * The refusal happened before anything was downloaded, so trying
			 * again costs no more than an up to date index does and the index
			 * recovers on its own if the remote is ever replaced. What it does
			 * not do is get better with time, so it is said once rather than
			 * every interval for as long as the index exists.
			 */
			syncLock.writeLock().lock();
			try {
				if(state == IndexState.CLOSED) {
					return;
				}

				if(state != IndexState.INCOMPATIBLE) {
					logger.atError()
						.addKeyValue("index", id)
						.addKeyValue("luceneCreatedMajor", e.getCreatedMajor())
						.log(
							"Index can not be read by this build; " + e.getMessage()
								+ ". Reindex the documents into a new index to recover them"
						);
				}

				state = IndexState.INCOMPATIBLE;
			} finally {
				syncLock.writeLock().unlock();
			}
			return;
		} catch(IOException e) {
			if(e instanceof SyncConflictException) {
				metrics.recordConflict("pull");
			}

			metrics.recordPull(System.nanoTime() - started, false);

			logger.atError()
				.addKeyValue("index", id)
				.setCause(e)
				.log("Failed to pull index; " + e.getMessage());

			/*
			 * A failed pull leaves the local copy as it was, so the index goes
			 * back to the state it was in rather than staying halfway through a
			 * pull that nothing will finish. Unless the index was closed while
			 * the pull ran - a closed instance stays closed.
			 */
			syncLock.writeLock().lock();
			try {
				if(state != IndexState.CLOSED) {
					state = startState;
				}
			} finally {
				syncLock.writeLock().unlock();
			}
			return;
		}

		syncLock.writeLock().lock();
		try {
			if(state == IndexState.CLOSED) {
				/*
				 * Closed while the pull ran. The pulled files are on disk for
				 * the instance that replaces this one, but nothing may be
				 * opened here anymore.
				 */
				return;
			}

			if(!hasChanges) {
				// No changes were pulled
				state = IndexState.USABLE;
				return;
			}

			var def = readDefinition();
			if(def == null) {
				// Failed to read the definition, the index is in an invalid state
				state = IndexState.NEEDS_PULL; // TODO: State for invalid index?
				return;
			}

			try {
				schema.setDefinition(def);
			} catch(ValidationException e) {
				/*
				 * The definition was written by a version of the engine that
				 * can do something this one can not. Opening the index anyway
				 * would index and answer without whatever is missing, so it is
				 * left closed until this node is upgraded and pulls again.
				 */
				logger.atError()
					.addKeyValue("index", id)
					.log("Cannot use index definition; " + e.getMessage());

				state = IndexState.UNSUPPORTED;
				return;
			}

			this.definition = def;
			this.definitionVersion = version(def);

			if(this.directory == null) {
				this.directory = FSDirectory.open(localPath);
			}

			/*
			 * Checked before the writer or the reader is opened, as opening
			 * either is what Lucene refuses on, and it does so with a plain
			 * IOException that would send the index back to being pulled again.
			 */
			if(!refreshLuceneCompatibility().isReadable()) {
				state = IndexState.INCOMPATIBLE;
				return;
			}

			/*
			 * Opened from the writer that is about to go, and what it says is
			 * about local state the pulled files replace either way.
			 */
			closeMergeReader();

			/*
			 * The in-memory log belongs to the state the pulled files replace.
			 * Kept, a node that loses and regains an index would resume from
			 * it instead of the pulled log file - whoever tracks loads the
			 * file again through beginChangeTracking.
			 */
			this.changeLog = null;

			if(this.writer != null) {
				/*
				 * The pulled files are the state to continue from, so anything
				 * the previous writer had not committed is dropped along with
				 * it. A writer also holds the lock on the directory, so the
				 * next one can only be opened once this one is gone.
				 */
				this.writer.rollback();
				this.writer = null;
				this.snapshots = null;
			}

			if(isReadOnly()) {
				/*
				 * An index that has never been committed has no segments for
				 * Lucene to open - a definition is written and pushed before
				 * any document is indexed, so a node that is not the indexer
				 * sees this for every index between its creation and its first
				 * commit. It holds no documents rather than being broken, so it
				 * is read through a reader with nothing in it and answers
				 * searches with no hits until a commit arrives to open.
				 */
				if(DirectoryReader.indexExists(directory)) {
					this.reader = DirectoryReader.open(directory);
					this.readerWithoutCommit = false;
				} else {
					this.reader = new MultiReader();
					this.readerWithoutCommit = true;
				}
			} else {
				// This is a writeable index, reopen the writer
				this.snapshots =
					new SnapshotDeletionPolicy(new KeepOnlyLastCommitDeletionPolicy());

				var config = new IndexWriterConfig(new StandardAnalyzer());
				config.setIndexDeletionPolicy(snapshots);
				config.setCodec(new IndexCodec(schema));
				config.setSimilarity(similarity);

				if(mergeFloorSegment.isPresent()) {
					/*
					 * Every commit flushes a segment, and frequent commits flush
					 * small ones. A raised floor merges them into floor-sized
					 * segments sooner, so a push carries fewer small files.
					 */
					var mergePolicy = new TieredMergePolicy();
					mergePolicy.setFloorSegmentMB(
						mergeFloorSegment.getAsLong() / (double) (1 << 20)
					);
					config.setMergePolicy(mergePolicy);
				}

				this.writer = new IndexWriter(directory, config);
				this.reader = DirectoryReader.open(writer);
				this.readerWithoutCommit = false;
			}

			/*
			 * The searcher manager takes over the reader that was in use, and
			 * closes it once the searches still holding it are done.
			 */
			searcherManager.refreshLatest(newSearcher(reader));

			state = IndexState.USABLE;
		} catch(IOException e) {
			logger.atError()
				.addKeyValue("index", id)
				.setCause(e)
				.log("Failed to reopen index after pull; " + e.getMessage());

			/*
			 * The local files have been updated but could not be opened, which
			 * pulling them again is the only way out of. Leaving the index in
			 * PULLING would mean nothing ever tries.
			 */
			state = IndexState.NEEDS_PULL;
		} finally {
			syncLock.writeLock().unlock();
		}
	}

	/**
	 * Open a searcher over a reader: it scores with the similarity of this
	 * index, and it stops collecting when the thread searching has run out of
	 * time.
	 *
	 * @see SearchDeadline
	 */
	private IndexSearcher newSearcher(IndexReader reader) {
		var searcher = new IndexSearcher(reader);
		searcher.setSimilarity(similarity);

		/*
		 * One searcher answers many requests at once, so the timeout reads the
		 * budget of the thread that collects. Setting a deadline here would
		 * bound every request from the moment the searcher opened.
		 */
		searcher.setTimeout(SearchDeadline.INSTANCE);

		return searcher;
	}

	/**
	 * Reopen this index to match whether this node may write to it, dropping
	 * anything uncommitted and taking the remote state as what to continue
	 * from. Called when the node gains or loses the indexer role, which
	 * changes which mode the Lucene directory has to be open in. An index
	 * already open in the mode the node holds it in is left as it is.
	 */
	public void reopen() {
		reopen(false);
	}

	/**
	 * Reopen this index to match whether this node may write to it, taking
	 * the remote state as what to continue from. An index already open in the
	 * mode the node holds it in is left as it is.
	 *
	 * @param flushFirst
	 *   whether an index being reopened out of writing commits and pushes
	 *   what it holds before the reopen, instead of it being dropped - for a
	 *   handover this node chose, where the remote is still its to write. A
	 *   flush that fails gives the changes up and the reopen continues.
	 */
	public void reopen(boolean flushFirst) {
		boolean flush;

		syncLock.writeLock().lock();
		try {
			if(state == IndexState.PULLING) {
				/*
				 * The pull that is running decides how to open the index when
				 * it finishes, and reads the node state at that point.
				 */
				return;
			}

			if(state == IndexState.CLOSED) {
				// A closed instance is not brought back
				return;
			}

			var shouldWrite = !isReadOnly();
			if(state != IndexState.NEEDS_PULL && shouldWrite == (writer != null)) {
				/*
				 * Already open the way the node holds it. Skipped rather than
				 * reopened so that an ownership change about other indexes -
				 * or one already applied - does not roll a writer back.
				 */
				return;
			}

			flush = flushFirst
				&& writer != null
				&& !shouldWrite
				&& state != IndexState.USABLE;
		} finally {
			syncLock.writeLock().unlock();
		}

		if(flush) {
			/*
			 * Documents answered but not yet committed would otherwise be
			 * dropped by the pull below - a handover is deliberate, so they
			 * are put where the successor will pull them from.
			 */
			try {
				commitChanges(PushReason.HANDOVER);
			} catch(IOException | RuntimeException e) {
				logger.atWarn()
					.addKeyValue("index", id)
					.setCause(e)
					.log(
						"Could not push before handing the index over, giving up"
							+ " its unpushed changes; " + e.getMessage()
					);
			}
		}

		syncLock.writeLock().lock();
		try {
			if(state == IndexState.PULLING || state == IndexState.CLOSED) {
				return;
			}

			state = IndexState.NEEDS_PULL;
		} finally {
			syncLock.writeLock().unlock();
		}

		pull();
	}

	/**
	 * Why a push is being made, which is what decides whether the node having
	 * stopped holding the index stops it.
	 */
	private enum PushReason {
		/**
		 * The ordinary work of a node that holds the index.
		 */
		HELD,

		/**
		 * A handover this node chose, or an instance on its way out. The node
		 * has already stopped holding the index by the time such a flush runs
		 * - that order is what keeps a successor from pulling before the flush
		 * lands - so what the index holds is pushed whatever the node state
		 * says now.
		 */
		HANDOVER
	}

	/**
	 * Push what the index holds to the remote.
	 *
	 * @param reason
	 *   why the push is being made, see {@link PushReason}
	 * @throws IndexReadonlyException
	 *   if the node stopped holding the index while the commit ran, where the
	 *   push was the ordinary work of holding it
	 * @throws SyncConflictException
	 *   if the remote was written by another node
	 * @throws IOException
	 */
	private void sync(PushReason reason) throws IOException {
		var started = System.nanoTime();
		var pushed = false;

		syncLock.readLock().lock();
		try {
			if(state == IndexState.CLOSED) {
				/*
				 * Closed between the commit and this push. There is no snapshot
				 * to read anymore, and pushing without one would replace the
				 * remote manifest with one that lists no files.
				 */
				return;
			}

			/*
			 * Asked again here rather than taken from where the commit started.
			 * A claim that lapses is noticed a round later, and by then the
			 * successor has taken the index over from the manifest the remote
			 * holds - so a push from here can still win the manifest race and
			 * leave the successor giving up documents it has already answered
			 * for. What is here is dropped instead, which the pull that follows
			 * the loss does.
			 */
			if(reason == PushReason.HELD && isReadOnly()) {
				throw new IndexReadonlyException(id);
			}

			/*
			 * Saying the index is being pushed for as long as it is keeps a
			 * listing that stops naming the index meanwhile from taking the
			 * local copy with it. Left alone for an index that is not in a
			 * state a push says anything about, so that a pull it is waiting
			 * for is not forgotten.
			 */
			var tracked = state.canModifyContents();
			if(tracked) {
				state = IndexState.PUSHING;
			}

			/*
			 * The commit is held for as long as the push runs, so that a merge
			 * finishing in the meantime can not delete a file that is still
			 * being uploaded.
			 */
			var commit = snapshotLatestCommit();
			try {
				sync.push(indexFiles(commit));
			} finally {
				if(commit != null) {
					snapshots.release(commit);
				}
			}

			if(tracked) {
				synchronized(stateLock) {
					/*
					 * A push carries the commit it started from, so anything
					 * indexed while it ran is still only here - and a
					 * definition can be pushed while documents are waiting for
					 * a commit. Either way the index goes on saying it holds
					 * changes rather than being in step with the remote.
					 */
					if(state == IndexState.PUSHING) {
						state = modifications == committedModifications
							? IndexState.USABLE
							: IndexState.MODIFIED;
					}
				}
			}

			pushed = true;

			var listener = pushListener;
			if(listener != null) {
				sync.syncedVersion().ifPresent(listener::accept);
			}
		} catch(SyncConflictException e) {
			metrics.recordConflict("push");

			logger.atError()
				.addKeyValue("index", id)
				.log("Another node has changed the remote index; " + e.getMessage());

			/*
			 * The remote has moved on without this node, which under a single
			 * indexer means a second writer is running. Nothing was
			 * overwritten and the remote is what to continue from, so local
			 * changes are given up and the next refresh pulls it over.
			 */
			state = IndexState.NEEDS_PULL;
			throw e;
		} finally {
			syncLock.readLock().unlock();
			metrics.recordPush(System.nanoTime() - started, pushed);
		}
	}

	/**
	 * Take a snapshot of the newest commit, so that the files it is made up of
	 * stay in place until it is released.
	 *
	 * @return
	 *   the commit, or {@code null} when nothing has been committed yet and
	 *   there is no commit to hold
	 * @throws IOException
	 */
	private IndexCommit snapshotLatestCommit() throws IOException {
		if(snapshots == null || !DirectoryReader.indexExists(directory)) {
			return null;
		}

		return snapshots.snapshot();
	}

	/**
	 * The files that make up the index, being those of a commit together with
	 * the definition that says how to read them. Files Lucene is still writing
	 * belong to a commit that does not exist yet and are left out, as another
	 * node has no use for them.
	 *
	 * @param commit
	 *   commit to take the files of, or {@code null} for an index that has not
	 *   been committed yet
	 * @return
	 * @throws IOException
	 */
	private Set<String> indexFiles(IndexCommit commit) throws IOException {
		var files = new LinkedHashSet<String>();

		if(Files.exists(localPath.resolve(DEFINITION_FILE))) {
			files.add(DEFINITION_FILE);
		}

		/*
		 * Carried whether or not this instance is tracking, so a log written
		 * by an earlier writer survives ownership moving until whoever tracks
		 * resumes or ends it.
		 */
		if(Files.exists(localPath.resolve(CHANGES_FILE))) {
			files.add(CHANGES_FILE);
		}

		if(commit != null) {
			files.addAll(commit.getFileNames());
		}

		return files;
	}

	/**
	 * Get the current state of the index.
	 *
	 * @return
	 */
	public IndexState getState() {
		syncLock.readLock().lock();
		try {
			return state;
		} finally {
			syncLock.readLock().unlock();
		}
	}

	/**
	 * Get how many changed documents are waiting for a commit. Always zero on
	 * a node that does not write the index.
	 *
	 * @return
	 */
	public long getPendingChanges() {
		return commitManager.pendingChanges();
	}

	/**
	 * Get how long the oldest change waiting for a commit has waited. This is
	 * how far behind a search on another node can be, since nothing reaches a
	 * reader before it is committed.
	 *
	 * @return
	 *   {@link Duration#ZERO} when nothing is waiting
	 */
	public Duration getPendingAge() {
		return commitManager.getPendingAge();
	}

	/**
	 * Get whether the Lucene writer holds anything its last commit did not
	 * take. Merges finishing in the background put the writer in this state
	 * without any document changing, and only a commit takes it out again.
	 *
	 * @return
	 *   {@code false} on a node that does not write the index
	 */
	public boolean hasUncommittedLuceneChanges() {
		syncLock.readLock().lock();
		try {
			return writer != null && writer.hasUncommittedChanges();
		} finally {
			syncLock.readLock().unlock();
		}
	}

	/**
	 * Get whether Lucene is merging segments of this index or holds merges
	 * waiting to run. A merge that finishes leaves the writer with
	 * {@link #hasUncommittedLuceneChanges() uncommitted changes}.
	 *
	 * @return
	 *   {@code false} on a node that does not write the index
	 */
	public boolean hasPendingMerges() {
		syncLock.readLock().lock();
		try {
			return writer != null && writer.hasPendingMerges();
		} finally {
			syncLock.readLock().unlock();
		}
	}

	/**
	 * Work out which Lucene version created this index and what that means for
	 * how much longer it can be read, saying so when the answer has changed.
	 *
	 * Called while the write lock is held, from the part of a pull that has the
	 * local files in place but has not opened them yet.
	 *
	 * @return
	 */
	private LuceneCompatibility refreshLuceneCompatibility() {
		/*
		 * What the synchronization recorded is preferred, as it is the answer
		 * for the index rather than for the copy that happens to be on this
		 * disk. Reading the segments is what covers an index synchronized
		 * before the version was recorded, and one that is not synchronized at
		 * all.
		 */
		var createdMajor = sync.luceneCreatedMajor();
		if(createdMajor.isEmpty()) {
			createdMajor = readLuceneCreatedMajor();
		}

		this.luceneCreatedMajor = createdMajor;

		var compatibility = LuceneCompatibility.of(createdMajor);
		if(compatibility == reportedCompatibility) {
			return compatibility;
		}

		this.reportedCompatibility = compatibility;

		if(compatibility == LuceneCompatibility.ENDING) {
			logger.atWarn()
				.addKeyValue("index", id)
				.addKeyValue("luceneCreatedMajor", createdMajor.getAsInt())
				.log(
					"Index was created with Lucene " + createdMajor.getAsInt()
						+ ".x, the oldest this build reads. Reindex it before"
						+ " upgrading this node across a Lucene major"
				);
		} else if(compatibility == LuceneCompatibility.UNREADABLE) {
			logger.atError()
				.addKeyValue("index", id)
				.addKeyValue("luceneCreatedMajor", createdMajor.getAsInt())
				.log(
					"Index was created with Lucene " + createdMajor.getAsInt()
						+ ".x, which this build can no longer read. Reindex the"
						+ " documents into a new index to recover them"
				);
		}

		return compatibility;
	}

	/**
	 * Read which major Lucene version created the local files, or empty when
	 * there is no commit to read it from or it could not be read.
	 *
	 * @return
	 */
	private OptionalInt readLuceneCreatedMajor() {
		try {
			/*
			 * Read without a minimum supported version, so that an index too
			 * old to open still answers which version made it - refusing is
			 * what the answer is for.
			 */
			return OptionalInt.of(
				SegmentInfos.readLatestCommit(directory, 0).getIndexCreatedVersionMajor()
			);
		} catch(IndexNotFoundException e) {
			// Nothing has been committed yet, so no version has been decided
			return OptionalInt.empty();
		} catch(IOException e) {
			logger.atWarn()
				.addKeyValue("index", id)
				.setCause(e)
				.log("Could not read which Lucene version created the index; " + e.getMessage());

			return OptionalInt.empty();
		}
	}

	/**
	 * Get how much longer the Lucene files of this index can be read.
	 *
	 * The version an index was created with never changes, but what reads it
	 * does - an index that is {@link LuceneCompatibility#ENDING} has to be
	 * reindexed before this node is upgraded across a Lucene major, or it
	 * becomes unreadable.
	 *
	 * @return
	 */
	public LuceneCompatibility getLuceneCompatibility() {
		return LuceneCompatibility.of(luceneCreatedMajor);
	}

	/**
	 * Get the major Lucene version this index was created with, or empty when
	 * nothing recorded one and there is no commit to read it from.
	 *
	 * @return
	 */
	public OptionalInt getLuceneCreatedMajor() {
		return luceneCreatedMajor;
	}

	private IndexDef readDefinition() {
		try(var in = Files.newInputStream(localPath.resolve(DEFINITION_FILE))) {
			return IndexDef.parseFrom(in);
		} catch(NoSuchFileException e) {
			// The index is new and has no definition
			return IndexDef.getDefaultInstance();
		} catch(IOException e) {
			logger.atError()
				.addKeyValue("index", id)
				.setCause(e)
				.log("Failed to read index definition; " + e.getMessage());
			return null;
		}
	}

	/**
	 * Get the current definition of the index.
	 *
	 * @return
	 */
	public IndexDef getDefinition() {
		syncLock.readLock().lock();
		try {
			return definition;
		} finally {
			syncLock.readLock().unlock();
		}
	}

	/**
	 * Get the version of the current definition. The version is derived from
	 * the contents of the definition, so two indexes with the same definition
	 * have the same version.
	 *
	 * Used to detect concurrent modifications, see
	 * {@link #updateDefinition(IndexDef, String)}.
	 *
	 * @return
	 */
	public String getDefinitionVersion() {
		syncLock.readLock().lock();
		try {
			return definitionVersion;
		} finally {
			syncLock.readLock().unlock();
		}
	}

	/**
	 * Update the definition of the index.
	 *
	 * @param def
	 * @throws IndexDefinitionIncompatibleException
	 *   if the index holds documents that were not indexed under {@code def}
	 * @throws IOException
	 */
	public void updateDefinition(IndexDef def) throws IOException {
		updateDefinition(def, null, false);
	}

	/**
	 * Update the definition of the index, optionally only if the current
	 * definition has the expected version.
	 *
	 * @param def
	 * @param expectedVersion
	 *   version the current definition is expected to have, or {@code null} to
	 *   update no matter the current version
	 * @throws IndexDefinitionIncompatibleException
	 *   if the index holds documents that were not indexed under {@code def}
	 * @throws IOException
	 */
	public void updateDefinition(IndexDef def, String expectedVersion) throws IOException {
		updateDefinition(def, expectedVersion, false);
	}

	/**
	 * Update the definition of the index, optionally only if the current
	 * definition has the expected version. The check and the update are
	 * performed atomically, so a caller that read a definition and its version
	 * can update it without racing another caller.
	 *
	 * <p>A definition that reaches nothing already indexed is refused while the
	 * index holds documents, which is what keeps a definition and the documents
	 * under it saying the same thing. Which differences those are, and why the
	 * alternative is a search quietly answering with less than it should, is in
	 * {@link DefinitionCompatibility}; the way through is a new generation. The
	 * count is read here rather than by the caller because the write lock is
	 * what makes it and the definition one answer - a document indexed between
	 * the two would be indexed under a definition that was about to be replaced.
	 *
	 * @param def
	 * @param expectedVersion
	 *   version the current definition is expected to have, or {@code null} to
	 *   update no matter the current version
	 * @param allowStaleDocuments
	 *   {@code true} to store the definition even where the documents already
	 *   indexed were not indexed under it, leaving them answering as they were
	 *   until they are indexed again
	 * @throws IndexDefinitionIncompatibleException
	 *   if the index holds documents that were not indexed under {@code def}
	 *   and {@code allowStaleDocuments} is {@code false}
	 * @throws IOException
	 */
	public void updateDefinition(
		IndexDef def,
		String expectedVersion,
		boolean allowStaleDocuments
	) throws IOException {
		syncLock.writeLock().lock();
		try {
			if(state == IndexState.CLOSED) {
				throw new IndexClosedException(id);
			}

			if(isReadOnly()) {
				throw new IndexReadonlyException(id);
			}

			if(!state.canModifyContents()) {
				/*
				 * Modifying an index that is out of date would result in an
				 * inconsistent state.
				 *
				 * An index should be pulled before being modified.
				 */
				throw new IndexOutOfDateException(id, state);
			}

			if(expectedVersion != null && !expectedVersion.equals(definitionVersion)) {
				throw new IndexVersionMismatchException(id, expectedVersion, definitionVersion);
			}

			if(!allowStaleDocuments && holdsDocuments()) {
				var incompatibilities = DefinitionCompatibility.check(this.definition, def);
				if(!incompatibilities.isEmpty()) {
					throw new IndexDefinitionIncompatibleException(incompatibilities);
				}
			}

			/*
			 * The highlight layout is the index's to keep rather than the
			 * caller's to send, so it is carried over from the definition being
			 * replaced - see IndexSchema.resolveHighlightLayout.
			 */
			var layout = IndexSchema.resolveHighlightLayout(def, this.definition);
			if(layout != IndexDef.HighlightLayout.HIGHLIGHT_LAYOUT_UNSPECIFIED) {
				def = def.toBuilder().setHighlightLayout(layout).build();
			}

			/*
			 * Record what the definition needs, so that a node without one of
			 * those features can tell rather than indexing without it.
			 */
			var described = IndexFeatures.describe(withDefaults(def));

			schema.setDefinition(described);

			Files.write(localPath.resolve(DEFINITION_FILE), described.toByteArray());
			this.definition = described;
			this.definitionVersion = version(described);
			sync(PushReason.HELD);
		} finally {
			syncLock.writeLock().unlock();
		}
	}

	/**
	 * Get whether anything is indexed here that a definition change could
	 * leave behind.
	 *
	 * Counted from the writer rather than from a searcher, so a document
	 * indexed but not committed yet counts - it was written under the
	 * definition that is about to be replaced the same way a committed one was.
	 * Child documents count too: a value written as its own unit is as much a
	 * thing that was analyzed as the document holding it.
	 *
	 * Only called with the write lock held, which is what keeps the answer true
	 * for as long as it is acted on.
	 *
	 * @return
	 */
	private boolean holdsDocuments() {
		/*
		 * A writable index has a writer from the moment it is pulled, so a null
		 * one here is an index nothing has been written to.
		 */
		return writer != null && writer.getDocStats().numDocs > 0;
	}

	/**
	 * Fill in the parts of a definition the caller left to the engine.
	 *
	 * Most defaults stay unset, so that what they mean is decided when they are
	 * read and one of them can be reconsidered later. This one is written out
	 * instead, because it decides what goes on disk for every document indexed
	 * from here on - an index that was created keeping its documents should go
	 * on keeping them, whatever a later version would have chosen for a new
	 * one.
	 *
	 * @param def
	 * @return
	 */
	private static IndexDef withDefaults(IndexDef def) {
		if(def.hasSource()) {
			return def;
		}

		return def.toBuilder()
			.setSource(IndexDef.SourceMode.SOURCE_MODE_FULL)
			.build();
	}

	/**
	 * Calculate the version of a definition. Uses deterministic serialization
	 * so that the map of fields does not affect the result.
	 *
	 * @param def
	 * @return
	 */
	private static String version(IndexDef def) {
		try {
			var bytes = new ByteArrayOutputStream();
			var out = CodedOutputStream.newInstance(bytes);
			out.useDeterministicSerialization();
			def.writeTo(out);
			out.flush();

			var digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
			return HexFormat.of().formatHex(digest, 0, 16);
		} catch(IOException | NoSuchAlgorithmException e) {
			throw new IllegalStateException("Unable to calculate version of definition", e);
		}
	}

	/**
	 * Get the fields available in this index.
	 *
	 * @return
	 */
	public ImmutableList<Field> getFields() {
		return schema.getFields();
	}

	/**
	 * Get a field of this index by the name a value would be given to,
	 * resolving the wildcard patterns the definition declares.
	 *
	 * @param name
	 * @return
	 *   the field, or empty when the definition has nothing that name belongs
	 *   to
	 */
	public Optional<Field> getField(String name) {
		return schema.getField(name);
	}

	/**
	 * Get a field inside an object field, by the dotted path through it - the
	 * field {@code price} inside the object {@code variants} is
	 * {@code variants.price}. Fields at the root are found through
	 * {@link #getField(String)}, never here.
	 *
	 * @param path
	 * @return
	 *   the field, or empty when no object of the definition holds that path
	 */
	public Optional<IndexSchema.NestedField> getNestedField(String path) {
		return schema.getNestedField(path);
	}

	/**
	 * Refuse a change to the contents of this index that can not be made right
	 * now. Called while holding the lock the change is made under, as the
	 * answer only holds for as long as it is held.
	 *
	 * @throws IndexClosedException
	 *   if this instance has been closed
	 * @throws IndexReadonlyException
	 *   if this node is not the indexer
	 * @throws IndexOutOfDateException
	 *   if the index is being synchronized, where writing would leave the local
	 *   copy and the remote disagreeing about what the index holds
	 */
	private void checkModifiable() {
		if(state == IndexState.CLOSED) {
			throw new IndexClosedException(id);
		}

		if(isReadOnly()) {
			throw new IndexReadonlyException(id);
		}

		if(!state.canModifyContents()) {
			throw new IndexOutOfDateException(id, state);
		}

		if(writer == null) {
			/*
			 * The node may write the index but the directory is still open the
			 * read-only way - the node took the index moments ago and the
			 * reopen has not finished. Refused as out of date so the caller
			 * retries, rather than reaching the writer that is not there.
			 */
			throw new IndexOutOfDateException(id, state);
		}
	}

	/**
	 * Record that the contents of the index have changed: the index holds
	 * something the remote does not until the next push, the change counts
	 * towards the next commit this index makes on its own, and towards the
	 * write load the node sheds indexes by.
	 *
	 * @param changes
	 *   how many documents the change covered
	 */
	private void markModified(long changes) {
		synchronized(stateLock) {
			modifications += changes;
			state = IndexState.MODIFIED;
		}

		commitManager.recordChange(changes);
		nodeState.recordWrite(indexName, changes);
	}

	/**
	 * Add a document to the index.
	 *
	 * @param doc
	 * @throws IOException
	 */
	public void addDocument(Document doc) throws IOException {
		writeGate.readLock().lock();
		syncLock.readLock().lock();
		try {
			checkModifiable();

			var luceneDoc = new org.apache.lucene.document.Document();

			var errors = Lists.mutable.<ErrorMessage>empty();
			var encounter = new IndexEncounterImpl(schema.getResources(), schema.isHighlightingInPostings());

			// Keep track of fields found so that all required fields are present
			var fieldsFound = Sets.mutable.<String>empty();

			/*
			 * Which field every value so far was given to, counted per locale:
			 * a locale specific field holds one variant per locale, so a value
			 * per translation is one value each rather than several. Counted
			 * under the name the value arrived with, so the concrete fields a
			 * wildcard pattern stands for are each their own count.
			 */
			var valuesSeen = Sets.mutable.<Pair<String, String>>empty();
			Term primaryKeyTerm = null;

			/*
			 * The values given to locale specific fields, kept per locale so
			 * that the locales the document left empty can be filled from the
			 * ones it did give - which is only known once every value has been
			 * seen. Left null when the index fills nothing, so a document
			 * costs nothing for a feature the index does not use.
			 */
			var localized = schema.hasLocaleFallback()
				? Maps.mutable.<String, LocalizedValues>empty()
				: null;

			/*
			 * The values of nested object fields become Lucene documents of
			 * their own, collected here and written in one block with the
			 * document at the end - which is what lets a search ask about one
			 * value at a time. Flattened objects write into the document
			 * itself and never land here.
			 */
			var childDocs = Lists.mutable.<org.apache.lucene.document.Document>empty();
			var childCounts = Maps.mutable.<String, Integer>empty();

			/*
			 * What every value of a keyed object field read for its key, so a
			 * document giving two values the same one is refused. Nothing else
			 * checks, and every place that names a value by its key relies on
			 * the name reaching one value.
			 */
			var keysSeen = Sets.mutable.<Pair<String, String>>empty();

			for(Document.Value value : doc.fields()) {
				var field = schema.getField(value.name());
				if(field.isEmpty()) {
					errors.add(
						ERROR_FIELD_NOT_FOUND.toMessage(
							ObjectLocation.root().forField(value.name()),
							"name",
							value.name()
						)
					);
					continue;
				}

				/*
				 * A flattened path resolves to a field, but a document gives
				 * its value inside the object - refused so there is one way to
				 * write a thing, and so the counting of values per field never
				 * has to reconcile two spellings of the same one.
				 */
				var enclosingObject = schema.getFlattenedObjectOf(value.name());
				if(enclosingObject.isPresent()) {
					errors.add(
						ERROR_FIELD_INSIDE_OBJECT.toMessage(
							ObjectLocation.root().forField(value.name()),
							"name", value.name(),
							"path", enclosingObject.get()
						)
					);
					continue;
				}

				var field0 = field.get();
				var type = field0.getType();

				if(field0.isObject()) {
					if(value.locale() != null) {
						errors.add(
							ERROR_LOCALE_NOT_ALLOWED.toMessage(
								ObjectLocation.root().forField(value.name()),
								"name", value.name(),
								"locale", value.locale()
							)
						);
						continue;
					}

					if(!valuesSeen.add(Tuples.pair(value.name(), (String) null))
						&& !field0.isMultiple()) {
						errors.add(
							ERROR_NOT_MULTIPLE.toMessage(
								ObjectLocation.root().forField(value.name()),
								"name", value.name()
							)
						);
						continue;
					}

					var position = childCounts.merge(value.name(), 1, Integer::sum) - 1;

					if(!(value.value() instanceof Document subDocument)) {
						errors.add(
							ERROR_NOT_A_DOCUMENT.toMessage(
								ObjectLocation.root().forField(value.name()).forIndex(position),
								"name", value.name()
							)
						);
						continue;
					}

					if(duplicateObjectKey(
						value.name(), value.name(), field0, subDocument,
						ObjectLocation.root().forField(value.name()).forIndex(position),
						keysSeen, errors
					)) {
						continue;
					}

					if(field0.isNestedObject()) {
						childDocs.add(
							childDocument(
								field0,
								value.name(),
								subDocument,
								ObjectLocation.root().forField(value.name()).forIndex(position),
								encounter,
								childDocs,
								keysSeen,
								errors
							)
						);
					} else {
						indexObjectValue(
							field0,
							value.name(),
							subDocument,
							ObjectLocation.root().forField(value.name()).forIndex(position),
							encounter,
							luceneDoc,
							childDocs,
							null,
							keysSeen,
							errors
						);
					}

					fieldsFound.add(value.name());
					continue;
				}

				if(value.value() instanceof Document) {
					errors.add(
						ERROR_UNEXPECTED_DOCUMENT.toMessage(
							ObjectLocation.root().forField(value.name()),
							"name", value.name()
						)
					);
					continue;
				}

				/*
				 * Analysis and collation follow the locale of each value, so it
				 * is resolved per value rather than per document. A field that
				 * is not locale specific holds one variant, so a value carrying
				 * a locale there says something the field can not keep; one
				 * that is refuses locales it never declared, because indexing
				 * them would write variants nothing ever searches.
				 */
				String tag = null;
				if(field0.isLocaleSpecific()) {
					if(value.locale() == null) {
						tag = field0.getDefaultLocale();
					} else {
						/*
						 * Matched as closely as the declared locales tell
						 * apart rather than exactly, so a value arriving as
						 * `nb-NO` lands in the `no` variant of a field that
						 * holds Norwegian as one language.
						 */
						var resolved = field0.resolveLocale(value.locale());
						if(resolved.isEmpty()) {
							errors.add(
								ERROR_LOCALE_NOT_DECLARED.toMessage(
									ObjectLocation.root().forField(value.name()),
									"name", value.name(),
									"locale", value.locale()
								)
							);
							continue;
						}

						tag = resolved.get();
					}

					// Declared locales are validated with the definition
					encounter.updateLocale(Locales.get(tag).orElseThrow());
				} else {
					if(value.locale() != null) {
						errors.add(
							ERROR_LOCALE_NOT_ALLOWED.toMessage(
								ObjectLocation.root().forField(value.name()),
								"name", value.name(),
								"locale", value.locale()
							)
						);
						continue;
					}

					encounter.updateLocale(DEFAULT_LOCALE_SUPPORT);
				}

				/*
				 * The second value for a field - for a locale specific one, the
				 * second in the same locale - is refused unless the field is
				 * declared multiple.
				 */
				if(!valuesSeen.add(Tuples.pair(value.name(), tag)) && !field0.isMultiple()) {
					errors.add(
						tag == null
							? ERROR_NOT_MULTIPLE.toMessage(
								ObjectLocation.root().forField(value.name()),
								"name", value.name()
							)
							: ERROR_NOT_MULTIPLE_IN_LOCALE.toMessage(
								ObjectLocation.root().forField(value.name()),
								"name", value.name(),
								"locale", tag
							)
					);
					continue;
				}

				encounter.updateValue(value.name(), field0.getDef());

				if(field0.getDef().getPrimaryKey()) {
					primaryKeyTerm = type.createPrimaryKeyTerm(encounter, value.value());
				}

				try {
					for(var indexableField : type.createFields(encounter, value.value())) {
						luceneDoc.add(indexableField);
					}
				} catch(ValidationException e) {
					/*
					 * A value the type refuses joins the other problems of the
					 * document, so everything wrong with it is reported at once.
					 */
					errors.addAllIterable(e.getErrors());
					continue;
				}

				if(localized != null && tag != null) {
					localized
						.getIfAbsentPut(
							value.name(),
							() -> new LocalizedValues(field0, Maps.mutable.empty())
						)
						.byLocale()
						.getIfAbsentPut(tag, Lists.mutable::empty)
						.add(value.value());
				}

				fieldsFound.add(value.name());
			}

			/*
			 * Only worth doing for a document the index is going to accept -
			 * one already being refused would write copies nothing keeps.
			 */
			if(localized != null && errors.isEmpty()) {
				fillMissingLocales(localized, encounter, luceneDoc, errors);
			}

			for(var field : schema.getRequiredFields()) {
				if(!fieldsFound.contains(field)) {
					errors.add(
						ERROR_REQUIRED_FIELD_MISSING.toMessage(
							ObjectLocation.root().forField(field),
							"name",
							field
						)
					);
				}
			}

			if(!errors.isEmpty()) {
				throw new ValidationException(errors);
			}

			/*
			 * Written after the fields have been checked, so that the copy kept
			 * is one the definition accepted rather than whatever arrived.
			 */
			byte[] source = null;
			if(schema.isSourceStored()) {
				source = DocumentSource.encode(doc);
				luceneDoc.add(new StoredField(FieldNames.SOURCE, source));
			}

			/*
			 * Held from here until the document has been written and remembered,
			 * so that a partial update of the same document can not read what
			 * was there before this and write its merge over the top.
			 */
			var documentLock = primaryKeyTerm == null ? null : lockFor(primaryKeyTerm.bytes());
			if(documentLock != null) {
				documentLock.lock();
			}

			try {
				if(childDocs.isEmpty()) {
					if(primaryKeyTerm != null) {
						writer.updateDocument(primaryKeyTerm, luceneDoc);
					} else {
						writer.addDocument(luceneDoc);
					}
				} else {
					/*
					 * The document and the values of its object fields are
					 * written as one block, values first - which is the order
					 * joining them back together relies on. Every value carries
					 * the primary key of its document, so replacing the document
					 * by key replaces the whole block; a value left behind would
					 * go on answering `nested` clauses for a document that no
					 * longer says it.
					 */
					if(primaryKeyTerm != null) {
						for(var child : childDocs) {
							child.add(
								new org.apache.lucene.document.StringField(
									primaryKeyTerm.field(),
									primaryKeyTerm.bytes(),
									org.apache.lucene.document.Field.Store.NO
								)
							);
						}
					}

					var block = childDocs.with(luceneDoc);
					if(primaryKeyTerm != null) {
						writer.updateDocuments(primaryKeyTerm, block);
					} else {
						writer.addDocuments(block);
					}
				}

				if(primaryKeyTerm != null && source != null) {
					rememberSource(primaryKeyTerm.bytes(), source);
				}
			} finally {
				if(documentLock != null) {
					documentLock.unlock();
				}
			}

			var log = this.changeLog;
			if(log != null && primaryKeyTerm != null) {
				log.record(primaryKeyTerm.bytes());
			}

			markModified(1);
		} finally {
			syncLock.readLock().unlock();
			writeGate.readLock().unlock();
		}
	}

	/**
	 * What a document gave one locale specific field, kept per locale so the
	 * locales it left empty can be filled from the ones it did give.
	 *
	 * @param field
	 *   the field the values were given to. Kept alongside because a value
	 *   arrives under a concrete name while the field may be a pattern several
	 *   names resolve to
	 * @param byLocale
	 *   the values, keyed by the declared locale they resolved to
	 */
	private record LocalizedValues(
		Field field,
		MutableMap<String, MutableList<Object>> byLocale
	) {
	}

	/**
	 * Write the variants of a document that its values left empty, taking each
	 * from the first locale of the index's chain the document did give.
	 *
	 * The value is written as the locale it fills rather than as the one it
	 * came from: analysis has to answer the terms a search of that variant
	 * produces, and a collation key is only comparable with the others in its
	 * variant when the same collator made them. What the document says is
	 * untouched - the copies live in the Lucene fields alone, and results are
	 * read from the document as it was given.
	 *
	 * @param localized
	 *   what the document gave each locale specific field
	 * @param encounter
	 * @param luceneDoc
	 *   the document being built, which the copies are added to
	 * @param errors
	 *   where problems with a copy are collected
	 */
	private void fillMissingLocales(
		MapIterable<String, LocalizedValues> localized,
		IndexEncounterImpl encounter,
		org.apache.lucene.document.Document luceneDoc,
		MutableList<ErrorMessage> errors
	) {
		for(var entry : localized.keyValuesView()) {
			var name = entry.getOne();
			var field = entry.getTwo().field();
			var byLocale = entry.getTwo().byLocale();

			// Empty for a field the index or the field itself leaves alone
			var chain = schema.getLocaleFallbackChain(field);
			if(chain.isEmpty()) {
				continue;
			}

			for(var locale : field.getLocales()) {
				if(byLocale.containsKey(locale)) {
					// The document said this locale itself
					continue;
				}

				/*
				 * The first locale of the chain the document gave. A chain
				 * naming locales this document holds none of leaves the
				 * variant empty, the way it was before there was a chain.
				 */
				var source = chain.detect(byLocale::containsKey);
				if(source == null) {
					continue;
				}

				encounter.updateLocale(Locales.get(locale).orElseThrow());
				encounter.updateValue(name, field.getDef());

				try {
					for(var value : byLocale.get(source)) {
						for(var indexableField : field.getType().createFields(encounter, value)) {
							luceneDoc.add(indexableField);
						}
					}
				} catch(ValidationException e) {
					errors.addAllIterable(e.getErrors());
				}
			}
		}
	}

	/**
	 * Turn one value of a nested object field into the Lucene document it is
	 * indexed as.
	 *
	 * <p>Everything is written under the name the document gave the object.
	 * That is the definition's own name for it unless the name is a pattern.
	 * A {@code nested} clause is scoped by the mark on the value, so writing
	 * the pattern would put the values of every name it matched in one place.
	 *
	 * @param objectField
	 *   the object field the value was given to
	 * @param objectName
	 *   the name the document gave it under
	 * @param value
	 *   the value, a document of its own
	 * @param location
	 *   where the value sits in the document, used to point at errors
	 * @param encounter
	 * @param childDocs
	 *   where the values of nested object fields land as documents
	 * @param keysSeen
	 *   what every value of a keyed object field read for its key, shared
	 *   across the whole document
	 * @param errors
	 *   where problems with the value are collected
	 * @return
	 */
	private org.apache.lucene.document.Document childDocument(
		Field objectField,
		String objectName,
		Document value,
		ObjectLocation location,
		IndexEncounterImpl encounter,
		MutableList<org.apache.lucene.document.Document> childDocs,
		MutableSet<Pair<String, String>> keysSeen,
		MutableList<ErrorMessage> errors
	) {
		var child = new org.apache.lucene.document.Document();
		NestedDocuments.mark(child, objectName);

		indexObjectValue(
			objectField, objectName, value, location, encounter,
			child, childDocs, objectName, keysSeen, errors
		);

		return child;
	}

	/**
	 * Index one value of an object field into the document that holds its
	 * fields, checking them the way {@link #addDocument} checks the document's
	 * own. For a flattened object that document is the one the value arrived
	 * in; for a nested object it is the value's own, built by
	 * {@link #childDocument}. Objects nest, so this recurses: a single or
	 * flattened object inside folds into the same target, and a nested list
	 * inside starts a document of its own.
	 *
	 * The names inside the value are the ones the object declares; what they
	 * are written under is the dotted path through the object, so a search
	 * finds them by the name it knows. A field allowed once per value may
	 * still be written by every value of the object, so being given more than
	 * once is judged within the value alone.
	 *
	 * @param objectField
	 *   the object field the value was given to
	 * @param objectName
	 *   the name the document gave it under, which the paths of the fields
	 *   inside are built from
	 * @param value
	 *   the value, an object of its own
	 * @param location
	 *   where the value sits in the document, used to point at errors
	 * @param encounter
	 * @param targetDoc
	 *   the document being built, which the fields are added to
	 * @param childDocs
	 *   where the values of nested object fields land as documents of their
	 *   own
	 * @param nestedList
	 *   concrete name of the nested list the value sits below, or {@code
	 *   null} when the target is the index's own document. Decides the
	 *   namespace the paths inside resolve in
	 * @param keysSeen
	 *   what every value of a keyed object field read for its key, shared
	 *   across the whole document so a key reaches one value wherever its
	 *   list sits
	 * @param errors
	 *   where problems with the value are collected
	 */
	private void indexObjectValue(
		Field objectField,
		String objectName,
		Document value,
		ObjectLocation location,
		IndexEncounterImpl encounter,
		org.apache.lucene.document.Document targetDoc,
		MutableList<org.apache.lucene.document.Document> childDocs,
		String nestedList,
		MutableSet<Pair<String, String>> keysSeen,
		MutableList<ErrorMessage> errors
	) {
		var valuesSeen = Sets.mutable.<Pair<String, String>>empty();
		var fieldsFound = Sets.mutable.<String>empty();
		var childCounts = Maps.mutable.<String, Integer>empty();

		/*
		 * The locales this value's fields were given in, so the locales it
		 * left empty can be filled from the ones it gave - per value, because
		 * each object value is its own unit of translation.
		 */
		var localized = schema.hasLocaleFallback()
			? Maps.mutable.<String, LocalizedValues>empty()
			: null;

		for(var inner : value.fields()) {
			var path = objectName + '.' + inner.name();

			/*
			 * Only the fields declared directly inside this object. The path
			 * resolves in the namespace the target document reads from - the
			 * index's own fields, or those of the nested list's values - and
			 * a name that resolves elsewhere in it was never part of this
			 * object: a root pattern that happens to match, or a field of an
			 * object further in, which only a name reaching past the object
			 * it was given to can name.
			 */
			var resolved = nestedList == null
				? schema.getField(path)
				: schema.getNestedField(path).map(IndexSchema.NestedField::field);

			if(resolved.isEmpty()
				|| !schema.getEnclosingObjectOf(resolved.get(), path)
					.filter(objectName::equals)
					.isPresent()) {
				errors.add(
					ERROR_FIELD_NOT_FOUND.toMessage(
						location.forField(inner.name()),
						"name", inner.name()
					)
				);
				continue;
			}

			var innerField = resolved.get();

			if(innerField.isObject()) {
				if(inner.locale() != null) {
					errors.add(
						ERROR_LOCALE_NOT_ALLOWED.toMessage(
							location.forField(inner.name()),
							"name", inner.name(),
							"locale", inner.locale()
						)
					);
					continue;
				}

				if(!valuesSeen.add(Tuples.pair(inner.name(), (String) null))
					&& !innerField.isMultiple()) {
					errors.add(
						ERROR_NOT_MULTIPLE.toMessage(
							location.forField(inner.name()),
							"name", inner.name()
						)
					);
					continue;
				}

				var position = childCounts.merge(inner.name(), 1, Integer::sum) - 1;

				if(!(inner.value() instanceof Document subDocument)) {
					errors.add(
						ERROR_NOT_A_DOCUMENT.toMessage(
							location.forField(inner.name()).forIndex(position),
							"name", inner.name()
						)
					);
					continue;
				}

				if(duplicateObjectKey(
					path, inner.name(), innerField, subDocument,
					location.forField(inner.name()).forIndex(position),
					keysSeen, errors
				)) {
					continue;
				}

				if(innerField.isNestedObject()) {
					childDocs.add(
						childDocument(
							innerField,
							path,
							subDocument,
							location.forField(inner.name()).forIndex(position),
							encounter,
							childDocs,
							keysSeen,
							errors
						)
					);
				} else {
					indexObjectValue(
						innerField,
						path,
						subDocument,
						location.forField(inner.name()).forIndex(position),
						encounter,
						targetDoc,
						childDocs,
						nestedList,
						keysSeen,
						errors
					);
				}

				fieldsFound.add(inner.name());
				continue;
			}

			if(inner.value() instanceof Document) {
				errors.add(
					ERROR_UNEXPECTED_DOCUMENT.toMessage(
						location.forField(inner.name()),
						"name", inner.name()
					)
				);
				continue;
			}

			/*
			 * The locale of each value is resolved the way addDocument
			 * resolves it for a field of the index - a field inside an
			 * object holds its variants the same way.
			 */
			String tag = null;
			if(innerField.isLocaleSpecific()) {
				if(inner.locale() == null) {
					tag = innerField.getDefaultLocale();
				} else {
					var resolvedLocale = innerField.resolveLocale(inner.locale());
					if(resolvedLocale.isEmpty()) {
						errors.add(
							ERROR_LOCALE_NOT_DECLARED.toMessage(
								location.forField(inner.name()),
								"name", inner.name(),
								"locale", inner.locale()
							)
						);
						continue;
					}

					tag = resolvedLocale.get();
				}

				encounter.updateLocale(Locales.get(tag).orElseThrow());
			} else {
				if(inner.locale() != null) {
					errors.add(
						ERROR_LOCALE_NOT_ALLOWED.toMessage(
							location.forField(inner.name()),
							"name", inner.name(),
							"locale", inner.locale()
						)
					);
					continue;
				}

				encounter.updateLocale(DEFAULT_LOCALE_SUPPORT);
			}

			if(!valuesSeen.add(Tuples.pair(inner.name(), tag)) && !innerField.isMultiple()) {
				errors.add(
					tag == null
						? ERROR_NOT_MULTIPLE.toMessage(
							location.forField(inner.name()),
							"name", inner.name()
						)
						: ERROR_NOT_MULTIPLE_IN_LOCALE.toMessage(
							location.forField(inner.name()),
							"name", inner.name(),
							"locale", tag
						)
				);
				continue;
			}

			encounter.updateValue(path, innerField.getDef());

			try {
				for(var indexableField : innerField.getType().createFields(encounter, inner.value())) {
					targetDoc.add(indexableField);
				}
			} catch(ValidationException e) {
				errors.addAllIterable(e.getErrors());
				continue;
			}

			if(localized != null && tag != null) {
				localized
					.getIfAbsentPut(
						path,
						() -> new LocalizedValues(innerField, Maps.mutable.empty())
					)
					.byLocale()
					.getIfAbsentPut(tag, Lists.mutable::empty)
					.add(inner.value());
			}

			fieldsFound.add(inner.name());
		}

		/*
		 * Each object value fills its own missing locales from its own given
		 * ones - and only for a value the index is going to accept, the way
		 * addDocument holds off for a document being refused.
		 */
		if(localized != null && localized.notEmpty() && errors.isEmpty()) {
			fillMissingLocales(localized, encounter, targetDoc, errors);
		}

		// Being required means required in every value, not once per document
		var objectDef = objectField.getDef().getType().getObject();
		for(var entry : objectDef.getFieldsMap().entrySet()) {
			if(entry.getValue().getRequired() && !fieldsFound.contains(entry.getKey())) {
				errors.add(
					ERROR_REQUIRED_FIELD_MISSING.toMessage(
						location.forField(entry.getKey()),
						"name", entry.getKey()
					)
				);
			}
		}
	}

	/**
	 * Check the key one value of a keyed object field reads, refusing it when
	 * an earlier value of the same list already read the same. Nothing else
	 * checks, and every place that names a value by its key relies on the
	 * name reaching one value.
	 *
	 * <p>A value with no key at all is left to the required check inside it,
	 * which points at the field rather than at a duplicate that is not there.
	 *
	 * @param path
	 *   concrete dotted path of the object field, which keeps the keys of
	 *   same-named lists inside different objects apart
	 * @param name
	 *   the name the value was given under, used to point at errors
	 * @param objectField
	 *   the object field the value was given to
	 * @param value
	 *   the value, a document of its own
	 * @param location
	 *   where the value sits in the document
	 * @param keysSeen
	 *   what every keyed value of the document read so far
	 * @param errors
	 *   where a duplicate is reported
	 * @return
	 *   whether the value duplicates an earlier one and has to be skipped
	 */
	private static boolean duplicateObjectKey(
		String path,
		String name,
		Field objectField,
		Document value,
		ObjectLocation location,
		MutableSet<Pair<String, String>> keysSeen,
		MutableList<ErrorMessage> errors
	) {
		var key = objectField.getObjectKey();
		if(key == null) {
			return false;
		}

		var reads = value.get(key);
		if(reads == null || keysSeen.add(Tuples.pair(path, String.valueOf(reads)))) {
			return false;
		}

		errors.add(
			ERROR_OBJECT_KEY_DUPLICATE.toMessage(
				location,
				"name", name,
				"key", key,
				"value", String.valueOf(reads)
			)
		);

		return true;
	}

	/**
	 * Change some of the fields of a document that is already indexed, leaving
	 * the rest of it as it is.
	 *
	 * <p>A patch carries its own primary key, the way a document does, and the
	 * document it names is read, changed and written back as one - so patches
	 * of the same document take effect in the order they are given, whether or
	 * not the index has been committed in between. What the fields of a patch
	 * mean is {@link DocumentPatch}; the merged document is then indexed as if
	 * it had been given whole, so a patch that leaves it failing validation is
	 * refused and changes nothing.
	 *
	 * <p>The document is read from the copy kept alongside the index, which an
	 * index set to keep nothing does not have.
	 *
	 * @return
	 *   {@code false} when nothing is indexed under the key, where the index is
	 *   left as it is - a patch says what to change about a document rather
	 *   than what should be there, so there is nothing to write without one
	 * @throws IndexNoPrimaryKeyException
	 *   if the definition of the index declares no primary key
	 * @throws IndexSourceNotKeptException
	 *   if the index does not keep its documents, or if this document was
	 *   indexed while it did not. What the fields of the document are can not
	 *   be told from the index alone, so merging into it would drop whatever
	 *   the patch did not mention
	 * @throws ValidationException
	 *   if the patch names no primary key, or if the document it leaves behind
	 *   is not one the definition accepts
	 * @throws IndexReadonlyException
	 *   if this node is not the indexer
	 * @throws IOException
	 */
	public boolean updateDocument(DocumentPatch patch) throws IOException {
		/*
		 * Taken before the sync lock, like every write - the addDocument below
		 * takes both again on the same thread, which the gate allows.
		 */
		writeGate.readLock().lock();
		syncLock.readLock().lock();
		try {
			checkModifiable();

			if(!schema.isSourceStored()) {
				throw new IndexSourceNotKeptException(id);
			}

			var field = primaryKeyField();
			var primaryKey = patch.get(field.getName());
			if(primaryKey == null) {
				throw new ValidationException(
					ERROR_PRIMARY_KEY_REQUIRED.toMessage(
						ObjectLocation.root().forField(field.getName()),
						"name", field.getName()
					)
				);
			}

			var encounter = new IndexEncounterImpl(schema.getResources(), schema.isHighlightingInPostings());
			encounter.updateLocale(DEFAULT_LOCALE_SUPPORT);
			encounter.updateValue(field.getName(), field.getDef());

			var term = field.getType().createPrimaryKeyTerm(encounter, primaryKey);

			/*
			 * Held across the read and the write, so that another update of the
			 * same document can not read what is there now and write its own
			 * merge over the one this makes. Taken again by addDocument below,
			 * which is the same lock on the same thread.
			 */
			var documentLock = lockFor(term.bytes());
			documentLock.lock();
			try {
				var current = readSource(term);
				if(current == null) {
					return false;
				}

				addDocument(patch.applyTo(current));
				return true;
			} finally {
				documentLock.unlock();
			}
		} finally {
			syncLock.readLock().unlock();
			writeGate.readLock().unlock();
		}
	}

	/**
	 * Read the stored copy of the document a term names, taking it from what
	 * has been written since the merge reader was opened when it is there.
	 *
	 * @return
	 *   the document, or {@code null} when nothing is indexed under the key
	 * @throws IndexSourceNotKeptException
	 *   if the document is indexed without a copy of what it was given, which
	 *   is how one indexed while the index kept nothing looks
	 */
	private Document readSource(Term term) throws IOException {
		synchronized(mergeLock) {
			if(mergeReaderStale) {
				/*
				 * The index was written in a way the remembered sources could
				 * not record, so the reader is the only thing that can answer -
				 * and reopening it makes it hold everything remembered as well.
				 */
				refreshMergeReader();
			}

			var pending = pendingSources.get(term.bytes());
			if(pending != null) {
				return pending == DELETED ? null : DocumentSource.decode(new BytesRef(pending));
			}

			if(mergeReader == null) {
				refreshMergeReader();
			}

			var searcher = new IndexSearcher(mergeReader);
			var hits = searcher.search(parentsOnly(new TermQuery(term)), 1);
			if(hits.totalHits.value() == 0) {
				return null;
			}

			var stored = mergeReader.storedFields()
				.document(hits.scoreDocs[0].doc, Set.of(FieldNames.SOURCE));

			var source = stored.getBinaryValue(FieldNames.SOURCE);
			if(source == null) {
				/*
				 * Indexed while the index kept nothing. The stored fields could
				 * be read back into something document shaped, but only the
				 * fields that happen to be stored, so a merge into it would
				 * quietly drop the rest.
				 */
				throw new IndexSourceNotKeptException(id);
			}

			return DocumentSource.decode(source);
		}
	}

	/**
	 * Remember the copy of a document that was just written, so that the next
	 * update of it reads what is there now rather than what the merge reader
	 * was opened on.
	 */
	private void rememberSource(BytesRef primaryKey, byte[] source) {
		synchronized(mergeLock) {
			var key = BytesRef.deepCopyOf(primaryKey);

			var previous = pendingSources.put(key, source);
			if(previous != null) {
				pendingSourceBytes -= previous.length;
			}

			pendingSourceBytes += source.length;

			if(pendingSourceBytes > MAX_PENDING_SOURCE_BYTES) {
				/*
				 * Forgotten rather than reopened here and now: an index that is
				 * only written to would pay for a reader nothing ever reads,
				 * and the next update that needs one reopens it itself.
				 */
				forgetSources();
				mergeReaderStale = true;
			}
		}
	}

	/**
	 * Remember that a document was removed, so that an update of it is told it
	 * is gone rather than reading it from a reader opened before the removal.
	 */
	private void rememberRemoved(BytesRef primaryKey) {
		synchronized(mergeLock) {
			var previous = pendingSources.put(BytesRef.deepCopyOf(primaryKey), DELETED);
			if(previous != null) {
				pendingSourceBytes -= previous.length;
			}
		}
	}

	/**
	 * Give up on saying what has been written since the merge reader was
	 * opened, for a change that can not be recorded document by document.
	 */
	private void invalidateMergeReader() {
		synchronized(mergeLock) {
			forgetSources();
			mergeReaderStale = true;
		}
	}

	private void forgetSources() {
		pendingSources.clear();
		pendingSourceBytes = 0;
	}

	/**
	 * Open the merge reader on what the writer holds now. Everything remembered
	 * is in the reader once this returns, which is what lets it be forgotten.
	 */
	private void refreshMergeReader() throws IOException {
		var reopened = mergeReader == null
			? DirectoryReader.open(writer)
			: DirectoryReader.openIfChanged(mergeReader, writer);

		if(reopened != null) {
			if(mergeReader != null) {
				mergeReader.close();
			}

			mergeReader = reopened;
		}

		forgetSources();
		mergeReaderStale = false;
	}

	private void closeMergeReader() {
		synchronized(mergeLock) {
			forgetSources();
			mergeReaderStale = false;

			if(mergeReader != null) {
				try {
					mergeReader.close();
				} catch(IOException e) {
					logger.atWarn()
						.addKeyValue("index", id)
						.setCause(e)
						.log("Could not close the reader partial updates read; " + e.getMessage());
				}

				mergeReader = null;
			}
		}
	}

	private ReentrantLock lockFor(BytesRef primaryKey) {
		return documentLocks[bucketOf(primaryKey)];
	}

	/**
	 * Get the locks covering a set of documents, in the order they have to be
	 * taken. Everything else takes one lock at a time, so keeping these in a
	 * fixed order is what stops two of these from deadlocking each other.
	 */
	private ReentrantLock[] locksFor(Term[] terms) {
		var buckets = new TreeSet<Integer>();
		for(var term : terms) {
			buckets.add(bucketOf(term.bytes()));
		}

		var locks = new ReentrantLock[buckets.size()];
		var i = 0;
		for(var bucket : buckets) {
			locks[i++] = documentLocks[bucket];
		}

		return locks;
	}

	private int bucketOf(BytesRef primaryKey) {
		return Math.floorMod(primaryKey.hashCode(), documentLocks.length);
	}

	/**
	 * Read a primary key that arrived as text into the value the key field
	 * holds, so that a key taken from a URL names the same document as the
	 * same key given as JSON.
	 *
	 * Text that is not a value of the key field is returned as it came, and
	 * refused where it is used.
	 *
	 * @param text
	 * @return
	 * @throws IndexNoPrimaryKeyException
	 *   if the definition of the index declares no primary key
	 */
	public Object parsePrimaryKey(String text) {
		return primaryKeyField().getType().primaryKeyFromText(text);
	}

	/**
	 * Remove the document indexed under a primary key.
	 *
	 * Removal is desired state the way indexing is: a key nothing was indexed
	 * under leaves the index as it is rather than failing, so the same request
	 * can be sent again. What is removed stops being searchable once the index
	 * is committed, which is also what pushes the removal to the remote.
	 *
	 * @param primaryKey
	 *   the key as the type of the key field holds it - text for a string key,
	 *   a whole number for a numeric one
	 * @throws IndexNoPrimaryKeyException
	 *   if the definition of the index declares no primary key
	 * @throws IndexInvalidQueryValueException
	 *   if the key is not a value the key field can hold
	 * @throws IndexReadonlyException
	 *   if this node is not the indexer
	 * @throws IOException
	 */
	public void deleteDocument(Object primaryKey) throws IOException {
		deleteDocuments(Lists.immutable.of(primaryKey));
	}

	/**
	 * Remove the documents indexed under several primary keys, as one change to
	 * the index. Every key is read before anything is removed, so a key the
	 * index refuses leaves the whole call without effect.
	 *
	 * @param primaryKeys
	 * @return
	 *   how many keys were taken, which is how many were given - a key nothing
	 *   was indexed under is not an error and counts with the rest
	 * @throws IndexNoPrimaryKeyException
	 *   if the definition of the index declares no primary key
	 * @throws IndexInvalidQueryValueException
	 *   if a key is not a value the key field can hold
	 * @throws IndexReadonlyException
	 *   if this node is not the indexer
	 * @throws IOException
	 */
	public int deleteDocuments(ListIterable<Object> primaryKeys) throws IOException {
		writeGate.readLock().lock();
		syncLock.readLock().lock();
		try {
			checkModifiable();

			if(primaryKeys.isEmpty()) {
				return 0;
			}

			var field = primaryKeyField();
			var encounter = new IndexEncounterImpl(schema.getResources(), schema.isHighlightingInPostings());
			encounter.updateLocale(DEFAULT_LOCALE_SUPPORT);
			encounter.updateValue(field.getName(), field.getDef());

			var terms = new Term[primaryKeys.size()];
			for(var i = 0; i < terms.length; i++) {
				terms[i] = field.getType().createPrimaryKeyTerm(encounter, primaryKeys.get(i));
			}

			/*
			 * Held over the removal so that a partial update of one of these
			 * documents can not have read it just before and write its merge
			 * back afterwards, which would bring the document back.
			 */
			var locks = locksFor(terms);
			for(var lock : locks) {
				lock.lock();
			}

			try {
				/*
				 * The values of an object field carry the key of the document
				 * they belong to, so the term takes the whole block with it -
				 * the same way replacing a document by its key does.
				 */
				writer.deleteDocuments(terms);

				for(var term : terms) {
					rememberRemoved(term.bytes());
				}
			} finally {
				for(var i = locks.length - 1; i >= 0; i--) {
					locks[i].unlock();
				}
			}

			var log = this.changeLog;
			if(log != null) {
				for(var term : terms) {
					log.record(term.bytes());
				}
			}

			markModified(terms.length);
			return terms.length;
		} finally {
			syncLock.readLock().unlock();
			writeGate.readLock().unlock();
		}
	}

	/**
	 * Remove every document a query matches, whether or not the index has a
	 * primary key.
	 *
	 * The clauses are the ones a search is written with and mean the same here,
	 * so what a search brings back is what a delete of those clauses removes.
	 * Documents indexed since the last commit are removed as well, and are not
	 * part of the count - nothing is searchable until it has been committed.
	 *
	 * @param clauses
	 *   what a document has to satisfy to be removed, all of them. An empty
	 *   list matches every document and empties the index
	 * @param locale
	 *   the locale locale specific fields are matched in (BCP 47), or
	 *   {@code null} to leave every field to its own default locale
	 * @return
	 *   how many of the documents that were searchable at that moment the query
	 *   matched
	 * @throws IndexReadonlyException
	 *   if this node is not the indexer
	 * @throws IOException
	 */
	public int deleteByQuery(ListIterable<Query> clauses, String locale) throws IOException {
		writeGate.readLock().lock();
		syncLock.readLock().lock();
		try {
			checkModifiable();

			if(locale != null && Locales.resolve(locale).isEmpty()) {
				throw new IndexException(ERROR_UNSUPPORTED_SEARCH_LOCALE, "locale", locale);
			}

			var compiler = new QueryCompiler(schema, locale, nestedParents);
			var documents = parentsOnly(compiler.compile(clauses), compiler, clauses);

			int matched;
			try(var handle = searcherManager.acquire()) {
				matched = handle.getSearcher().count(documents);
			}

			/*
			 * Which keys go has to be written down before they are gone -
			 * recorded ahead of the removal, so a failure between the two
			 * leaves the log saying more than changed rather than less.
			 */
			var log = this.changeLog;
			if(log != null) {
				recordMatches(log, documents);
			}

			writer.deleteDocuments(withNestedValues(documents));

			/*
			 * Which documents went is not known key by key here, so nothing
			 * remembered can be trusted to still be there.
			 */
			invalidateMergeReader();

			/*
			 * The count only covers what was searchable, while the removal also
			 * takes documents indexed since the last commit - so a query that
			 * matched nothing may still have changed the index, and counts as
			 * one change rather than none.
			 */
			markModified(Math.max(matched, 1));
			return matched;
		} finally {
			syncLock.readLock().unlock();
			writeGate.readLock().unlock();
		}
	}

	/**
	 * Widen a query of documents to the values of the object fields of those
	 * documents, which are Lucene documents of their own sitting in the same
	 * block. A value left behind by a document that is gone would go on
	 * answering {@code nested} clauses for a document that no longer says it.
	 *
	 * @param documents
	 *   a query matching documents of the index only, as
	 *   {@link #parentsOnly(org.apache.lucene.search.Query)} leaves it
	 * @return
	 */
	private org.apache.lucene.search.Query withNestedValues(
		org.apache.lucene.search.Query documents
	) {
		if(!schema.hasNestedFields()) {
			return documents;
		}

		return new BooleanQuery.Builder()
			.add(documents, BooleanClause.Occur.SHOULD)
			.add(
				new ToChildBlockJoinQuery(documents, nestedParents),
				BooleanClause.Occur.SHOULD
			)
			.build();
	}

	/**
	 * Get the field documents of this index are named by.
	 *
	 * @return
	 *   empty when the definition declares no primary key, where every document
	 *   indexed is a new one rather than replacing anything
	 */
	public Optional<Field> getPrimaryKey() {
		return schema.getPrimaryKey();
	}

	/**
	 * Get the field documents of this index are named by.
	 *
	 * @return
	 * @throws IndexNoPrimaryKeyException
	 *   if the definition of the index declares no primary key
	 */
	private Field primaryKeyField() {
		return schema.getPrimaryKey().orElseThrow(() -> new IndexNoPrimaryKeyException(id));
	}

	public Document getDocument(Object primaryKey) throws IOException {
		syncLock.readLock().lock();
		try {
			if(state == IndexState.CLOSED) {
				throw new IndexClosedException(id);
			}

			try(var handle = searcherManager.acquire()) {
				var searcher = handle.getSearcher();

				var primaryKeyField = primaryKeyField();

				var encounter = new IndexEncounterImpl(schema.getResources(), schema.isHighlightingInPostings());
				encounter.updateLocale(DEFAULT_LOCALE_SUPPORT);
				encounter.updateValue(primaryKeyField.getName(), primaryKeyField.getDef());

				var primaryKeyTerm = primaryKeyField.getType()
					.createPrimaryKeyTerm(encounter, primaryKey);
				/*
				 * The values of object fields carry the primary key of their
				 * document too, so the lookup has to say which of the block it
				 * wants.
				 */
				var query = parentsOnly(new TermQuery(primaryKeyTerm));

				var hits = searcher.search(query, 1);
				if(hits.totalHits.value() == 0) {
					return null;
				}

				var doc = documentCache.read(
					searcher,
					searcher.storedFields(),
					hits.scoreDocs[0].doc,
					null
				);

				var reader = DocumentReader.everyVariant(schema, Sets.immutable.<String>empty());
				if(reader.needsChildren()) {
					/*
					 * Without a copy, the values of the document's nested
					 * lists are read out of their own documents and handed
					 * along, so the document comes back with them inside.
					 */
					var children = readChildren(
						searcher,
						new int[] { hits.scoreDocs[0].doc },
						reader::wantsChildren
					);

					return reader.read(doc, children.get(hits.scoreDocs[0].doc));
				}

				return reader.read(doc);
			}
		} finally {
			syncLock.readLock().unlock();
		}
	}

	/**
	 * Get how many documents the index holds, as a search sees it - the last
	 * commit, without anything indexed since.
	 *
	 * @throws IndexClosedException
	 *   if this instance has been closed
	 * @throws IOException
	 */
	public long getDocumentCount() throws IOException {
		syncLock.readLock().lock();
		try {
			if(state == IndexState.CLOSED) {
				throw new IndexClosedException(id);
			}

			try(var handle = searcherManager.acquire()) {
				return handle.getSearcher().count(parentsOnly(new MatchAllDocsQuery()));
			}
		} finally {
			syncLock.readLock().unlock();
		}
	}

	/**
	 * Read the stored copy of the document a primary key term names, seeing
	 * everything written so far whether committed or not. The term is a key as
	 * Lucene indexed it - what a {@link ChangeLog} records - comparable only
	 * between indexes whose primary key fields share a name and type.
	 *
	 * <p>Answering from what is not committed yet takes the writer, so only
	 * the node writing the index can ask.
	 *
	 * @param keyTerm
	 * @return
	 *   the document, or {@code null} when nothing is indexed under the key
	 * @throws IndexNoPrimaryKeyException
	 *   if the definition declares no primary key
	 * @throws IndexSourceNotKeptException
	 *   if the index keeps no copy of its documents, or the document was
	 *   indexed while it kept none
	 * @throws IndexReadonlyException
	 *   if this node is not the indexer
	 * @throws IOException
	 */
	public Document getDocumentByKeyTerm(BytesRef keyTerm) throws IOException {
		syncLock.readLock().lock();
		try {
			checkModifiable();

			var field = primaryKeyField();
			if(!schema.isSourceStored()) {
				throw new IndexSourceNotKeptException(id);
			}

			var encounter = new IndexEncounterImpl(schema.getResources(), schema.isHighlightingInPostings());
			encounter.updateLocale(DEFAULT_LOCALE_SUPPORT);
			encounter.updateValue(field.getName(), field.getDef());

			return readSource(new Term(encounter.name(FieldNames.PRIMARY_KEY), keyTerm));
		} finally {
			syncLock.readLock().unlock();
		}
	}

	/**
	 * Remove the document a primary key term names, the way
	 * {@link #deleteDocument(Object)} removes one named by its value. The term
	 * is a key as Lucene indexed it - what a {@link ChangeLog} of an index
	 * with the same primary key field records - so a caller catching this
	 * index up against another can take a removal over without decoding the
	 * key.
	 *
	 * @param keyTerm
	 * @throws IndexNoPrimaryKeyException
	 *   if the definition declares no primary key
	 * @throws IndexReadonlyException
	 *   if this node is not the indexer
	 * @throws IOException
	 */
	public void deleteDocumentByKeyTerm(BytesRef keyTerm) throws IOException {
		writeGate.readLock().lock();
		syncLock.readLock().lock();
		try {
			checkModifiable();

			var field = primaryKeyField();
			var encounter = new IndexEncounterImpl(schema.getResources(), schema.isHighlightingInPostings());
			encounter.updateLocale(DEFAULT_LOCALE_SUPPORT);
			encounter.updateValue(field.getName(), field.getDef());

			var term = new Term(
				encounter.name(FieldNames.PRIMARY_KEY),
				BytesRef.deepCopyOf(keyTerm)
			);

			/*
			 * Held like deleteDocuments holds it, so a partial update that read
			 * the document just before cannot write its merge back afterwards
			 * and bring the document back.
			 */
			var lock = lockFor(term.bytes());
			lock.lock();
			try {
				writer.deleteDocuments(term);
				rememberRemoved(term.bytes());
			} finally {
				lock.unlock();
			}

			var log = this.changeLog;
			if(log != null) {
				log.record(term.bytes());
			}

			markModified(1);
		} finally {
			syncLock.readLock().unlock();
			writeGate.readLock().unlock();
		}
	}

	/**
	 * Read documents back out of this index, in the order of their primary
	 * keys, so that a caller can take out everything the index holds without
	 * knowing what to ask for.
	 *
	 * <p>The order is the order of the primary key terms: what a whole number
	 * key counts as, and the UTF-8 bytes of a text one. It depends on the keys
	 * alone, so it is the same whatever order the documents were indexed in,
	 * and merges, removals and pulls do not move a document within it. That is
	 * what lets a caller stop and pick up again from the key it last read,
	 * without a token standing for a position.
	 *
	 * <p>One call answers from one searcher, so what it hands over is the
	 * index at one moment - the last commit, as a search reads it, rather than
	 * everything written so far. Between two calls it is not: a document indexed
	 * under a key the reading has already passed is never handed over, and one
	 * changed after it was read is handed over as it was. Reading an index
	 * that is being written and needing everything means tracking the changes
	 * alongside - see {@link #beginChangeTracking()}.
	 *
	 * <p>How much one call reads is the caller's to bound. Reading holds the
	 * index against a pull for as long as it runs, so a caller wanting
	 * everything asks for many bounded reads rather than one unbounded one.
	 *
	 * @param after
	 *   the key to carry on after, which is not itself handed over, or
	 *   {@code null} to start at the first document - a key nothing is indexed
	 *   under carries on from where it would have been
	 * @param limit
	 *   how many documents to read at most
	 * @param receiver
	 *   handed each document as it is read, in order
	 * @return
	 *   how many documents were handed over, which is fewer than {@code limit}
	 *   only at the end of the index
	 * @throws IndexNoPrimaryKeyException
	 *   if the definition declares no primary key, without which the documents
	 *   have no order to be read in
	 * @throws IndexSourceNotKeptException
	 *   if the index keeps no copy of its documents, where what came back
	 *   would be what the fields happen to be stored as rather than the
	 *   documents
	 * @throws IndexClosedException
	 *   if this instance has been closed
	 * @throws IOException
	 */
	public int scanDocuments(
		Object after,
		int limit,
		DocumentReceiver receiver
	) throws IOException {
		syncLock.readLock().lock();
		try {
			if(state == IndexState.CLOSED) {
				throw new IndexClosedException(id);
			}

			var primaryKeyField = primaryKeyField();
			if(!schema.isSourceStored()) {
				throw new IndexSourceNotKeptException(id);
			}

			var encounter = new IndexEncounterImpl(schema.getResources(), schema.isHighlightingInPostings());
			encounter.updateLocale(DEFAULT_LOCALE_SUPPORT);
			encounter.updateValue(primaryKeyField.getName(), primaryKeyField.getDef());

			try(var handle = searcherManager.acquire()) {
				var searcher = handle.getSearcher();
				var reader = searcher.getIndexReader();

				/*
				 * One merged view over the segments, which is what puts the
				 * keys of the whole index in one order - the terms of a single
				 * segment are only sorted among themselves.
				 */
				var terms = MultiTerms.getTerms(reader, encounter.name(FieldNames.PRIMARY_KEY));
				if(terms == null) {
					return 0;
				}

				var keys = terms.iterator();
				if(! seek(keys, after, primaryKeyField, encounter)) {
					return 0;
				}

				var liveDocs = MultiBits.getLiveDocs(reader);
				var storedFields = searcher.storedFields();
				var documents = DocumentReader.everyVariant(schema, Sets.immutable.<String>empty());

				var read = 0;
				PostingsEnum postings = null;

				while(read < limit) {
					postings = keys.postings(postings, PostingsEnum.NONE);

					var doc = documentOf(postings, liveDocs);
					if(doc != NO_DOCUMENT) {
						receiver.accept(
							documents.read(documentCache.read(searcher, storedFields, doc, null))
						);

						read++;
					}

					if(keys.next() == null) {
						break;
					}
				}

				return read;
			}
		} finally {
			syncLock.readLock().unlock();
		}
	}

	/**
	 * Handed each document a {@link Index#scanDocuments scan} reads, as it is
	 * read.
	 */
	@FunctionalInterface
	public interface DocumentReceiver {
		/**
		 * Take one document. Throwing stops the scan, which hands the failure
		 * back to whoever asked for it.
		 *
		 * @param document
		 * @throws IOException
		 */
		void accept(Document document) throws IOException;
	}

	/**
	 * Put a walk over the primary keys on the first key a scan is to read.
	 *
	 * @param keys
	 * @param after
	 *   the key the scan carries on after, or {@code null} to start at the
	 *   first
	 * @return
	 *   whether there is a key to read, which is {@code false} for an empty
	 *   index and for a scan that carried on past the last key
	 */
	private static boolean seek(
		TermsEnum keys,
		Object after,
		Field primaryKeyField,
		IndexEncounter encounter
	) throws IOException {
		if(after == null) {
			return keys.next() != null;
		}

		var from = primaryKeyField.getType()
			.createPrimaryKeyTerm(encounter, after)
			.bytes();

		/*
		 * A key that is there is stepped past, as it was read by the call that
		 * ended on it; a key that is not leaves the walk on the one that would
		 * have followed it, which is where a scan carrying on from a document
		 * that has since been removed belongs.
		 */
		return switch(keys.seekCeil(from)) {
			case END -> false;
			case FOUND -> keys.next() != null;
			case NOT_FOUND -> true;
		};
	}

	/**
	 * What {@link #documentOf} answers for a key nothing is indexed under any
	 * more.
	 */
	private static final int NO_DOCUMENT = -1;

	/**
	 * Get the document a primary key names, from everything the key is written
	 * on.
	 *
	 * <p>A key is written on more than the document itself. The values of its
	 * object fields carry it too, as they are Lucene documents of their own in
	 * the same block; and a version of the document that was replaced or
	 * removed keeps it until a merge drops the segment it sits in. Of all of
	 * them only one block is live - replacing a document removes the whole of
	 * the previous one - and a block is written with its document last, so the
	 * highest live one is the document.
	 */
	private static int documentOf(PostingsEnum postings, Bits liveDocs) throws IOException {
		var found = NO_DOCUMENT;

		for(
			var doc = postings.nextDoc();
			doc != DocIdSetIterator.NO_MORE_DOCS;
			doc = postings.nextDoc()
		) {
			if(liveDocs == null || liveDocs.get(doc)) {
				found = doc;
			}
		}

		return found;
	}

	/**
	 * Search this index.
	 *
	 * Searching runs against the state this node has, which for a node that is
	 * not the indexer is whatever it last pulled. It never waits for a pull or
	 * for changes that have not been committed yet, so a search answers from
	 * what is there rather than blocking until it is current.
	 *
	 * @param request
	 *   what to look for, how to order it and how much to bring back
	 * @return
	 *   what was found, never {@code null}
	 * @throws IOException
	 */
	public SearchResult search(SearchRequest request) throws IOException {
		return search(request, null);
	}

	/**
	 * Version of the search settings whose skipped entries were last logged,
	 * so a gap between the settings and this generation is said once per
	 * version rather than once per search.
	 */
	private volatile String settingsVersionWarned;

	/**
	 * Compile the ranking of the search settings against this generation, or
	 * {@code null} when there is none to put in force.
	 */
	private RankingOverride compileRankingOverride(SearchSettings.Snapshot settings) {
		if(settings == null || settings.ranking() == null) {
			return null;
		}

		var override = schema.compileRankingOverride(settings.ranking());

		if(override.skippedFields().notEmpty()
			&& !settings.version().equals(settingsVersionWarned)) {
			settingsVersionWarned = settings.version();

			logger.atWarn()
				.addKeyValue("index", id)
				.addKeyValue("fields", override.skippedFields().makeString(", "))
				.log(
					"The search settings rank by fields this generation cannot"
						+ " answer for; skipping those entries"
				);
		}

		return override;
	}

	/**
	 * Validate a ranking arriving as search settings against this generation.
	 * What passes here can still be skipped by a later generation - see
	 * {@link IndexSchema#compileRankingOverride} - so this is the check for
	 * storing settings, not for searching with them.
	 *
	 * @param ranking
	 * @param location
	 *   where the ranking sits in what the caller is validating
	 * @return
	 *   what stops the ranking, empty when this generation answers for all of
	 *   it
	 */
	public ListIterable<ErrorMessage> validateSearchSettings(
		RankingConfig ranking,
		ObjectLocation location
	) {
		return schema.validateRankingConfig(ranking, location);
	}

	/**
	 * Validate synonym sets arriving as search settings against this
	 * generation. What passes here can still be skipped by a later generation -
	 * see {@link SynonymOverlay#compile} - so this is the check for storing
	 * settings, not for searching with them.
	 *
	 * @param synonyms
	 *   the sets as they would be stored, by name
	 * @param location
	 *   where the sets sit in what the caller is validating
	 * @return
	 *   what stops the sets, empty when this generation answers for all of them
	 */
	public ListIterable<ErrorMessage> validateSearchSettings(
		Map<String, QuerySynonyms> synonyms,
		ObjectLocation location
	) {
		return SynonymOverlay.validate(synonyms, schema, location);
	}

	/**
	 * The overlay last compiled, and what it was compiled from. Building the
	 * automaton of a set walks every rule of it through the analyzer of every
	 * field it applies to, which is work a search should do once for a version
	 * of the settings rather than once each.
	 */
	private record CompiledSynonyms(
		String settingsVersion,
		String definitionVersion,
		SynonymOverlay overlay
	) {
	}

	private volatile CompiledSynonyms compiledSynonyms;

	/**
	 * Compile the synonym sets of the search settings against this generation.
	 */
	private SynonymOverlay compileSynonymOverlay(SearchSettings.Snapshot settings) {
		if(settings == null || settings.synonyms().isEmpty()) {
			return SynonymOverlay.none();
		}

		var compiled = compiledSynonyms;
		if(compiled != null
			&& compiled.settingsVersion().equals(settings.version())
			&& compiled.definitionVersion().equals(definitionVersion)) {
			return compiled.overlay();
		}

		var overlay = SynonymOverlay.compile(settings.synonyms(), schema);
		compiledSynonyms = new CompiledSynonyms(
			settings.version(),
			definitionVersion,
			overlay
		);

		if(overlay.skippedFields().notEmpty()) {
			logger.atWarn()
				.addKeyValue("index", id)
				.addKeyValue("fields", overlay.skippedFields().makeString(", "))
				.log(
					"The search settings widen fields this generation cannot"
						+ " answer for; leaving those fields as they are"
				);
		}

		return overlay;
	}

	/**
	 * Validate the word lists of search settings that are matched as they are
	 * spelled against this generation. What passes here can still be skipped by
	 * a later generation - see {@link TypoExclusions#compile} - so this is the
	 * check for storing settings, not for searching with them.
	 *
	 * @param typoExclusions
	 *   the lists as they would be stored, by name
	 * @param location
	 *   where the lists sit in what the caller is validating
	 * @return
	 *   what stops the lists, empty when this generation answers for all of
	 *   them
	 */
	public ListIterable<ErrorMessage> validateTypoExclusions(
		Map<String, QueryTypoExclusions> typoExclusions,
		ObjectLocation location
	) {
		return TypoExclusions.validate(typoExclusions, schema, location);
	}

	/**
	 * The exclusions last compiled, and what they were compiled from. Reading a
	 * word list walks every word of it through the analyzer of every field it
	 * covers, which is work a search should do once for a version of the
	 * settings rather than once each.
	 */
	private record CompiledTypoExclusions(
		String settingsVersion,
		String definitionVersion,
		TypoExclusions exclusions
	) {
	}

	private volatile CompiledTypoExclusions compiledTypoExclusions;

	/**
	 * Compile the word lists of the search settings against this generation.
	 */
	private TypoExclusions compileTypoExclusions(SearchSettings.Snapshot settings) {
		if(settings == null || settings.typoExclusions().isEmpty()) {
			return TypoExclusions.none();
		}

		var compiled = compiledTypoExclusions;
		if(compiled != null
			&& compiled.settingsVersion().equals(settings.version())
			&& compiled.definitionVersion().equals(definitionVersion)) {
			return compiled.exclusions();
		}

		var exclusions = TypoExclusions.compile(settings.typoExclusions(), schema);
		compiledTypoExclusions = new CompiledTypoExclusions(
			settings.version(),
			definitionVersion,
			exclusions
		);

		if(exclusions.skippedFields().notEmpty()) {
			logger.atWarn()
				.addKeyValue("index", id)
				.addKeyValue("fields", exclusions.skippedFields().makeString(", "))
				.log(
					"The search settings match words as they are spelled in fields"
						+ " this generation cannot answer for; forgiving mistakes"
						+ " in those fields as the definition says"
				);
		}

		return exclusions;
	}

	/**
	 * Search this index with its search settings in force.
	 *
	 * <p>The settings belong to the index name while this instance is one
	 * generation, so their ranking is compiled against this generation's schema
	 * as the search runs - an entry naming a field this generation does not
	 * have is skipped rather than failing the search, and logged once per
	 * version of the settings rather than once per search.
	 *
	 * <p>Collecting stops when the {@link SearchDeadline} of the calling thread
	 * runs out, and what was collected until then is returned as a result of
	 * its own. A caller that opened a budget asks the scope whether it ran out
	 * before it uses the result.
	 *
	 * @param request
	 *   what to look for, how to order it and how much to bring back
	 * @param settings
	 *   the search settings of the index, or {@code null} to search with the
	 *   definition alone
	 * @return
	 *   what was found, never {@code null}
	 * @throws IOException
	 */
	public SearchResult search(SearchRequest request, SearchSettings.Snapshot settings)
		throws IOException
	{
		syncLock.readLock().lock();
		try {
			if(state == IndexState.CLOSED) {
				throw new IndexClosedException(id);
			}

			try(var handle = searcherManager.acquire()) {
				var searcher = handle.getSearcher();

				/*
				 * The locale of the search decides which variant of a locale
				 * specific field is searched and sorted by. Asking for one
				 * this build has no rules for is refused rather than answered
				 * from the wrong variant.
				 */
				if(request.locale() != null && Locales.resolve(request.locale()).isEmpty()) {
					throw new IndexException(
						ERROR_UNSUPPORTED_SEARCH_LOCALE,
						"locale", request.locale()
					);
				}

				var compiler = new QueryCompiler(
					schema,
					request.locale(),
					nestedParents,
					compileRankingOverride(settings),
					compileSynonymOverlay(settings),
					compileTypoExclusions(settings)
				);
				var searched = request.query().newWithAll(request.filters());

				/*
				 * What a hit stands for is resolved before anything runs, so a
				 * field that can not answer as hits is refused however many
				 * results the search would have brought back.
				 */
				var hitsPath = request.hits() == null ? null : request.hits().path();

				/*
				 * A page that expands only some of its documents holds hits of
				 * both kinds, and every question below that reads one hit at a
				 * time has to ask which kind it has.
				 */
				var mixed = hitsPath != null && !request.hits().isEveryDocument();
				if(hitsPath != null) {
					compiler.hitsObjectField(hitsPath);
					valueFieldsReturnable(hitsPath, request.hits().fields());
				}

				var assembled = assemble(compiler, request, searched);
				var query = assembled.hits();

				/*
				 * Ordering by a value inside an object reads which values a
				 * document may be ordered by off the clauses of the search, so
				 * the order is decided by the same values the search matched.
				 * Hits that are the values themselves are ordered by their own
				 * fields instead, and a page holding both kinds by score alone.
				 */
				Sort sort;
				if(hitsPath == null) {
					sort = compiler.compileSort(request.sort(), searched);
				} else if(mixed) {
					sort = compiler.compileMixedSort(request.sort(), hitsPath);
				} else {
					sort = compiler.compileChildSort(request.sort(), hitsPath);
				}

				/*
				 * Resolved before anything runs, so a field that can not be
				 * highlighted is refused however many results the search brings
				 * back. On a value page the fields are resolved inside the
				 * path: each hit carries fragments of its own value.
				 */
				var highlightTargets = Lists.mutable.<Highlighter.Target>empty();
				for(var pair : request.highlight().keyValuesView()) {
					highlightTargets.add(new Highlighter.Target(
						pair.getOne(),
						hitsPath == null
							? compiler.highlightField(pair.getOne())
							: compiler.highlightValueField(hitsPath, pair.getOne()),
						pair.getTwo()
					));
				}

				/*
				 * Resolved for the same reason the highlights are - a field
				 * that can not answer which of its values matched is refused
				 * however many results the search brings back.
				 */
				for(var pair : request.matched().keyValuesView()) {
					compiler.objectField(pair.getOne());
					valueFieldsReturnable(pair.getOne(), pair.getTwo().fields());
				}

				/*
				 * What the search lets go of rather than come back empty, filled
				 * in below by whichever of the two paths found nothing. The
				 * request is replaced by the relaxed one as soon as that
				 * happens, so the facets, the total and the highlights all
				 * describe the search the hits actually came from.
				 */
				SearchResult.Relaxed relaxed = null;

				/*
				 * Lucene is asked for everything up to the end of the page, as
				 * ranking the results is what decides which of them the page holds.
				 */
				var wanted = (int) Math.min(
					(long) request.offset() + request.limit(),
					Integer.MAX_VALUE
				);

				/*
				 * A second pass reorders its window from the first result, so
				 * the whole window is ranked however few of it the page shows.
				 * The request has already been refused if the page reaches past
				 * it, which is what keeps this from shrinking `wanted`.
				 */
				var rescore = rescoring(request);
				if(rescore != null && wanted > 0) {
					wanted = rescore.window();
				}

				if(wanted == 0) {
					/*
					 * Nothing to rank and nothing to read. A search with a limit
					 * of zero and facets is how a filtering UI refreshes its
					 * counts without fetching hits, and counting those collects
					 * every match anyway - so that collection is made here and
					 * doubles as the exact total. Without facets, counting alone
					 * is the cheapest way to answer. Either number is also what
					 * says whether anything was found at all.
					 */
					var withFacets = !request.facets().isEmpty();
					FacetsCollector matches = null;
					Faceted counted = null;

					long count;
					if(withFacets && searched.isEmpty() && !mixed) {
						/*
						 * Nothing narrows the search, so its facets and total
						 * are the ones counting keeps per reader - collecting
						 * the matches here would pay for what those answers
						 * exist to avoid.
						 */
						counted = countFacets(searcher, compiler, request, searched, assembled, null);
						count = counted.total();
					} else if(withFacets && !mixed) {
						matches = searcher.search(query, new FacetsCollectorManager());
						count = matchCount(matches);
					} else {
						/*
						 * A mixed search counts its hits here whatever it asks
						 * for besides: the collection its facets need is of
						 * documents, and this number is of hits.
						 */
						count = searcher.count(query);
					}

					if(count == 0) {
						var outcome = relax(searcher, compiler, request);
						if(outcome != null) {
							request = request.withQuery(outcome.query());
							relaxed = outcome.relaxed();
							searched = request.query().newWithAll(request.filters());
							assembled = assemble(compiler, request, searched);
							query = assembled.hits();

							if(withFacets && !mixed) {
								matches = searcher.search(query, new FacetsCollectorManager());
								count = matchCount(matches);
							} else {
								count = searcher.count(query);
							}
						}
					}

					if(withFacets && counted == null) {
						counted = countFacets(searcher, compiler, request, searched, assembled, matches);
					}

					return new SearchResult(
						Lists.immutable.empty(),
						new SearchResult.Total(
							counted == null || mixed ? count : counted.total(),
							true
						),
						mixed
							? new SearchResult.Total(
								counted == null
									? searcher.count(assembled.documents())
									: counted.total(),
								true
							)
							: null,
						counted == null ? null : counted.counts(),
						relaxed
					);
				}

				var ranked = ranked(compiler, request, assembled, searched);

				/*
				 * Continuing from a key hands Lucene the position instead of
				 * ranking everything up to it, which is what keeps moving
				 * through results the same cost at any depth. Going backwards
				 * runs the mirror image of the sort, so the results are the
				 * ones just before the position - walked away from it, and
				 * turned back around below.
				 */
				var backwards = request.before() != null;
				var position = backwards ? request.before() : request.after();

				var window = rank(
					searcher,
					compiler,
					ranked,
					rescore,
					sort,
					wanted,
					position,
					backwards
				);
				var topDocs = window.topDocs();

				/*
				 * Nothing was found, so the search is run again with a word let
				 * go of. Continuing from a key is no reason to skip it: the
				 * count is of the whole search rather than of what follows the
				 * key, and the same text over the same index lets go of the same
				 * words, so page two of a relaxed search relaxes the same way
				 * page one did.
				 */
				if(topDocs.totalHits.value() == 0) {
					var outcome = relax(searcher, compiler, request);
					if(outcome != null) {
						request = request.withQuery(outcome.query());
						relaxed = outcome.relaxed();

						searched = request.query().newWithAll(request.filters());
						assembled = assemble(compiler, request, searched);
						query = assembled.hits();
						ranked = ranked(compiler, request, assembled, searched);

						window = rank(
							searcher,
							compiler,
							ranked,
							rescore,
							sort,
							wanted,
							position,
							backwards
						);
						topDocs = window.topDocs();
					}
				}

				/*
				 * Counted from the query the hits came from, so a relaxed search
				 * does not offer filters for a set of documents it is not
				 * showing. Counting collects every match, so it also knows the
				 * exact total. Collected on a pass of its own rather than while
				 * the page was ranked: a collector that has to see every match
				 * would keep the ranking from ending early and force a score for
				 * every document, which measures slower than the extra pass -
				 * see FacetBenchmark before reshaping this.
				 */
				var faceted = request.facets().isEmpty()
					? null
					: countFacets(searcher, compiler, request, searched, assembled, null);

				var reader = DocumentReader.inLocale(
					schema,
					request.fields(),
					request.locale()
				);
				var names = reader.namesOf();
				var primaryKey = schema.getPrimaryKey();

				if(!highlightTargets.isEmpty() && hitsPath == null && names != null) {
					/*
					 * The text a fragment is cut out of is a stored field of
					 * the same document the hit is built from, so it is read
					 * with the rest of the page rather than on a pass of its
					 * own. On a value page the text sits in the values' own
					 * documents instead, which are read below whatever is
					 * loaded here.
					 */
					for(var target : highlightTargets) {
						names.add(Highlighter.storedField(target.luceneField()));
					}
				}

				if(request.matched().notEmpty() && names != null) {
					/*
					 * The matched values are read out of the copy of the
					 * document, whether or not `fields` needs it - which is
					 * what answers them even when the path itself was not
					 * asked back.
					 */
					names.add(FieldNames.SOURCE);
				}

				if(hitsPath != null && names != null) {
					/*
					 * The value each hit stands for is read out of the copy of
					 * the document holding it, whether or not `fields` asked
					 * the path back.
					 */
					names.add(FieldNames.SOURCE);
				}

				/*
				 * Where the page ends among what was ranked. A second pass
				 * ranks its whole window so that it reorders from the first
				 * result, which leaves results ranked that the page does not
				 * show.
				 */
				var pageEnd = (int) Math.min(
					topDocs.scoreDocs.length,
					(long) request.offset() + request.limit()
				);

				var docIds = new int[Math.max(0, pageEnd - request.offset())];
				for(var i = 0; i < docIds.length; i++) {
					docIds[i] = topDocs.scoreDocs[request.offset() + i].doc;
				}

				/*
				 * A hit standing for a value is materialized from the document
				 * above it: where each value of the page sits is worked out in
				 * one pass, and the stored fields read are those of the
				 * documents rather than of the values, which carry none.
				 */
				IntObjectMap<MatchedChildren.Location> locations = null;
				var stored = docIds;
				if(hitsPath != null) {
					locations = MatchedChildren.locate(searcher, nestedParents, hitsPath, docIds);

					/*
					 * A hit that was located is a value and is read from the
					 * document above it; one that was not is a document and is
					 * read from itself.
					 */
					var parents = IntSets.mutable.empty();
					for(var docId : docIds) {
						var location = locations.get(docId);
						parents.add(location == null ? docId : location.parent());
					}

					stored = parents.toArray();
				}

				var page = readStored(searcher, stored, names);

				/*
				 * Without copies of the documents, the values of nested lists
				 * live in documents of their own. Read here for whatever needs
				 * them: the fields being answered and the matched values being
				 * reported.
				 */
				IntObjectMap<MapIterable<String, ListIterable<org.apache.lucene.document.Document>>> children = null;
				if(!schema.isSourceStored()) {
					var matchedBlocks = request.matched().keysView()
						.select(path -> schema.getNestedFields(path)
							.anySatisfy(Field::isStored))
						.toSet();

					if(reader.needsChildren() || matchedBlocks.notEmpty()) {
						children = readChildren(
							searcher,
							stored,
							path -> reader.wantsChildren(path) || matchedBlocks.contains(path)
						);
					}
				}

				/*
				 * The documents of the hits standing for values, holding what
				 * only they can hold: the value's stored fields when the index
				 * keeps no copies, and the text highlighting cuts fragments
				 * from either way.
				 */
				IntObjectMap<org.apache.lucene.document.Document> valueDocs = null;
				if(hitsPath != null
					&& (!highlightTargets.isEmpty()
						|| (!schema.isSourceStored()
							&& schema.getNestedFields(hitsPath).anySatisfy(Field::isStored)))) {
					valueDocs = readValueDocuments(searcher, locations, docIds);
				}

				ListIterable<ImmutableMap<String, ImmutableList<String>>> highlights = null;
				if(!highlightTargets.isEmpty()) {
					highlights = highlight(
						searcher,
						compiler,
						highlightTargets,
						request,
						hitsPath,
						docIds,
						hitsPath == null ? page : valueDocs
					);
				}

				/*
				 * Which values of each asked-about object field matched, found
				 * for the whole page at once - and off the settled clauses, so
				 * a relaxed search reports the values its hits actually came
				 * from.
				 */
				MapIterable<String, MatchedPath> matchedPaths = null;
				if(request.matched().notEmpty()) {
					var found = Maps.mutable.<String, MatchedPath>empty();
					for(var pair : request.matched().keyValuesView()) {
						var path = pair.getOne();
						var options = pair.getTwo();
						var valueScores = compiler.matchedValuesScore(path, searched);
						found.put(path, new MatchedPath(
							MatchedChildren.find(
								searcher,
								compiler.compileMatchedValues(path, searched, valueScores),
								valueScores,
								nestedParents,
								path,
								docIds
							),
							valueScores,
							options.fields().isEmpty()
								? null
								: options.fields().collect(
									name -> name.substring(path.length() + 1)
								)
						));
					}

					matchedPaths = found;
				}

				/*
				 * The copy holds a deep path's values inside the field at the
				 * root of its object chain, so that is the field to keep
				 * readable.
				 */
				var matchedNames = request.matched().keysView()
					.collect(path -> schema.getFlattenedObjectOf(path).orElse(path))
					.toSet();

				var hits = Lists.mutable.<SearchResult.Hit>empty();
				if(hitsPath != null) {
					/*
					 * Several hits can stand for values of one document, so the
					 * copy is decoded once per document of the page rather than
					 * once per hit.
					 */
					var decoded = IntObjectMaps.mutable.<DocumentReader.WithSource>empty();
					var alsoDecode = Sets.immutable.of(
						schema.getFlattenedObjectOf(hitsPath).orElse(hitsPath)
					);

					/*
					 * The request names fields by their dotted paths; cut()
					 * keeps by the name a field has inside the value.
					 */
					var inside = request.hits().fields().isEmpty()
						? null
						: request.hits().fields().collect(
							name -> name.substring(hitsPath.length() + 1)
						);

					var hitsKey = schema.getField(hitsPath)
						.map(Field::getObjectKey)
						.orElse(null);

					for(var i = request.offset(); i < pageEnd; i++) {
						var scoreDoc = topDocs.scoreDocs[i];
						var location = locations.get(scoreDoc.doc);

						if(location == null) {
							/*
							 * A document the search was told not to expand,
							 * answering as itself among the values of the ones
							 * it was.
							 */
							var itself = reader.read(
								page.get(scoreDoc.doc),
								children == null ? null : children.get(scoreDoc.doc)
							);
							hits.add(
								new SearchResult.Hit(
									primaryKey.map(field -> itself.get(field.getName())).orElse(null),
									Float.isNaN(scoreDoc.score) ? 0f : scoreDoc.score,
									itself,
									SortKeys.keyOf(scoreDoc, backwards),
									/*
									 * The highlighted fields are inside the
									 * path, and a document answering as itself
									 * holds no value of it - the entry is
									 * empty rather than absent, so one page
									 * reads uniformly.
									 */
									highlights == null
										? null
										: highlights.get(i - request.offset()),
									null
								)
							);
							continue;
						}

						var withSource = decoded.get(location.parent());
						if(withSource == null) {
							withSource = reader.readWithSource(
								page.get(location.parent()),
								alsoDecode,
								children == null ? null : children.get(location.parent())
							);
							decoded.put(location.parent(), withSource);
						}

						var document = withSource.document();

						/*
						 * The block and the copy hold the values in the order
						 * the document gave them, so the position found among
						 * the children is the position in the copy. A value
						 * that is not there was indexed before a copy was
						 * kept, and is left out the way a whole missing copy
						 * is - the hit still answers its document and its
						 * position.
						 */
						Document value = null;
						String valueKey = null;
						if(withSource.source() != null) {
							var all = valuesAt(withSource.source(), hitsPath);
							if(location.ordinal() < all.size()
								&& all.get(location.ordinal()) instanceof Document valueDoc) {
								/*
								 * Read before cutting, so that a search asking
								 * for some of the fields inside still gets the
								 * key - it says which value the hit is, not
								 * what the hit shows.
								 */
								if(hitsKey != null) {
									var reads = valueDoc.get(hitsKey);
									valueKey = reads == null ? null : String.valueOf(reads);
								}

								value = inside == null
									? valueDoc
									: cut(valueDoc, inside);
							}
						} else if(valueDocs != null) {
							/*
							 * Without a copy the hit's own document is what
							 * holds the value - as far as its stored fields
							 * reach, the way any document reads without one.
							 */
							var valueStored = valueDocs.get(scoreDoc.doc);
							if(valueStored != null) {
								var valueDoc = reader.readNestedValue(hitsPath, valueStored);
								if(hitsKey != null) {
									var reads = valueDoc.get(hitsKey);
									valueKey = reads == null ? null : String.valueOf(reads);
								}

								value = inside == null
									? valueDoc
									: cut(valueDoc, inside);
							}
						}

						hits.add(
							new SearchResult.Hit(
								primaryKey.map(field -> document.get(field.getName())).orElse(null),
								location.ordinal(),
								valueKey,
								Float.isNaN(scoreDoc.score) ? 0f : scoreDoc.score,
								document,
								value,
								SortKeys.keyOf(scoreDoc, backwards),
								highlights == null
									? null
									: highlights.get(i - request.offset()),
								null
							)
						);
					}
				} else {
					for(var i = request.offset(); i < pageEnd; i++) {
						var scoreDoc = topDocs.scoreDocs[i];

						Document document;
						ImmutableMap<String, SearchResult.Matched> matched = null;
						if(matchedPaths == null) {
							document = reader.read(
								page.get(scoreDoc.doc),
								children == null ? null : children.get(scoreDoc.doc)
							);
						} else {
							var withSource = reader.readWithSource(
								page.get(scoreDoc.doc),
								matchedNames,
								children == null ? null : children.get(scoreDoc.doc)
							);
							document = withSource.document();
							matched = matchedOf(
								request,
								matchedPaths,
								scoreDoc.doc,
								withSource.source(),
								reader,
								children == null ? null : children.get(scoreDoc.doc)
							);
						}

						hits.add(
							new SearchResult.Hit(
								primaryKey.map(field -> document.get(field.getName())).orElse(null),
								/*
								 * Ordering by a field with nothing in the query
								 * to score leaves Lucene with no score to
								 * report, which is no score rather than an
								 * unusable number.
								 */
								Float.isNaN(scoreDoc.score) ? 0f : scoreDoc.score,
								document,
								SortKeys.keyOf(scoreDoc, backwards),
								highlights == null
									? null
									: highlights.get(i - request.offset()),
								matched
							)
						);
					}
				}

				if(backwards) {
					// Walked away from the position, read toward it
					hits.reverseThis();
				}

				var total = new SearchResult.Total(
					topDocs.totalHits.value(),
					topDocs.totalHits.relation() == TotalHits.Relation.EQUAL_TO
				);

				/*
				 * Lucene stops counting once it knows there are more matches
				 * than were asked for, so an exact total that was not reached
				 * on the way is counted on its own - unless the facets already
				 * collected every match, in which case their count is the
				 * whole number for free. What a mixed search counted for its
				 * facets is a number of documents and answers below instead:
				 * the total is of hits, which is the unit the page moves in.
				 */
				if(faceted != null && !mixed) {
					total = new SearchResult.Total(faceted.total(), true);
				} else if(request.total() == SearchRequest.Total.EXACT && !total.exact()) {
					total = new SearchResult.Total(searcher.count(query), true);
				}

				/*
				 * How many documents a mixed page's hits came from, which its
				 * facets are counted in and its total is not. Free where the
				 * facets already collected every document, and a count of the
				 * query already in hand otherwise.
				 */
				SearchResult.Total documents = null;
				if(mixed) {
					documents = new SearchResult.Total(
						faceted == null
							? searcher.count(assembled.documents())
							: faceted.total(),
						true
					);
				}

				return new SearchResult(
					hits.toImmutable(),
					total,
					documents,
					faceted == null ? null : faceted.counts(),
					relaxed,
					window.end()
				);
			}
		} finally {
			syncLock.readLock().unlock();
		}
	}

	/**
	 * Get how one hit scores under a search.
	 *
	 * <p>The search is compiled the way {@link #search} compiles it - the same
	 * clauses, locale and ranking, and relaxed the same way when it matches
	 * nothing - so the score reported here is the score a search reports for
	 * the hit.
	 *
	 * <p>Paging, sorting, facets, highlights and {@code matched} are not read.
	 * A search ordered by a field is still scored, and the score is what this
	 * answers for.
	 *
	 * <p>A hit the search does not match is explained rather than refused, with
	 * the clauses that ruled it out marked as not matching.
	 *
	 * <p>The {@link SearchDeadline} of the calling thread bounds this the way
	 * it bounds a search.
	 *
	 * @param request
	 * @param primaryKey
	 *   the key of the document, as the type of the key field holds it
	 * @param valueIndex
	 *   which value of the request's {@code hits} path to explain, counted the
	 *   way a hit reports its position. Read only where the document answers
	 *   with its values - never for a search whose hits are documents, and
	 *   never for a document the request's {@code when} leaves as a hit of its
	 *   own, which is explained as the document it answers as
	 * @param settings
	 *   the search settings of the index, or {@code null} to explain under the
	 *   definition alone
	 * @return
	 *   never {@code null}
	 * @throws IndexClosedException
	 *   if this instance has been closed
	 * @throws IndexNoPrimaryKeyException
	 *   if the definition of the index declares no primary key
	 * @throws IndexException
	 *   with {@code index:explain:document_not_found} if no document is indexed
	 *   under the key, {@code index:explain:value_not_found} if it holds no
	 *   value of the path at the position, or
	 *   {@code index:query:unsupported_locale} if the search asks for a locale
	 *   this build has no rules for
	 * @throws IOException
	 */
	public SearchExplanation explain(
		SearchRequest request,
		Object primaryKey,
		int valueIndex,
		SearchSettings.Snapshot settings
	) throws IOException {
		syncLock.readLock().lock();
		try {
			if(state == IndexState.CLOSED) {
				throw new IndexClosedException(id);
			}

			try(var handle = searcherManager.acquire()) {
				/*
				 * Over the same reader as a search, with the query cache off. A
				 * weight that does not score is handed to the cache, whose
				 * explanation is the query's own toString and nothing below it -
				 * which loses every clause a filter was compiled from. Explaining
				 * visits one document, so there is nothing for a cache to save.
				 */
				var searcher = newSearcher(handle.getSearcher().getIndexReader());
				searcher.setQueryCache(null);

				if(request.locale() != null && Locales.resolve(request.locale()).isEmpty()) {
					throw new IndexException(
						ERROR_UNSUPPORTED_SEARCH_LOCALE,
						"locale", request.locale()
					);
				}

				var compiler = new QueryCompiler(
					schema,
					request.locale(),
					nestedParents,
					compileRankingOverride(settings),
					compileSynonymOverlay(settings),
					compileTypoExclusions(settings)
				);
				compiler.markClauses(request.query(), request.filters());

				var searched = request.query().newWithAll(request.filters());

				var hitsPath = request.hits() == null ? null : request.hits().path();
				org.apache.lucene.search.Query expands = null;
				if(hitsPath != null) {
					compiler.hitsObjectField(hitsPath);

					if(!request.hits().isEveryDocument()) {
						expands = compiler.compile(request.hits().when());
					}
				}

				var assembled = assemble(compiler, request, searched);
				var ranked = ranked(compiler, request, assembled, searched);

				/*
				 * A search matching nothing lets go of a word and runs again, so
				 * an explanation follows it there rather than describe a search
				 * that answered nobody. Counted on the assembled query rather than
				 * the ranked one, as what ranks a search never changes what it
				 * matches.
				 */
				SearchResult.Relaxed relaxed = null;
				if(searcher.count(assembled.hits()) == 0) {
					var outcome = relax(searcher, compiler, request);
					if(outcome != null) {
						request = request.withQuery(outcome.query());
						relaxed = outcome.relaxed();

						// Relaxing builds clauses of its own, which the marks have
						// to be worked out over before anything is compiled again
						compiler.markClauses(request.query(), request.filters());

						searched = request.query().newWithAll(request.filters());
						assembled = assemble(compiler, request, searched);
						ranked = ranked(compiler, request, assembled, searched);
					}
				}

				var explanation = searcher.explain(
					ranked.query(),
					explained(searcher, primaryKey, hitsPath, expands, valueIndex)
				);

				return new SearchExplanation(
					explanation.isMatch(),
					explanation.isMatch() ? explanation.getValue().floatValue() : 0f,
					Explanations.of(explanation),
					relaxed
				);
			}
		} finally {
			syncLock.readLock().unlock();
		}
	}

	/**
	 * Find the Lucene document an explanation is asked about: the document
	 * itself, or one of the values of an object field when the hits of the
	 * search are values.
	 *
	 * @param searcher
	 * @param primaryKey
	 * @param hitsPath
	 *   the object field the hits are values of, or {@code null} when they are
	 *   documents
	 * @param expands
	 *   the compiled condition deciding which documents answer with their
	 *   values, or {@code null} where every document does. A document the
	 *   condition leaves out is a hit standing for itself, and is explained as
	 *   one however the position was given
	 * @param valueIndex
	 *   position of the value among the document's values of the path
	 * @return
	 * @throws IOException
	 */
	private int explained(
		IndexSearcher searcher,
		Object primaryKey,
		String hitsPath,
		org.apache.lucene.search.Query expands,
		int valueIndex
	) throws IOException {
		var primaryKeyField = primaryKeyField();

		var encounter = new IndexEncounterImpl(
			schema.getResources(),
			schema.isHighlightingInPostings()
		);
		encounter.updateLocale(DEFAULT_LOCALE_SUPPORT);
		encounter.updateValue(primaryKeyField.getName(), primaryKeyField.getDef());

		var term = primaryKeyField.getType().createPrimaryKeyTerm(encounter, primaryKey);

		/*
		 * The values of object fields carry the primary key of their document
		 * too, so the lookup has to say it wants the document of the block.
		 */
		var found = searcher.search(parentsOnly(new TermQuery(term)), 1);
		if(found.totalHits.value() == 0) {
			throw new IndexException(
				ERROR_EXPLAIN_DOCUMENT_NOT_FOUND,
				"key", String.valueOf(primaryKey)
			);
		}

		var document = found.scoreDocs[0].doc;
		if(hitsPath == null) {
			return document;
		}

		if(expands != null && !matches(searcher, expands, document)) {
			return document;
		}

		var value = MatchedChildren.child(
			searcher,
			nestedParents,
			hitsPath,
			document,
			valueIndex
		);

		if(value < 0) {
			throw new IndexException(
				ERROR_EXPLAIN_VALUE_NOT_FOUND,
				"key", String.valueOf(primaryKey),
				"path", hitsPath,
				"index", String.valueOf(valueIndex)
			);
		}

		return value;
	}

	/**
	 * Get whether one Lucene document matches a query, asked of the document
	 * rather than of the index: the query is run over the segment holding it
	 * and stopped as soon as it is reached or passed.
	 *
	 * @param searcher
	 * @param query
	 * @param docId
	 *   Lucene id of the document, over the searcher's reader
	 * @return
	 * @throws IOException
	 */
	private static boolean matches(
		IndexSearcher searcher,
		org.apache.lucene.search.Query query,
		int docId
	) throws IOException {
		var reader = searcher.getIndexReader();
		var context = reader.leaves().get(ReaderUtil.subIndex(docId, reader.leaves()));

		var weight = searcher.createWeight(
			searcher.rewrite(query),
			ScoreMode.COMPLETE_NO_SCORES,
			1f
		);

		var scorer = weight.scorer(context);
		if(scorer == null) {
			return false;
		}

		var wanted = docId - context.docBase;
		return scorer.iterator().advance(wanted) == wanted;
	}

	/**
	 * Ask Lucene for a page of results.
	 *
	 * @param searcher
	 * @param ranked
	 *   the query to run, with whatever ranks it already applied
	 * @param sort
	 *   the order to return results in, {@code null} for the best matches first
	 * @param wanted
	 *   how many results to rank, counted from the start rather than from the
	 *   page
	 * @param position
	 *   the hit to continue from, or {@code null} to start at the beginning
	 * @param backwards
	 *   if the results are the ones before the position rather than after it
	 * @param scores
	 *   if the score of each hit is worth computing
	 * @return
	 * @throws IOException
	 */
	private TopDocs topDocs(
		IndexSearcher searcher,
		org.apache.lucene.search.Query ranked,
		Sort sort,
		int wanted,
		SortKey position,
		boolean backwards,
		boolean scores
	) throws IOException {
		if(position == null) {
			return sort == null
				? searcher.search(ranked, wanted)
				: searcher.search(ranked, wanted, sort, scores);
		}

		if(sort == null && !backwards) {
			return searcher.searchAfter(
				SortKeys.toAfter(position, null, false),
				ranked,
				wanted
			);
		}

		return searcher.searchAfter(
			SortKeys.toAfter(position, sort, backwards),
			ranked,
			wanted,
			backwards ? SortKeys.reverse(sort) : sort,
			scores
		);
	}

	/**
	 * Work out what a search that found nothing can let go of to find
	 * something.
	 *
	 * Reached only from an empty page, which is what keeps relaxing from ever
	 * taking a result away: a search that found something never gets here. What
	 * it costs - a count per word of the text, and a pass per word let go of -
	 * is paid for by a search that was about to answer with nothing at all.
	 *
	 * @param searcher
	 * @param compiler
	 *   the compiler that compiled the search, still pointed at its locale
	 * @param request
	 * @return
	 *   what to search with instead and what was let go, or {@code null} when
	 *   there was nothing to give up or giving it up found nothing either
	 * @throws IOException
	 */
	private Relaxation.Outcome relax(
		IndexSearcher searcher,
		QueryCompiler compiler,
		SearchRequest request
	) throws IOException {
		var relaxation = Relaxation.of(request.query());
		if(relaxation == null) {
			return null;
		}

		/*
		 * Assembled rather than compiled directly, so every question relaxing
		 * asks counts the unit the search answers with - value hits for a
		 * search whose hits are values. The heuristics compare the counts of
		 * words against each other, which holds for values the way it does for
		 * documents.
		 */
		return relaxation.run(
			clauses -> {
				var whole = clauses.newWithAll(request.filters());
				return anyMatch(searcher, assemble(compiler, request, whole).hits());
			},
			clauses -> searcher.count(assemble(compiler, request, clauses).hits())
		);
	}

	/**
	 * Ask whether a query matches anything, without working out how much.
	 *
	 * The search stops at the first document it finds and scores nothing,
	 * which is what separates this from counting - a query matching most of
	 * the index answers as quickly as one matching a single document.
	 *
	 * @param searcher
	 * @param query
	 * @return
	 * @throws IOException
	 */
	private static boolean anyMatch(
		IndexSearcher searcher,
		org.apache.lucene.search.Query query
	) throws IOException {
		return searcher.search(query, new CollectorManager<AnyMatch, Boolean>() {
			@Override
			public AnyMatch newCollector() {
				return new AnyMatch();
			}

			@Override
			public Boolean reduce(Collection<AnyMatch> collectors) {
				return collectors.stream().anyMatch(AnyMatch::found);
			}
		});
	}

	/**
	 * Remembers that a document was found and then refuses to look at any
	 * more, which is how {@link #anyMatch} stops at the first one.
	 */
	private static final class AnyMatch implements Collector {
		private boolean found;

		boolean found() {
			return found;
		}

		@Override
		public LeafCollector getLeafCollector(LeafReaderContext context) {
			if(found) {
				// An earlier segment answered, so the rest are not read at all
				throw new CollectionTerminatedException();
			}

			return new LeafCollector() {
				@Override
				public void setScorer(Scorable scorer) {
				}

				@Override
				public void collect(int doc) {
					found = true;
					throw new CollectionTerminatedException();
				}
			};
		}

		@Override
		public ScoreMode scoreMode() {
			return ScoreMode.COMPLETE_NO_SCORES;
		}
	}

	/**
	 * Refuse a field selection on values the index could never answer - the
	 * same rule whether the values come back beside each hit ({@code matched})
	 * or as the hits themselves ({@code hits}).
	 *
	 * Judged the way {@link DocumentReader} judges what {@code fields} asks
	 * back: refused rather than answered with the field left out of every
	 * value, which reads as values that never held it.
	 *
	 * @param path
	 *   name of the object field whose values are being reported
	 * @param fields
	 *   the fields of each value asked back, by their dotted paths
	 * @throws IndexSourceRequiredException
	 *   if only the copy of the document could answer a name and the index
	 *   keeps none - an object inside the value, or a field below a flattened
	 *   list there
	 * @throws IndexFieldUsageException
	 *   if a name could answer without the copy by being stored, and is not
	 * @throws IndexFieldNotFoundException
	 *   if a name is not a field inside the object
	 */
	private void valueFieldsReturnable(String path, SetIterable<String> fields) {
		for(var name : fields) {
			var nested = schema.getNestedField(name);
			if(nested.isEmpty() || !nested.get().path().equals(path)) {
				throw new IndexFieldNotFoundException(name);
			}

			if(!schema.isSourceStored()) {
				var field = nested.get().field();
				if(field.isObject()
					|| DocumentReader.underFlattenedList(schema, field, name)) {
					throw new IndexSourceRequiredException(name);
				}

				if(!field.isStored()) {
					throw new IndexFieldUsageException(name, "stored");
				}
			}
		}
	}

	/**
	 * The matched values of one asked-about object field, found for a whole
	 * page of results.
	 *
	 * @param found
	 *   the matches keyed by Lucene id, a document with none absent
	 * @param ranked
	 *   whether the clauses on the field rank its values, which is what orders
	 *   them by how well each matched rather than as the document gave them
	 * @param inside
	 *   the fields to keep of each value, by their name inside it, or
	 *   {@code null} to hand the values back whole
	 */
	private record MatchedPath(
		IntObjectMap<MatchedChildren.Matches> found,
		boolean ranked,
		SetIterable<String> inside
	) {
	}

	/**
	 * Shape which values matched for one hit, for every object field the
	 * search asked about.
	 *
	 * @param request
	 * @param matchedPaths
	 *   what {@link MatchedChildren} found for the page, per path
	 * @param doc
	 *   Lucene id of the hit
	 * @param source
	 *   the copy of the hit's document, or {@code null} when it has none
	 * @param reader
	 *   the reader of the page, which is what turns a value's own document
	 *   back into the value when there is no copy to read it from
	 * @param children
	 *   the documents of the hit's nested lists' values per path, or
	 *   {@code null} when none were read - without them and without a copy
	 *   the values are left out and only their number answers
	 * @return
	 */
	private ImmutableMap<String, SearchResult.Matched> matchedOf(
		SearchRequest request,
		MapIterable<String, MatchedPath> matchedPaths,
		int doc,
		Document source,
		DocumentReader reader,
		MapIterable<String, ListIterable<org.apache.lucene.document.Document>> children
	) {
		var result = Maps.mutable.<String, SearchResult.Matched>empty();

		request.matched().forEachKeyValue((path, options) -> {
			var forPath = matchedPaths.get(path);
			var matches = forPath.found().get(doc);
			if(matches == null) {
				matches = MatchedChildren.none();
			}

			var ordinals = matches.ordinals();
			if(forPath.ranked() && ordinals.length > 1) {
				ordinals = byScore(ordinals, matches.scores());
			}

			ImmutableList<Document> values = null;
			if(source != null) {
				var all = valuesAt(source, path);
				var picked = Lists.mutable.<Document>empty();
				var wanted = Math.min(options.limit(), ordinals.length);
				for(var i = 0; i < wanted; i++) {
					/*
					 * The block and the copy hold the values in the order the
					 * document gave them, so the position found among the
					 * children is the position in the copy. A value that is
					 * not there is one indexed before the copy was kept, and
					 * is left out the way a whole missing copy is.
					 */
					if(ordinals[i] < all.size()
						&& all.get(ordinals[i]) instanceof Document value) {
						picked.add(
							forPath.inside() == null
								? value
								: cut(value, forPath.inside())
						);
					}
				}

				values = picked.toImmutable();
			} else if(children != null && children.containsKey(path)) {
				/*
				 * Without a copy the values answer from their own documents,
				 * as far as their stored fields reach. The children come in
				 * block order, so the position found among them indexes
				 * straight into the list.
				 */
				var all = children.get(path);
				var picked = Lists.mutable.<Document>empty();
				var wanted = Math.min(options.limit(), ordinals.length);
				for(var i = 0; i < wanted; i++) {
					if(ordinals[i] < all.size()) {
						var value = reader.readNestedValue(path, all.get(ordinals[i]));
						picked.add(
							forPath.inside() == null
								? value
								: cut(value, forPath.inside())
						);
					}
				}

				values = picked.toImmutable();
			}

			result.put(path, new SearchResult.Matched(values, matches.count()));
		});

		return result.toImmutable();
	}

	/**
	 * Cut one matched value down to the fields that were asked for inside it.
	 * A field is kept by the name it has inside the value - a deeper one by
	 * the remaining path through the objects between - so every locale
	 * variant of a named field comes along, the same as when {@code fields}
	 * cuts an object.
	 */
	private static Document cut(Document value, SetIterable<String> names) {
		return DocumentReader.cutInner(value, names);
	}

	/**
	 * The values a document's copy holds at a concrete dotted path, in the
	 * order the document gave them. For a path below other objects that is
	 * the depth first order over the chain, which is the order the values'
	 * documents were written to the block in - so the position found among
	 * the children of a path is the position here.
	 *
	 * <p>Walked along the object chain the schema declares rather than by
	 * splitting the path, because a declared name may itself hold a dot and
	 * only the chain says where one object's name ends.
	 *
	 * @param source
	 *   the copy of the document
	 * @param path
	 *   concrete dotted path of an object field
	 * @return
	 *   the values, empty when the document holds none
	 */
	private List<Object> valuesAt(Document source, String path) {
		var chain = Lists.mutable.<String>empty();

		var name = path;
		var field = schema.getField(name).orElse(null);
		while(field != null) {
			var parent = schema.getEnclosingObjectOf(field, name).orElse(null);
			if(parent == null) {
				chain.add(0, name);
				break;
			}

			chain.add(0, name.substring(parent.length() + 1));
			name = parent;
			field = schema.getField(parent).orElse(null);
		}

		if(field == null) {
			return List.of();
		}

		var values = Lists.mutable.empty();
		collectAt(source, chain, 0, values);
		return values;
	}

	private static void collectAt(
		Document doc,
		ListIterable<String> chain,
		int level,
		MutableList<Object> values
	) {
		var name = chain.get(level);
		for(var value : doc.fields()) {
			if(!name.equals(value.name())) {
				continue;
			}

			if(level == chain.size() - 1) {
				values.add(value.value());
			} else if(value.value() instanceof Document inner) {
				collectAt(inner, chain, level + 1, values);
			}
		}
	}

	/**
	 * Order the matched values best first, keeping the order the document gave
	 * them between values that scored the same.
	 */
	private static int[] byScore(int[] ordinals, float[] scores) {
		var order = new Integer[ordinals.length];
		for(var i = 0; i < order.length; i++) {
			order[i] = i;
		}

		Arrays.sort(order, (a, b) -> Float.compare(scores[b], scores[a]));

		var sorted = new int[ordinals.length];
		for(var i = 0; i < sorted.length; i++) {
			sorted[i] = ordinals[order[i]];
		}

		return sorted;
	}

	/**
	 * What counting the facets of a search found.
	 *
	 * @param counts
	 *   the counts per value, keyed by the name of each facet
	 * @param total
	 *   how many documents the whole search matches. Counting collects every
	 *   match of the query and filters, so the number is exact and the search
	 *   does not have to count again
	 */
	private record Faceted(
		ImmutableMap<String, SearchResult.Facet> counts,
		long total
	) {
	}

	/**
	 * Count the matches per value for every facet of the search.
	 *
	 * A facet is counted sideways of the filter entries it
	 * {@link Facet#excludes(String) excludes} - by default the ones on its own
	 * field: those are left out of its scope, so ticking a value keeps the
	 * other values of that field visible and countable, while the query and
	 * every other filter narrow the counts the way they narrow the hits.
	 * Facets that exclude no filter of the search all share the scope of the
	 * search itself, so the matches are collected once per distinct scope
	 * rather than once per facet. The facets of one scope are also counted in
	 * one walk of those matches - see {@link FacetWalk} - so another facet
	 * adds the doc values it reads, not another iteration.
	 *
	 * A facet over a field inside an object counts the values of that object
	 * instead - the ones the search matched, which is what its {@code nested}
	 * clauses say - and rolls them up, so it answers how many documents hold
	 * each value the way every other facet does. Its scope is worked out the
	 * same way, a {@code nested} filter being excludable by its field path
	 * like any other entry, and facets that keep the same filters share one
	 * scope per object field.
	 *
	 * A facet over a field whose values are paths counts a level of the tree at
	 * a time and answers the counts nested. The scope is worked out the same
	 * way, which is what keeps the sideways rule true down a tree: the filter
	 * that drilled into a level is a filter on the facet's own field, so it is
	 * left out and the levels beside the chosen one stay countable.
	 *
	 * A scope with no clauses left in it is everything the index holds, whose
	 * counts change only when the reader does - a search with facets but
	 * nothing narrowing it, and the facet counting sideways of the only filter
	 * of the search, both ask for them. Those are answered per reader through
	 * {@link FacetStates}, and the matches of the search are only collected
	 * when something still needs them - a search every facet of which is
	 * answered that way pays a count for its total instead of a collection.
	 *
	 * A search whose hits are the values of an object field, whatever the
	 * document, counts differently enough - the counts are of values, and
	 * nothing per reader answers those - that it is counted apart, in
	 * {@link #countValueFacets}. A search that expands only some of its
	 * documents is counted here: its page holds hits of both kinds, and the
	 * only count that describes all of them is of the documents they came
	 * from. A colour holding twelve products holds twelve however many of them
	 * chose to answer as their variants.
	 *
	 * @param clauses
	 *   the clauses the search ran with, query and filters together
	 * @param assembled
	 *   those clauses assembled, for the documents of the search and the query
	 *   its hits come from
	 * @param whole
	 *   the matches of the search where the caller already collected them, or
	 *   {@code null} to collect them here if a facet or the total needs them -
	 *   either way the scope of every facet no filter names
	 */
	private Faceted countFacets(
		IndexSearcher searcher,
		QueryCompiler compiler,
		SearchRequest request,
		ListIterable<Query> clauses,
		Assembled assembled,
		FacetsCollector whole
	) throws IOException {
		if(request.hits() != null && request.hits().isEveryDocument()) {
			return countValueFacets(searcher, compiler, request, clauses, assembled, whole);
		}

		var documents = assembled.documents();
		var reader = searcher.getIndexReader();
		var filterPaths = request.filters().collect(Index::filterPathOf);

		/*
		 * The matches of everything the index holds, collected the first time
		 * a whole-index scope is not already answered per reader. The whole
		 * search is that collection when nothing narrows it.
		 */
		var everything = clauses.isEmpty() ? whole : null;

		var collectors = Maps.mutable.<ImmutableList<Query>, FacetMatches>empty();
		var values = Maps.mutable.<Pair<String, ImmutableList<Query>>, FacetMatches>empty();
		var counts = Maps.mutable.<String, SearchResult.Facet>empty();
		var walks = Maps.mutable.<FacetMatches, MutableList<PendingFacet>>empty();

		for(var facet : request.facets()) {
			var filters = keptFilters(request.filters(), filterPaths, facet);
			var sideways = filters.size() != request.filters().size();

			var nested = schema.getNestedField(facet.field());
			var scoped = sideways
				? request.query().newWithAll(filters)
				: clauses;

			FacetMatches scope;
			var keepWhole = false;
			if(nested.isPresent()) {
				var path = nested.get().path();

				if(scoped.isEmpty()) {
					var kept = FacetStates.wholeCountsOf(reader, request.locale(), facet);
					if(kept != null) {
						counts.put(facet.name(), kept);
						continue;
					}

					keepWhole = true;
				}

				var key = Tuples.pair(path, filters);
				scope = values.get(key);
				if(scope == null) {
					var scopedDocuments = sideways
						? assemble(compiler, request, scoped).documents()
						: documents;

					scope = FacetMatches.rolledUp(
						searcher.search(
							compiler.compileNestedValues(path, scopedDocuments, scoped),
							new FacetsCollectorManager()
						),
						nestedParents
					);
					values.put(key, scope);
				}
			} else {
				if(scoped.isEmpty()) {
					var kept = FacetStates.wholeCountsOf(reader, request.locale(), facet);
					if(kept == null) {
						if(everything == null) {
							everything = collectEverything(searcher, compiler);
						}

						var wholeScope = FacetMatches.of(everything);
						walks.getIfAbsentPut(wholeScope, Lists.mutable::empty).add(
							new PendingFacet(facet, prepareFacet(compiler, facet, wholeScope.mode()), true)
						);
					} else {
						counts.put(facet.name(), kept);
					}

					continue;
				}

				if(sideways) {
					scope = collectors.get(filters);
					if(scope == null) {
						scope = FacetMatches.of(
							searcher.search(
								assemble(compiler, request, scoped).documents(),
								new FacetsCollectorManager()
							)
						);
						collectors.put(filters, scope);
					}
				} else {
					if(whole == null) {
						whole = searcher.search(documents, new FacetsCollectorManager());
					}

					scope = FacetMatches.of(whole);
				}
			}

			walks.getIfAbsentPut(scope, Lists.mutable::empty).add(
				new PendingFacet(facet, prepareFacet(compiler, facet, scope.mode()), keepWhole)
			);
		}

		countWalks(reader, request, walks, counts);

		long total;
		if(whole != null) {
			total = matchCount(whole);
		} else if(!clauses.isEmpty()) {
			/*
			 * Every facet found its scope without the matches of the search,
			 * so the exact total the caller is promised is counted on its own
			 * - counting skips the collection a facet would have needed.
			 */
			total = searcher.count(documents);
		} else if(everything != null) {
			total = matchCount(everything);
			FacetStates.keepWholeTotal(reader, total);
		} else {
			var kept = FacetStates.wholeTotalOf(reader);
			if(kept == null) {
				total = searcher.count(documents);
				FacetStates.keepWholeTotal(reader, total);
			} else {
				total = kept;
			}
		}

		return new Faceted(counts.toImmutable(), total);
	}

	/**
	 * Count the facets of a search whose hits are the matched values of one
	 * object field, where the counts are of those hits rather than of
	 * documents.
	 *
	 * A facet over a field inside the path counts the value hits per value
	 * directly - each hit holds its own values, and no rolling up is wanted
	 * because a hit is the unit being counted. A facet over a field of the
	 * index counts each value hit into what the document holding it says
	 * there, so a brand facet answers how many matching variants each brand
	 * has. A facet over a field inside another object is refused: its values
	 * are not the hits and not on the documents either, so no count of it
	 * describes this result set.
	 *
	 * The sideways rule holds unchanged - a facet leaves the filter entries it
	 * {@link Facet#excludes(String) excludes} out of its scope - and a scope
	 * is the value hits of that narrowed search, assembled the same way the
	 * search itself is. Nothing here reads the per-reader whole-index answers
	 * {@link FacetStates} keeps: those count documents, and even an unnarrowed
	 * search counts values here.
	 *
	 * @param clauses
	 *   the clauses the search ran with, query and filters together
	 * @param assembled
	 *   those clauses assembled
	 * @param whole
	 *   the matches of the search - its value hits - where the caller already
	 *   collected them, or {@code null} to collect them here when a facet or
	 *   the total needs them
	 */
	private Faceted countValueFacets(
		IndexSearcher searcher,
		QueryCompiler compiler,
		SearchRequest request,
		ListIterable<Query> clauses,
		Assembled assembled,
		FacetsCollector whole
	) throws IOException {
		var reader = searcher.getIndexReader();
		var filterPaths = request.filters().collect(Index::filterPathOf);
		var path = request.hits().path();

		var collectors = Maps.mutable.<ImmutableList<Query>, FacetsCollector>empty();
		var counts = Maps.mutable.<String, SearchResult.Facet>empty();
		var walks = Maps.mutable.<FacetMatches, MutableList<PendingFacet>>empty();

		for(var facet : request.facets()) {
			var nested = schema.getNestedField(facet.field());
			if(nested.isPresent() && !nested.get().path().equals(path)) {
				throw new IndexException(
					ERROR_HITS_FACET_UNSUPPORTED,
					"field", facet.field(),
					"path", path
				);
			}

			var filters = keptFilters(request.filters(), filterPaths, facet);

			FacetsCollector matches;
			if(filters.size() != request.filters().size()) {
				matches = collectors.get(filters);
				if(matches == null) {
					matches = searcher.search(
						assemble(compiler, request, request.query().newWithAll(filters)).hits(),
						new FacetsCollectorManager()
					);
					collectors.put(filters, matches);
				}
			} else {
				if(whole == null) {
					whole = searcher.search(assembled.hits(), new FacetsCollectorManager());
				}

				matches = whole;
			}

			var scope = nested.isPresent()
				? FacetMatches.values(matches)
				: FacetMatches.parentsByValue(matches, nestedParents);

			walks.getIfAbsentPut(scope, Lists.mutable::empty).add(
				new PendingFacet(facet, prepareFacet(compiler, facet, scope.mode()), false)
			);
		}

		countWalks(reader, request, walks, counts);

		var total = whole != null
			? matchCount(whole)
			: searcher.count(assembled.hits());

		return new Faceted(counts.toImmutable(), total);
	}

	/**
	 * The filter entries a facet's counts are narrowed by - every entry of the
	 * search except the ones the facet excludes.
	 *
	 * @param filterPaths
	 *   the field path of each entry, aligned with {@code filters} - worked
	 *   out once per search rather than once per facet
	 */
	private static ImmutableList<Query> keptFilters(
		ImmutableList<Query> filters,
		ListIterable<String> filterPaths,
		Facet facet
	) {
		var kept = Lists.mutable.<Query>empty();
		for(var i = 0; i < filters.size(); i++) {
			if(!facet.excludes(filterPaths.get(i))) {
				kept.add(filters.get(i));
			}
		}

		return kept.toImmutable();
	}

	/**
	 * The field path a filter entry is excluded by, see
	 * {@link Facet#excludes(String)}.
	 *
	 * A {@code field} entry is the field it names. A {@code nested} entry is
	 * the one field path its clauses read, or - when they read several - the
	 * most specific path covering all of them, down to the object field
	 * itself. The entry is one condition however many fields it touches, so
	 * its path is only as specific as the whole of it.
	 */
	private static String filterPathOf(Query filter) {
		if(filter instanceof FieldQuery field) {
			return field.field();
		}

		var nested = (NestedQuery) filter;
		var named = Lists.mutable.<String>empty();
		collectFieldPaths(nested.clauses(), nested.path(), named);

		String path = null;
		for(var name : named) {
			path = path == null ? name : commonPath(path, name);
		}

		return path == null ? nested.path() : path;
	}

	/**
	 * Collect the dotted paths of every field the clauses read, the object
	 * field itself standing in for a clause that covers all of its fields.
	 */
	private static void collectFieldPaths(
		ListIterable<Query> clauses,
		String path,
		MutableList<String> collected
	) {
		for(var clause : clauses) {
			switch(clause) {
				case FieldQuery q -> collected.add(q.field());
				case TextQuery q -> {
					if(q.fields().isEmpty()) {
						collected.add(path);
					} else {
						collected.addAllIterable(q.fields().keysView());
					}
				}
				case AndQuery q -> collectFieldPaths(q.clauses(), path, collected);
				case OrQuery q -> collectFieldPaths(q.clauses(), path, collected);
				case NotQuery q -> collectFieldPaths(q.clauses(), path, collected);
				case BoostQuery q -> collectFieldPaths(q.clauses(), path, collected);
				case NestedQuery q -> collected.add(q.path());
				case KnnQuery q -> collected.add(q.field());
				case FuseQuery q -> {
					for(var ranking : q.rankings()) {
						collectFieldPaths(ranking.clauses(), path, collected);
					}

					collectFieldPaths(q.filter(), path, collected);
				}
			}
		}
	}

	/**
	 * The longest path two dotted paths share whole segments of, empty when
	 * they share none.
	 */
	private static String commonPath(String a, String b) {
		var left = a.split("\\.");
		var right = b.split("\\.");

		var shared = 0;
		while(shared < left.length && shared < right.length
			&& left[shared].equals(right[shared])) {
			shared++;
		}

		return String.join(".", Arrays.copyOf(left, shared));
	}

	/**
	 * Prepare one facet of a search for the walk of its scope.
	 *
	 * @throws IndexFieldUsageException
	 *   if the facet asks for a level of a tree from a field whose values are
	 *   not paths
	 */
	private FacetCount prepareFacet(
		QueryCompiler compiler,
		Facet facet,
		FacetMatches.Mode mode
	) {
		if(facet.ranges().isEmpty() && compiler.isHierarchical(facet.field())) {
			return compiler.hierarchyFacetCounter(facet.field())
				.prepare(mode, facet.path(), facet.depth(), facet.limit(), facet.order());
		}

		if(facet.ranges().isEmpty()) {
			/*
			 * Counting a level of a tree is what a field holding paths answers
			 * and no other field can, so asking any other for it is refused
			 * rather than answered with the whole values.
			 */
			if(facet.asksForATree()) {
				throw new IndexFieldUsageException(facet.field(), "hierarchy");
			}

			return compiler.facetCounter(facet.field())
				.prepare(mode, facet.limit(), facet.order());
		}

		return compiler.rangeFacetCounter(facet.field(), facet.ranges())
			.prepare(mode);
	}

	/**
	 * Walk every gathered scope once and read the facets counted over it.
	 *
	 * @param walks
	 *   the facets waiting per scope
	 * @param counts
	 *   where each facet's result goes, by facet name
	 */
	private void countWalks(
		IndexReader reader,
		SearchRequest request,
		MutableMap<FacetMatches, MutableList<PendingFacet>> walks,
		MutableMap<String, SearchResult.Facet> counts
	) throws IOException {
		for(var walk : walks.keyValuesView()) {
			FacetWalk.walk(walk.getOne(), walk.getTwo().collect(PendingFacet::count));

			for(var pending : walk.getTwo()) {
				var counted = pending.count().result();
				counts.put(pending.facet().name(), counted);

				if(pending.keepWhole()) {
					FacetStates.keepWholeCounts(reader, request.locale(), pending.facet(), counted);
				}
			}
		}
	}

	/**
	 * One facet waiting for the walk of its scope.
	 *
	 * @param count
	 *   the counting, ready to be fed by the walk
	 * @param keepWhole
	 *   whether the result is kept per reader as whole-index counts - see
	 *   {@link FacetStates}
	 */
	private record PendingFacet(Facet facet, FacetCount count, boolean keepWhole) {
	}

	/**
	 * Collect the matches of everything the index holds, which is the scope of
	 * a facet the clauses of the search leave alone.
	 */
	private FacetsCollector collectEverything(
		IndexSearcher searcher,
		QueryCompiler compiler
	) throws IOException {
		var none = Lists.immutable.<Query>empty();
		return searcher.search(
			parentsOnly(compiler.compile(none), compiler, none),
			new FacetsCollectorManager()
		);
	}

	/**
	 * Get how many documents a collection of matches holds, which for matches
	 * collected over a whole search is its exact total.
	 */
	private static long matchCount(FacetsCollector matches) {
		var total = 0L;
		for(var docs : matches.getMatchingDocs()) {
			total += docs.totalHits();
		}

		return total;
	}

	/**
	 * What one search runs against Lucene, in both of the shapes the parts of
	 * answering need.
	 *
	 * @param documents
	 *   the compiled clauses, matching the documents of the search - what
	 *   facet scopes and value hits are built from
	 * @param hits
	 *   the query matching what the search answers with, one match per hit:
	 *   the same query as {@code documents} when hits are documents, the
	 *   matched values of the request's path when they are values, and the two
	 *   together where only the documents a {@code when} names answer with
	 *   their values. Ranking is not applied - see {@link #ranked}
	 */
	private record Assembled(
		org.apache.lucene.search.Query documents,
		org.apache.lucene.search.Query hits
	) {
	}

	/**
	 * Compile what a search runs, which is the one place that knows what its
	 * hits stand for. Everything that runs or counts a search goes through
	 * here - the search itself, relaxing it, and the scopes of its facets - so
	 * they all count the same unit.
	 *
	 * @param compiler
	 *   the compiler of the search, still pointed at its locale
	 * @param request
	 *   the request, read for what a hit stands for
	 * @param clauses
	 *   the clauses to compile - the search's own, or a facet's sideways scope
	 * @return
	 */
	private Assembled assemble(
		QueryCompiler compiler,
		SearchRequest request,
		ListIterable<Query> clauses
	) {
		var documents = parentsOnly(compiler.compile(clauses), compiler, clauses);
		if(request.hits() == null) {
			return new Assembled(documents, documents);
		}

		return new Assembled(
			documents,
			hitsOf(compiler, request, documents, clauses, false)
		);
	}

	/**
	 * Compile the query whose matches are the hits of a search that answers
	 * with values: every matching document's values, or - where the request
	 * says which documents expand - the values of those and the documents
	 * themselves for the rest.
	 *
	 * The two kinds of hit are matched by branches of one query, so a search
	 * that holds both still ranks and pages in a single pass. They can never
	 * both answer for one document: a document either satisfies {@code when},
	 * and is then only reachable through the join below it, or it does not,
	 * and is then a hit of its own.
	 *
	 * @param compiler
	 * @param request
	 *   the request, read for the path its hits are values of and for which
	 *   documents answer with them
	 * @param documents
	 *   the documents of the search, compiled - ranked or not, which is the
	 *   caller's to decide
	 * @param clauses
	 *   the clauses of the search, for the conditions they put on the values
	 * @param scores
	 *   whether each hit scores - see {@link #valueHits}. A document hit
	 *   scores what it scored as a document either way
	 * @return
	 */
	private org.apache.lucene.search.Query hitsOf(
		QueryCompiler compiler,
		SearchRequest request,
		org.apache.lucene.search.Query documents,
		ListIterable<Query> clauses,
		boolean scores
	) {
		var when = request.hits().when();
		if(when.isEmpty()) {
			return valueHits(compiler, request, documents, clauses, scores);
		}

		var expands = compiler.compile(when);

		var expanded = new BooleanQuery.Builder()
			.add(documents, scores ? BooleanClause.Occur.MUST : BooleanClause.Occur.FILTER)
			.add(expands, BooleanClause.Occur.FILTER)
			.build();

		var itself = new BooleanQuery.Builder()
			.add(documents, scores ? BooleanClause.Occur.MUST : BooleanClause.Occur.FILTER)
			.add(expands, BooleanClause.Occur.MUST_NOT)
			.build();

		return new BooleanQuery.Builder()
			.add(valueHits(compiler, request, expanded, clauses, scores), BooleanClause.Occur.SHOULD)
			.add(itself, BooleanClause.Occur.SHOULD)
			.build();
	}

	/**
	 * Compile the query whose matches are the value hits of a search: the
	 * values of one object field that belong to a matching document and that
	 * the search's {@code nested} clauses on the path asked for - every value
	 * of a matching document when they asked for nothing.
	 *
	 * @param compiler
	 * @param request
	 *   the request, read for the path its hits are values of and for whether
	 *   it orders by a field
	 * @param documents
	 *   the documents of the search, compiled - ranked or not, which is the
	 *   caller's to decide
	 * @param clauses
	 *   the clauses of the search, for the conditions they put on the values
	 * @param scores
	 *   whether each hit scores - what its document scored, joined down the
	 *   block, plus what the value itself scored under the clauses that rank
	 *   on the path. Off, everything only filters
	 * @return
	 */
	private org.apache.lucene.search.Query valueHits(
		QueryCompiler compiler,
		SearchRequest request,
		org.apache.lucene.search.Query documents,
		ListIterable<Query> clauses,
		boolean scores
	) {
		var path = request.hits().path();
		var everyDocument = request.hits().isEveryDocument();

		/*
		 * A page holding value hits beside document hits has to rank the two
		 * against each other, and only what their documents scored is a number
		 * both of them have. Adding what the value scored on top would put
		 * every expanded document above an equally relevant one that answered
		 * as itself - an ordering decided by how a result is displayed rather
		 * than by how well it matched.
		 */
		var valueScores = scores && everyDocument
			&& compiler.matchedValuesScore(path, clauses);

		/*
		 * Ordering by a field visits every match, which is the walk the
		 * clause naming the path is worth keeping for - see valuesOf.
		 */
		var keepPathClause = !request.sort().isEmpty();

		/*
		 * When every clause sits on the path, the join adds nothing: a value
		 * satisfying all of the conditions is itself the child that qualifies
		 * its document, so the values query alone matches the same values -
		 * and skips evaluating the conditions a second time from the document
		 * side, which is most of what a search that has to visit every value
		 * pays. With scores the join is what carries the document's score
		 * down, so it stays, and so it does where only some documents expand:
		 * dropping the join would drop the condition that picked them.
		 */
		if(!scores && everyDocument && onPathAlone(clauses, path)) {
			return compiler.compileMatchedValues(path, clauses, false, keepPathClause);
		}

		return new BooleanQuery.Builder()
			.add(
				new ToChildBlockJoinQuery(documents, nestedParents),
				scores ? BooleanClause.Occur.MUST : BooleanClause.Occur.FILTER
			)
			.add(
				compiler.compileMatchedValues(path, clauses, valueScores, keepPathClause),
				valueScores ? BooleanClause.Occur.MUST : BooleanClause.Occur.FILTER
			)
			.build();
	}

	/**
	 * Get whether clauses ask nothing of the documents of the index beyond
	 * what they ask of the values of one path - every one a {@code nested}
	 * clause on the path, or an {@code and} of such clauses, the same shapes
	 * whose conditions reach the values query. Any other clause narrows the
	 * documents in a way the values query does not carry, and an empty list
	 * asks nothing at all.
	 */
	private static boolean onPathAlone(ListIterable<Query> clauses, String path) {
		return clauses.allSatisfy(clause -> switch(clause) {
			case NestedQuery q -> q.path().equals(path);
			case AndQuery q -> onPathAlone(q.clauses(), path);
			default -> false;
		});
	}

	/**
	 * What ranking a search settled on: the query to hand Lucene, and whether
	 * its hits carry scores worth reporting.
	 *
	 * Scores are worth reporting when something in the search decided them,
	 * which a signal does even for a search whose clauses only narrow - a
	 * listing ranked by what sells is ordered by its scores and by nothing
	 * else.
	 */
	private record Ranked(org.apache.lucene.search.Query query, boolean scores) {
	}

	/**
	 * Apply what ranks a search to its assembled query.
	 *
	 * A signal multiplies the score, so it only says anything where the score
	 * is what orders results - a search sorting by a field of its own is
	 * ordered by that field, and reading doc values to build a number nothing
	 * looks at would be paid for nothing. Applied here rather than when the
	 * search is assembled, so that counting matches and counting facets -
	 * neither of which reads a score - are left with the plain query they can
	 * count fastest.
	 *
	 * For a search whose hits are values, the signals are applied to the
	 * documents and the block join is rebuilt to carry scores down: a hit then
	 * scores what its document scored - signals included - plus what the value
	 * itself scored under the clauses that rank on the path. Where only some
	 * documents answer with their values, a hit scores what its document
	 * scored and nothing else, so that the two kinds of hit on the page are
	 * ranked by the same number. A search where nothing scores keeps the plain
	 * query.
	 *
	 * @param compiler
	 * @param request
	 * @param assembled
	 * @param clauses
	 *   the clauses the search runs with, query and filters together
	 * @return
	 */
	private Ranked ranked(
		QueryCompiler compiler,
		SearchRequest request,
		Assembled assembled,
		ListIterable<Query> clauses
	) {
		var scores = request.query().anySatisfy(Query::scores);

		if(request.hits() == null) {
			var ranked = request.sort().isEmpty()
				? compiler.applySignals(assembled.hits(), request.signals())
				: assembled.hits();

			return new Ranked(ranked, scores || ranked != assembled.hits());
		}

		var documents = request.sort().isEmpty()
			? compiler.applySignals(assembled.documents(), request.signals())
			: assembled.documents();
		scores = scores || documents != assembled.documents();

		return new Ranked(
			scores
				? hitsOf(compiler, request, documents, clauses, true)
				: assembled.hits(),
			scores
		);
	}

	/**
	 * Get the second pass a search runs, or {@code null} when it runs none.
	 *
	 * A rescore reorders what relevance ranked, so a search ordered by a field
	 * of its own has nothing for it to reorder - the same rule the signals
	 * follow. A search continuing from a key is past the window: its position
	 * names a place in the order the first pass ranked, and reordering the
	 * results around it would answer from an order that key never named.
	 */
	private static Rescore rescoring(SearchRequest request) {
		if(request.rescore() == null
			|| !request.sort().isEmpty()
			|| request.after() != null
			|| request.before() != null)
		{
			return null;
		}

		return request.rescore();
	}

	/**
	 * The page Lucene ranked, and where its rescored window ended.
	 */
	private record Window(TopDocs topDocs, SortKey end) {
	}

	/**
	 * Rank a page of results, reordering the best of them when the search asked
	 * for a second pass.
	 *
	 * @param searcher
	 * @param compiler
	 *   the compiler that compiled the search, still pointed at its locale
	 * @param ranked
	 *   what ranks the search, already applied to its query
	 * @param rescore
	 *   the second pass, or {@code null} for none
	 * @param sort
	 *   the order to return results in, {@code null} for the best matches first
	 * @param wanted
	 *   how many results to rank, the whole window when there is a second pass
	 * @param position
	 *   the hit to continue from, or {@code null} to start at the beginning
	 * @param backwards
	 *   if the results are the ones before the position rather than after it
	 * @return
	 * @throws IOException
	 */
	private Window rank(
		IndexSearcher searcher,
		QueryCompiler compiler,
		Ranked ranked,
		Rescore rescore,
		Sort sort,
		int wanted,
		SortKey position,
		boolean backwards
	) throws IOException {
		var topDocs = topDocs(
			searcher,
			ranked.query(),
			sort,
			wanted,
			position,
			backwards,
			ranked.scores()
		);

		if(rescore == null || topDocs.scoreDocs.length == 0) {
			return new Window(topDocs, null);
		}

		/*
		 * Read before the second pass reorders the window. Lucene can only
		 * resume from a position in the order it ranked, so the results below
		 * the window are reached from where the first pass left off - which the
		 * rescored order no longer holds anywhere.
		 */
		var end = topDocs.scoreDocs.length < wanted
			? null
			: SortKeys.keyOf(topDocs.scoreDocs[topDocs.scoreDocs.length - 1], false);

		return new Window(
			QueryRescorer.rescore(
				searcher,
				topDocs,
				compiler.compileRescore(rescore.boost(), rescore.signals()),
				rescore.weight(),
				topDocs.scoreDocs.length
			),
			end
		);
	}

	/**
	 * Keep a query to the documents of the index, for a query whose clauses
	 * are no longer at hand to be read.
	 *
	 * @param query
	 * @return
	 */
	private org.apache.lucene.search.Query parentsOnly(org.apache.lucene.search.Query query) {
		return parentsOnly(query, null, null);
	}

	/**
	 * Keep a query to the documents of the index. The values of object fields
	 * are Lucene documents too, and anything that matches broadly - listing
	 * the index, an exclusion - would otherwise answer with them as hits of
	 * their own.
	 *
	 * Left off where the clauses say the query already matches nothing else,
	 * because walking the documents of the index beside a search costs a step
	 * per hit it never rules out - and a condition on a field of the index is
	 * something no value of an object field can satisfy.
	 *
	 * @param query
	 * @param compiler
	 *   the compiler that compiled the query, or {@code null} to keep the
	 *   query to documents whatever it holds
	 * @param clauses
	 *   the clauses the query was compiled from, read to tell whether it can
	 *   match anything but documents
	 * @return
	 */
	private org.apache.lucene.search.Query parentsOnly(
		org.apache.lucene.search.Query query,
		QueryCompiler compiler,
		ListIterable<se.l4.exofind.engine.query.Query> clauses
	) {
		if(!schema.hasNestedFields()) {
			return query;
		}

		if(compiler != null && compiler.matchesDocumentsOnly(clauses)) {
			return query;
		}

		return new BooleanQuery.Builder()
			.add(query, BooleanClause.Occur.MUST)
			.add(NestedDocuments.parentsFilter(nestedParents), BooleanClause.Occur.FILTER)
			.build();
	}

	/**
	 * Read the stored fields of a whole page of results at once.
	 *
	 * Stored fields arrive compressed in blocks holding many documents, and
	 * the reader keeps only the block it last decompressed. Read hit by hit in
	 * the order the page ranks them, hits land in no particular block order
	 * and a block is decompressed again for every hit that returns to it -
	 * which is why the page is read here, in one pass, however many times its
	 * documents are needed afterwards. The reads go through the document
	 * cache, which is what keeps a page that was read recently from being
	 * decompressed again at all.
	 *
	 * @param searcher
	 *   the searcher the ids belong to
	 * @param docIds
	 *   Lucene ids of the documents of the page, in any order
	 * @param names
	 *   the stored fields to read, or {@code null} for all of them
	 * @return
	 *   the documents, keyed by Lucene id
	 * @throws IOException
	 */
	private IntObjectMap<org.apache.lucene.document.Document> readStored(
		IndexSearcher searcher,
		int[] docIds,
		Set<String> names
	) throws IOException {
		/*
		 * Read in id order rather than in the order the page shows them, so
		 * that hits sharing a block are read while it is the block the reader
		 * has decompressed.
		 */
		var ordered = docIds.clone();
		Arrays.sort(ordered);

		var storedFields = searcher.storedFields();
		var documents = IntObjectMaps.mutable
			.<org.apache.lucene.document.Document>ofInitialCapacity(ordered.length);
		for(var docId : ordered) {
			documents.put(docId, documentCache.read(searcher, storedFields, docId, names));
		}

		return documents;
	}

	/**
	 * Read the documents of the nested lists' values for a page of documents,
	 * for reads that have no copy of the document to answer from.
	 *
	 * Every stored field of a child is read: a child holds nothing but its own
	 * value's fields, and the paths of a list whose name holds a wildcard are
	 * names documents gave, which nothing could enumerate up front.
	 *
	 * @param searcher
	 * @param docIds
	 *   Lucene ids of the documents to read children for
	 * @param paths
	 *   which nested lists are needed, asked with concrete paths
	 * @return
	 *   the children per document, keyed by Lucene id and grouped by path in
	 *   block order - the order the document gave the values in
	 * @throws IOException
	 */
	private IntObjectMap<MapIterable<String, ListIterable<org.apache.lucene.document.Document>>> readChildren(
		IndexSearcher searcher,
		int[] docIds,
		Predicate<String> paths
	) throws IOException {
		var ids = MatchedChildren.children(searcher, nestedParents, paths, docIds);

		var result = IntObjectMaps.mutable
			.<MapIterable<String, ListIterable<org.apache.lucene.document.Document>>>ofInitialCapacity(ids.size());
		var storedFields = searcher.storedFields();
		for(var pair : ids.keyValuesView()) {
			var byPath = Maps.mutable
				.<String, ListIterable<org.apache.lucene.document.Document>>empty();
			for(var pathPair : pair.getTwo().keyValuesView()) {
				var docs = Lists.mutable.<org.apache.lucene.document.Document>empty();
				var iterator = pathPair.getTwo().intIterator();
				while(iterator.hasNext()) {
					docs.add(documentCache.read(searcher, storedFields, iterator.next(), null));
				}

				byPath.put(pathPair.getOne(), docs);
			}

			result.put(pair.getOne(), byPath);
		}

		return result;
	}

	/**
	 * Read the stored fields of the hits of a value page that stand for
	 * values, for a page read without copies of the documents - the value a
	 * hit answers with is then read out of the hit's own document.
	 *
	 * @param searcher
	 * @param locations
	 *   where each value hit sits, which is what tells a hit standing for a
	 *   value from a document answering as itself
	 * @param docIds
	 *   Lucene ids of the hits of the page
	 * @return
	 *   the documents of the value hits, keyed by Lucene id
	 * @throws IOException
	 */
	private IntObjectMap<org.apache.lucene.document.Document> readValueDocuments(
		IndexSearcher searcher,
		IntObjectMap<MatchedChildren.Location> locations,
		int[] docIds
	) throws IOException {
		var ordered = docIds.clone();
		Arrays.sort(ordered);

		var storedFields = searcher.storedFields();
		var result = IntObjectMaps.mutable.<org.apache.lucene.document.Document>empty();
		for(var docId : ordered) {
			if(locations.get(docId) == null) {
				continue;
			}

			result.put(docId, documentCache.read(searcher, storedFields, docId, null));
		}

		return result;
	}

	/**
	 * Highlight the page of results a search brings back.
	 *
	 * @param searcher
	 * @param compiler
	 *   the compiler that compiled the search, still pointed at its locale
	 * @param targets
	 *   the fields the search asked to highlight, already resolved, never empty
	 * @param request
	 * @param hitsPath
	 *   name of the object field whose values are the hits, or {@code null}
	 *   when the hits are documents. On a value page the fragments are cut
	 *   per value, from the conditions the search put on the values
	 * @param docIds
	 *   Lucene ids of the hits of the page, in page order
	 * @param stored
	 *   the stored fields of those hits, read by
	 *   {@link #readStored(IndexSearcher, int[], Set)} - or by
	 *   {@link #readValueDocuments} on a value page, where a hit standing for
	 *   a document has no entry and answers with no fragments
	 * @return
	 *   one map per hit of the page, in page order - or {@code null} when the
	 *   search holds nothing that ranks, which every hit answers with no
	 *   fragments
	 * @throws IOException
	 */
	private ListIterable<ImmutableMap<String, ImmutableList<String>>> highlight(
		IndexSearcher searcher,
		QueryCompiler compiler,
		ListIterable<Highlighter.Target> targets,
		SearchRequest request,
		String hitsPath,
		int[] docIds,
		IntObjectMap<org.apache.lucene.document.Document> stored
	) throws IOException {
		var scoring = hitsPath == null
			? compiler.compileScoring(request.query())
			: compiler.compileValueScoring(hitsPath, request.query());
		if(scoring == null) {
			return null;
		}

		return new Highlighter(
			searcher,
			targets,
			stored,
			schema.isHighlightingInPostings()
		).highlight(scoring, docIds);
	}

	/**
	 * Start recording which documents change, so that a copy of this index
	 * taken from here on can be caught up by reading the recorded documents
	 * again. Every write records the primary keys it touches into the returned
	 * {@link ChangeLog}, including the keys a delete by query resolves to, and
	 * the log is persisted and pushed with every commit - a node that takes
	 * this index over resumes the same log rather than starting blind.
	 *
	 * <p>Tracking already underway is returned as it is. It ends with
	 * {@link #endChangeTracking()} and does not survive {@link #close()};
	 * what was committed of the log does, and is resumed by the next call
	 * here.
	 *
	 * @return
	 *   the log the writes record into
	 * @throws IndexNoPrimaryKeyException
	 *   if the definition declares no primary key, without which a change
	 *   cannot be named
	 * @throws IndexSourceNotKeptException
	 *   if the index keeps no copy of its documents, without which the keys a
	 *   delete by query takes cannot be read
	 * @throws IndexReadonlyException
	 *   if this node is not the indexer
	 * @throws IndexClosedException
	 *   if this instance has been closed
	 * @throws IndexOutOfDateException
	 *   if the index has not been pulled yet
	 * @throws IOException
	 *   if a log persisted by an earlier writer could not be read back
	 */
	public ChangeLog beginChangeTracking() throws IOException {
		syncLock.writeLock().lock();
		try {
			checkModifiable();

			primaryKeyField();
			if(!schema.isSourceStored()) {
				throw new IndexSourceNotKeptException(id);
			}

			if(changeLog != null) {
				return changeLog;
			}

			var file = localPath.resolve(CHANGES_FILE);
			this.changeLog = Files.exists(file)
				? ChangeLog.load(file)
				: new ChangeLog();

			return changeLog;
		} finally {
			syncLock.writeLock().unlock();
		}
	}

	/**
	 * Stop recording which documents change and drop what has been recorded,
	 * locally and - with the next commit - from the remote. Safe to call when
	 * nothing tracks.
	 *
	 * @throws IOException
	 *   if the persisted log could not be removed, in which case tracking has
	 *   still ended
	 */
	public void endChangeTracking() throws IOException {
		syncLock.writeLock().lock();
		try {
			this.changeLog = null;
			Files.deleteIfExists(localPath.resolve(CHANGES_FILE));
		} finally {
			syncLock.writeLock().unlock();
		}
	}

	/**
	 * Get the log writes are being recorded into, empty while nothing tracks.
	 */
	public Optional<ChangeLog> getChangeLog() {
		return Optional.ofNullable(changeLog);
	}

	/**
	 * Hold every write to the contents still until the returned hold is
	 * closed. Writes already underway finish first - this call waits for them
	 * - and new ones wait at the gate rather than failing, so a caller sees
	 * added latency and nothing else.
	 *
	 * <p>Searches, commits and pushes are not held; the holder itself may
	 * commit. Meant for the moment a caught-up copy takes over, where the
	 * change log has to stay empty while the switch is made.
	 *
	 * @return
	 *   the hold, which the caller closes to let writes continue
	 */
	public WriteHold holdWrites() {
		var lock = writeGate.writeLock();
		lock.lock();
		return lock::unlock;
	}

	/**
	 * What {@link #holdWrites()} hands the caller: closing it lets writes
	 * continue. Close once, from the thread that took the hold.
	 */
	public interface WriteHold extends AutoCloseable {
		@Override
		void close();
	}

	/**
	 * Record every document a query is about to remove into the change log.
	 * Reads the keys from the stored copies of the documents, seeing
	 * everything written so far whether committed or not.
	 *
	 * @param documents
	 *   the compiled query, matching documents of the index only
	 * @throws IndexSourceNotKeptException
	 *   if a matched document was indexed while the index kept no copy, where
	 *   its key can no longer be read - nothing is recorded or removed
	 * @throws IOException
	 */
	private void recordMatches(
		ChangeLog log,
		org.apache.lucene.search.Query documents
	) throws IOException {
		var field = primaryKeyField();
		var encounter = new IndexEncounterImpl(schema.getResources(), schema.isHighlightingInPostings());
		encounter.updateLocale(DEFAULT_LOCALE_SUPPORT);
		encounter.updateValue(field.getName(), field.getDef());

		synchronized(mergeLock) {
			/*
			 * Reopened whether or not it is stale: what is remembered beside
			 * the reader cannot answer a query, so the reader alone has to
			 * hold everything written so far.
			 */
			refreshMergeReader();

			var searcher = new IndexSearcher(mergeReader);
			var docIds = searcher.search(
				documents,
				new CollectorManager<CollectDocIds, MutableIntList>() {
					@Override
					public CollectDocIds newCollector() {
						return new CollectDocIds();
					}

					@Override
					public MutableIntList reduce(Collection<CollectDocIds> collectors) {
						var all = IntLists.mutable.empty();
						for(var collector : collectors) {
							all.addAll(collector.docIds);
						}
						return all;
					}
				}
			);

			var storedFields = mergeReader.storedFields();
			var wanted = Set.of(FieldNames.SOURCE);
			for(var i = 0; i < docIds.size(); i++) {
				var stored = storedFields.document(docIds.get(i), wanted);
				var source = stored.getBinaryValue(FieldNames.SOURCE);
				if(source == null) {
					/*
					 * Indexed while the index kept nothing, so which key the
					 * removal takes cannot be said - and a log that is missing
					 * a key is worse than a delete that is refused.
					 */
					throw new IndexSourceNotKeptException(id);
				}

				var decoded = DocumentSource.decode(source, field.getName()::equals);
				var term = field.getType()
					.createPrimaryKeyTerm(encounter, decoded.get(field.getName()));
				log.record(term.bytes());
			}
		}
	}

	/**
	 * Gathers the id of every matching document, for reading their stored
	 * fields once the search is done.
	 */
	private static final class CollectDocIds implements Collector {
		private final MutableIntList docIds = IntLists.mutable.empty();

		@Override
		public LeafCollector getLeafCollector(LeafReaderContext context) {
			var base = context.docBase;
			return new LeafCollector() {
				@Override
				public void setScorer(Scorable scorer) {
				}

				@Override
				public void collect(int doc) {
					docIds.add(base + doc);
				}
			};
		}

		@Override
		public ScoreMode scoreMode() {
			return ScoreMode.COMPLETE_NO_SCORES;
		}
	}

	/**
	 * Write what has been indexed into a Lucene commit and push it to the
	 * remote, which is what makes it searchable here and elsewhere.
	 *
	 * @throws IndexReadonlyException
	 *   if this node is not the indexer. Only the indexer writes, so there is
	 *   nothing here to commit and the caller has to reach the node that has it
	 * @throws IOException
	 */
	public void commit() throws IOException {
		if(isReadOnly()) {
			throw new IndexReadonlyException(id);
		}

		commitChanges(PushReason.HELD);
	}

	/**
	 * Commit and push whatever this instance holds, without asking whether the
	 * node may still write. Closing goes through here, as an index that is on
	 * its way out is committed for the state it was opened in rather than the
	 * one the node is in now.
	 *
	 * @param reason
	 *   why the push that follows the commit is being made, which is what
	 *   decides whether the node having stopped holding the index stops it -
	 *   see {@link PushReason}
	 */
	private void commitChanges(PushReason reason) throws IOException {
		this.syncLock.writeLock().lock();
		try {
			if(this.writer == null) {
				return;
			}

			logger.atDebug().addKeyValue("index", id).log("Committing index");

			/*
			 * Saved before the Lucene commit, while the write lock keeps every
			 * write path out, so the file always covers at least the changes
			 * the commit carries - and the push that follows uploads both.
			 */
			var log = this.changeLog;
			if(log != null) {
				log.save(localPath.resolve(CHANGES_FILE));
			}

			writer.commit();

			/*
			 * Read while the write lock is held, so nothing can be recording a
			 * change at the same time: everything counted so far is in the
			 * commit, and anything counted after it is what tells the push that
			 * the index has moved on since.
			 */
			this.committedModifications = this.modifications;

			/*
			 * The version that created an index is settled by its first commit
			 * and never changes after it, so this is both the earliest and the
			 * only time it has to be worked out on the node doing the writing.
			 */
			if(luceneCreatedMajor.isEmpty()) {
				refreshLuceneCompatibility();
			}

			this.reader = DirectoryReader.open(writer);

			this.searcherManager.refreshLatest(newSearcher(reader));

			/*
			 * Everything remembered is in the commit, so the next partial
			 * update opens a reader that holds it rather than being told about
			 * it a document at a time.
			 */
			closeMergeReader();
		} finally {
			this.syncLock.writeLock().unlock();
		}

		this.syncLock.readLock().lock();
		try {
			sync(reason);
		} finally {
			this.syncLock.readLock().unlock();
		}
	}

	public void close() throws IOException {
		close(true);
	}

	/**
	 * Close this index, optionally without committing and pushing pending
	 * changes. Skipping the commit is used when the index is about to be
	 * deleted and its local changes are of no interest.
	 *
	 * Closing is final for this instance and safe to repeat. Operations that
	 * arrive after the close fail with {@link IndexClosedException} - the
	 * index itself is opened again by asking {@link se.l4.exofind.engine.Indexes}
	 * for it anew.
	 *
	 * @param commit
	 * @throws IOException
	 */
	public void close(boolean commit) throws IOException {
		if(getState() == IndexState.CLOSED) {
			return;
		}

		/*
		 * Stopped before the last commit rather than after it, so that a commit
		 * of its own can not be starting while the index is being torn down.
		 */
		this.commitManager.close();

		if(commit) {
			this.commitChanges(PushReason.HANDOVER);
		}

		this.syncLock.writeLock().lock();
		try {
			if(state == IndexState.CLOSED) {
				return;
			}

			/*
			 * Marked before anything is torn down, so a pull that is holding
			 * the remote right now knows not to reopen what is closed here
			 * when it comes back for the lock.
			 */
			state = IndexState.CLOSED;

			/*
			 * Readers are closed before the writer they may have been opened
			 * from, and the searcher manager owns every reader that has been
			 * handed out, so it goes first.
			 */
			this.searcherManager.close();
			closeMergeReader();

			if(this.reader != null) {
				this.reader.close();
				this.reader = null;
			}

			if(this.writer != null) {
				this.writer.close();
				this.writer = null;
				this.snapshots = null;
			}

			if(this.directory != null) {
				this.directory.close();
				this.directory = null;
			}
		} finally {
			this.syncLock.writeLock().unlock();
		}

		/*
		 * Nothing is left for the background work to do, and the thread running
		 * it would otherwise keep this index alive.
		 */
		this.maintenanceExecutor.shutdownNow();
	}

}
