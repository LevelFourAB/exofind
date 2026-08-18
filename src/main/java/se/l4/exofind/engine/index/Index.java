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
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.facet.FacetsCollectorManager;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.KeepOnlyLastCommitDeletionPolicy;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SnapshotDeletionPolicy;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.CollectionTerminatedException;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
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
import org.apache.lucene.util.BytesRef;
import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.factory.primitive.IntObjectMaps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.ImmutableMap;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.map.primitive.IntObjectMap;
import org.eclipse.collections.api.set.SetIterable;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.tuple.Tuples;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.CodedOutputStream;

import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.errors.ErrorMessage;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.locales.LocaleSupport;
import se.l4.exofind.engine.index.locales.Locales;
import se.l4.exofind.engine.index.schema.Field;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.IndexFeatures;
import se.l4.exofind.engine.index.schema.IndexSchema;
import se.l4.exofind.engine.index.state.StateSync;
import se.l4.exofind.engine.index.state.SyncConflictException;
import se.l4.exofind.engine.index.state.SyncIncompatibleException;
import se.l4.exofind.engine.query.FieldQuery;
import se.l4.exofind.engine.query.Query;
import se.l4.exofind.engine.query.SearchRequest;
import se.l4.exofind.engine.query.SearchResult;
import se.l4.exofind.engine.query.SortKey;

/**
 * Index represents a single index that can be searched or updated.
 */
public class Index {
	public static final String DEFINITION_FILE = "definition.ef.bin";

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

	private static final Logger logger = LoggerFactory.getLogger(Index.class);

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
	private final Path localPath;
	private final StateSync sync;

	private final ReadWriteLock syncLock;

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

	public Index(
		NodeState nodeState,
		String name,
		Path localPath,
		StateSync sync,
		CommitPolicy commitPolicy
	) {
		this.nodeState = nodeState;
		this.id = name;
		this.localPath = localPath;
		this.sync = sync;

		this.schema = new IndexSchema();
		this.similarity = new IndexSimilarity(schema);
		this.syncLock = new ReentrantReadWriteLock();
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
			new IndexCommitManager(this, maintenanceExecutor, commitPolicy);

		this.nestedParents = new QueryBitSetProducer(NestedDocuments.parentsQuery());
	}

	public String getId() {
		return id;
	}

	/**
	 * Get if this index is read-only.
	 *
	 * @return
	 */
	public boolean isReadOnly() {
		return !nodeState.isIndexer();
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
		boolean hasChanges;
		try {
			hasChanges = this.sync.pull();

			if(startState == IndexState.NEEDS_PULL || readerWithoutCommit) {
				// At start no changes may be pulled but an out of date index
				// should be treated as having changes
				hasChanges = true;
			}
		} catch(SyncIncompatibleException e) {
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

				this.writer = new IndexWriter(directory, config);
				this.reader = DirectoryReader.open(writer);
				this.readerWithoutCommit = false;
			}

			/*
			 * The searcher manager takes over the reader that was in use, and
			 * closes it once the searches still holding it are done.
			 */
			var searcher = new IndexSearcher(reader);
			searcher.setSimilarity(similarity);
			searcherManager.refreshLatest(searcher);

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
	 * Reopen this index to match whether this node may write to it, dropping
	 * anything uncommitted and taking the remote state as what to continue
	 * from. Called when the node gains or loses the indexer role, which
	 * changes which mode the Lucene directory has to be open in.
	 */
	public void reopen() {
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

			state = IndexState.NEEDS_PULL;
		} finally {
			syncLock.writeLock().unlock();
		}

		pull();
	}

	private void sync() throws IOException {
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
		} catch(SyncConflictException e) {
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
	 * @throws IOException
	 */
	public void updateDefinition(IndexDef def) throws IOException {
		updateDefinition(def, null);
	}

	/**
	 * Update the definition of the index, optionally only if the current
	 * definition has the expected version. The check and the update are
	 * performed atomically, so a caller that read a definition and its version
	 * can update it without racing another caller.
	 *
	 * @param def
	 * @param expectedVersion
	 *   version the current definition is expected to have, or {@code null} to
	 *   update no matter the current version
	 * @throws IOException
	 */
	public void updateDefinition(IndexDef def, String expectedVersion) throws IOException {
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

			/*
			 * Record what the definition needs, so that a node without one of
			 * those features can tell rather than indexing without it.
			 */
			var described = IndexFeatures.describe(withDefaults(def));

			schema.setDefinition(described);

			Files.write(localPath.resolve(DEFINITION_FILE), described.toByteArray());
			this.definition = described;
			this.definitionVersion = version(described);
			sync();
		} finally {
			syncLock.writeLock().unlock();
		}
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
	}

	/**
	 * Record that the contents of the index have changed: the index holds
	 * something the remote does not until the next push, and the change counts
	 * towards the next commit this index makes on its own.
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
	}

	/**
	 * Add a document to the index.
	 *
	 * @param doc
	 * @throws IOException
	 */
	public void addDocument(Document doc) throws IOException {
		syncLock.readLock().lock();
		try {
			checkModifiable();

			var luceneDoc = new org.apache.lucene.document.Document();

			var errors = Lists.mutable.<ErrorMessage>empty();
			var encounter = new IndexEncounterImpl(schema.getResources());

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

					if(field0.isNestedObject()) {
						childDocs.add(
							childDocument(
								field0,
								subDocument,
								ObjectLocation.root().forField(value.name()).forIndex(position),
								encounter,
								errors
							)
						);
					} else {
						flattenFields(
							field0,
							subDocument,
							ObjectLocation.root().forField(value.name()).forIndex(position),
							encounter,
							luceneDoc,
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

			markModified(1);
		} finally {
			syncLock.readLock().unlock();
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
	 * Turn one value of an object field into the Lucene document it is indexed
	 * as, checking its fields the way {@link #addDocument} checks the
	 * document's own.
	 *
	 * The names inside the value are the ones the object declares; what they
	 * are written under is the dotted path through the object, so a search
	 * finds them by the name it knows.
	 *
	 * @param objectField
	 *   the object field the value was given to
	 * @param value
	 *   the value, a document of its own
	 * @param location
	 *   where the value sits in the document, used to point at errors
	 * @param encounter
	 * @param errors
	 *   where problems with the value are collected
	 * @return
	 */
	private org.apache.lucene.document.Document childDocument(
		Field objectField,
		Document value,
		ObjectLocation location,
		IndexEncounterImpl encounter,
		MutableList<ErrorMessage> errors
	) {
		var child = new org.apache.lucene.document.Document();
		NestedDocuments.mark(child, objectField.getName());

		var valuesSeen = Sets.mutable.<String>empty();
		var fieldsFound = Sets.mutable.<String>empty();

		for(var inner : value.fields()) {
			var nested = schema.getNestedField(objectField.getName() + '.' + inner.name());
			if(nested.isEmpty()) {
				errors.add(
					ERROR_FIELD_NOT_FOUND.toMessage(
						location.forField(inner.name()),
						"name", inner.name()
					)
				);
				continue;
			}

			var innerField = nested.get().field();

			// Fields inside an object are never locale specific
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

			if(inner.value() instanceof Document) {
				errors.add(
					ERROR_UNEXPECTED_DOCUMENT.toMessage(
						location.forField(inner.name()),
						"name", inner.name()
					)
				);
				continue;
			}

			if(!valuesSeen.add(inner.name()) && !innerField.isMultiple()) {
				errors.add(
					ERROR_NOT_MULTIPLE.toMessage(
						location.forField(inner.name()),
						"name", inner.name()
					)
				);
				continue;
			}

			encounter.updateLocale(DEFAULT_LOCALE_SUPPORT);
			encounter.updateValue(innerField.getName(), innerField.getDef());

			try {
				for(var indexableField : innerField.getType().createFields(encounter, inner.value())) {
					child.add(indexableField);
				}
			} catch(ValidationException e) {
				errors.addAllIterable(e.getErrors());
				continue;
			}

			fieldsFound.add(inner.name());
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

		return child;
	}

	/**
	 * Fold one value of a flattened object field into the document that holds
	 * it, checking its fields the way {@link #childDocument} checks a nested
	 * value's.
	 *
	 * The names inside the value are the ones the object declares; what they
	 * are written under is the dotted path through the object, which is the
	 * root field the schema registered for each of them. A field allowed once
	 * per value may still be written by every value of the object, so being
	 * given more than once is judged within the value alone.
	 *
	 * @param objectField
	 *   the object field the value was given to
	 * @param value
	 *   the value, an object of its own
	 * @param location
	 *   where the value sits in the document, used to point at errors
	 * @param encounter
	 * @param luceneDoc
	 *   the document being built, which the fields are added to
	 * @param errors
	 *   where problems with the value are collected
	 */
	private void flattenFields(
		Field objectField,
		Document value,
		ObjectLocation location,
		IndexEncounterImpl encounter,
		org.apache.lucene.document.Document luceneDoc,
		MutableList<ErrorMessage> errors
	) {
		var valuesSeen = Sets.mutable.<String>empty();
		var fieldsFound = Sets.mutable.<String>empty();

		for(var inner : value.fields()) {
			var path = objectField.getName() + '.' + inner.name();

			/*
			 * Only the declared fields of the object, which are the paths the
			 * schema folded out of it - a root pattern that happens to match
			 * the path was never part of this object.
			 */
			if(schema.getFlattenedObjectOf(path).isEmpty()) {
				errors.add(
					ERROR_FIELD_NOT_FOUND.toMessage(
						location.forField(inner.name()),
						"name", inner.name()
					)
				);
				continue;
			}

			var innerField = schema.getField(path).orElseThrow();

			// Fields inside an object are never locale specific
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

			if(inner.value() instanceof Document) {
				errors.add(
					ERROR_UNEXPECTED_DOCUMENT.toMessage(
						location.forField(inner.name()),
						"name", inner.name()
					)
				);
				continue;
			}

			if(!valuesSeen.add(inner.name()) && !innerField.isMultiple()) {
				errors.add(
					ERROR_NOT_MULTIPLE.toMessage(
						location.forField(inner.name()),
						"name", inner.name()
					)
				);
				continue;
			}

			encounter.updateLocale(DEFAULT_LOCALE_SUPPORT);
			encounter.updateValue(innerField.getName(), innerField.getDef());

			try {
				for(var indexableField : innerField.getType().createFields(encounter, inner.value())) {
					luceneDoc.add(indexableField);
				}
			} catch(ValidationException e) {
				errors.addAllIterable(e.getErrors());
				continue;
			}

			fieldsFound.add(inner.name());
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

			var encounter = new IndexEncounterImpl(schema.getResources());
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
		syncLock.readLock().lock();
		try {
			checkModifiable();

			if(primaryKeys.isEmpty()) {
				return 0;
			}

			var field = primaryKeyField();
			var encounter = new IndexEncounterImpl(schema.getResources());
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

			markModified(terms.length);
			return terms.length;
		} finally {
			syncLock.readLock().unlock();
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

				var encounter = new IndexEncounterImpl(schema.getResources());
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

				var storedFields = searcher.storedFields();
				var doc = storedFields.document(hits.scoreDocs[0].doc);

				return documentReader(Sets.immutable.<String>empty()).read(doc);
			}
		} finally {
			syncLock.readLock().unlock();
		}
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

				var compiler = new QueryCompiler(schema, request.locale(), nestedParents);
				var searched = request.query().newWithAll(request.filters());
				var query = parentsOnly(compiler.compile(searched), compiler, searched);
				/*
				 * Ordering by a value inside an object reads which values a
				 * document may be ordered by off the clauses of the search, so
				 * the order is decided by the same values the search matched.
				 */
				var sort = compiler.compileSort(
					request.sort(),
					request.query().newWithAll(request.filters())
				);

				/*
				 * Resolved before anything runs, so a field that can not be
				 * highlighted is refused however many results the search brings
				 * back.
				 */
				var highlightTargets = Lists.mutable.<Highlighter.Target>empty();
				for(var pair : request.highlight().keyValuesView()) {
					highlightTargets.add(new Highlighter.Target(
						pair.getOne(),
						compiler.highlightField(pair.getOne()),
						pair.getTwo()
					));
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
				if(wanted == 0) {
					/*
					 * Nothing to rank and nothing to read, which is the cheapest
					 * way to answer how many documents match - and the count is
					 * also what says whether anything was found at all.
					 */
					var count = searcher.count(query);
					if(count == 0) {
						var outcome = relax(searcher, compiler, request);
						if(outcome != null) {
							request = request.withQuery(outcome.query());
							relaxed = outcome.relaxed();
							searched = request.query().newWithAll(request.filters());
							query = parentsOnly(compiler.compile(searched), compiler, searched);
							count = searcher.count(query);
						}
					}

					/*
					 * A search with a limit of zero and facets is how a
					 * filtering UI refreshes its counts without fetching hits.
					 * Counting collects every match, so it also knows the exact
					 * total.
					 */
					var counted = request.facets().isEmpty()
						? null
						: countFacets(searcher, compiler, request);

					return new SearchResult(
						Lists.immutable.empty(),
						new SearchResult.Total(
							counted == null ? count : counted.total(),
							true
						),
						counted == null ? null : counted.counts(),
						relaxed
					);
				}

				/*
				 * A signal multiplies the score, so it only says anything where
				 * the score is what orders results. A search sorting by a field
				 * of its own is ordered by that field, and reading doc values
				 * to build a number nothing looks at would be paid for nothing.
				 *
				 * Applied here rather than to the query above, so that counting
				 * matches and counting facets - neither of which reads a score
				 * - are left with the plain query they can count fastest.
				 */
				var ranked = request.sort().isEmpty()
					? compiler.applySignals(query, request.signals())
					: query;

				/*
				 * Scores are worth reporting when something in the search
				 * decided them, which a signal does even for a search whose
				 * clauses only narrow - a listing ranked by what sells is
				 * ordered by its scores and by nothing else.
				 */
				var scores = request.query().anySatisfy(Query::scores) || ranked != query;

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

				var topDocs = topDocs(
					searcher,
					ranked,
					sort,
					wanted,
					position,
					backwards,
					scores
				);

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
						query = parentsOnly(compiler.compile(searched), compiler, searched);
						ranked = request.sort().isEmpty()
							? compiler.applySignals(query, request.signals())
							: query;

						topDocs = topDocs(
							searcher,
							ranked,
							sort,
							wanted,
							position,
							backwards,
							request.query().anySatisfy(Query::scores) || ranked != query
						);
					}
				}

				/*
				 * Counted from the query the hits came from, so a relaxed search
				 * does not offer filters for a set of documents it is not
				 * showing. Counting collects every match, so it also knows the
				 * exact total.
				 */
				var faceted = request.facets().isEmpty()
					? null
					: countFacets(searcher, compiler, request);

				var reader = documentReader(request.fields());
				var names = reader.namesOf();
				var storedFields = searcher.storedFields();
				var primaryKey = schema.getPrimaryKey();

				/*
				 * The text a fragment is cut out of is a stored field of the
				 * same document the hit is built from, and stored fields arrive
				 * compressed in blocks, so reading the page once for its values
				 * and again for its text decompresses every block twice. A page
				 * that is wanted twice is therefore read here and kept - one
				 * that is not is read hit by hit below and let go again, rather
				 * than held whole for nothing.
				 */
				IntObjectMap<org.apache.lucene.document.Document> page = null;
				ListIterable<ImmutableMap<String, ImmutableList<String>>> highlights = null;
				if(!highlightTargets.isEmpty()) {
					if(names != null) {
						for(var target : highlightTargets) {
							names.add(Highlighter.storedField(target.luceneField()));
						}
					}

					var docIds = new int[Math.max(0, topDocs.scoreDocs.length - request.offset())];
					for(var i = 0; i < docIds.length; i++) {
						docIds[i] = topDocs.scoreDocs[request.offset() + i].doc;
					}

					page = readStored(storedFields, docIds, names);
					highlights = highlight(
						searcher,
						compiler,
						highlightTargets,
						request,
						docIds,
						page
					);
				}

				var hits = Lists.mutable.<SearchResult.Hit>empty();
				for(var i = request.offset(); i < topDocs.scoreDocs.length; i++) {
					var scoreDoc = topDocs.scoreDocs[i];
					var document = reader.read(
						page == null
							? storedDocument(storedFields, scoreDoc.doc, names)
							: page.get(scoreDoc.doc)
					);

					hits.add(
						new SearchResult.Hit(
							primaryKey.map(field -> document.get(field.getName())).orElse(null),
							/*
							 * Ordering by a field with nothing in the query to
							 * score leaves Lucene with no score to report, which
							 * is no score rather than an unusable number.
							 */
							Float.isNaN(scoreDoc.score) ? 0f : scoreDoc.score,
							document,
							SortKeys.keyOf(scoreDoc, backwards),
							highlights == null
								? null
								: highlights.get(i - request.offset())
						)
					);
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
				 * whole number for free.
				 */
				if(faceted != null) {
					total = new SearchResult.Total(faceted.total(), true);
				} else if(request.total() == SearchRequest.Total.EXACT && !total.exact()) {
					total = new SearchResult.Total(searcher.count(query), true);
				}

				return new SearchResult(
					hits.toImmutable(),
					total,
					faceted == null ? null : faceted.counts(),
					relaxed
				);
			}
		} finally {
			syncLock.readLock().unlock();
		}
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

		return relaxation.run(
			clauses -> {
				var whole = clauses.newWithAll(request.filters());
				return anyMatch(searcher, parentsOnly(compiler.compile(whole), compiler, whole));
			},
			clauses -> searcher.count(
				parentsOnly(compiler.compile(clauses), compiler, clauses)
			)
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

	private DocumentReader documentReader(SetIterable<String> fields) {
		return new DocumentReader(schema, fields);
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
	 * A facet is counted sideways of the filters on its own field: those are
	 * left out of its scope, so ticking a value keeps the other values of that
	 * field visible and countable, while the query and every other filter
	 * narrow the counts the way they narrow the hits. Facets whose field no
	 * filter names all share the scope of the search itself, so the matches
	 * are collected once per distinct scope rather than once per facet.
	 *
	 * A facet over a field inside an object counts the values of that object
	 * instead - the ones the search matched, which is what its {@code nested}
	 * clauses say - and rolls them up, so it answers how many documents hold
	 * each value the way every other facet does. Filters name fields of the
	 * index and never reach inside one, so those facets have no sideways scope
	 * of their own and share one per object field.
	 *
	 * A facet over a field whose values are paths counts a level of the tree at
	 * a time and answers the counts nested. The scope is worked out the same
	 * way, which is what keeps the sideways rule true down a tree: the filter
	 * that drilled into a level is a filter on the facet's own field, so it is
	 * left out and the levels beside the chosen one stay countable.
	 */
	private Faceted countFacets(
		IndexSearcher searcher,
		QueryCompiler compiler,
		SearchRequest request
	) throws IOException {
		var reader = searcher.getIndexReader();
		var filtered = request.filters().collect(FieldQuery::field).toSet();

		var clauses = request.query().newWithAll(request.filters());
		var documents = parentsOnly(compiler.compile(clauses), compiler, clauses);
		var whole = searcher.search(documents, new FacetsCollectorManager());

		var collectors = Maps.mutable.<String, FacetMatches>empty();
		var values = Maps.mutable.<String, FacetMatches>empty();
		var counts = Maps.mutable.<String, SearchResult.Facet>empty();

		for(var facet : request.facets()) {
			var nested = schema.getNestedField(facet.field());

			FacetMatches scope;
			if(nested.isPresent()) {
				var path = nested.get().path();

				scope = values.get(path);
				if(scope == null) {
					scope = new FacetMatches(
						searcher.search(
							compiler.compileNestedValues(path, documents, clauses),
							new FacetsCollectorManager()
						),
						nestedParents
					);
					values.put(path, scope);
				}
			} else if(filtered.contains(facet.field())) {
				scope = collectors.get(facet.field());
				if(scope == null) {
					var sideways = request.query().newWithAll(
						request.filters().reject(f -> f.field().equals(facet.field()))
					);

					scope = FacetMatches.of(
						searcher.search(
							parentsOnly(compiler.compile(sideways), compiler, sideways),
							new FacetsCollectorManager()
						)
					);
					collectors.put(facet.field(), scope);
				}
			} else {
				scope = FacetMatches.of(whole);
			}

			if(facet.ranges().isEmpty() && compiler.isHierarchical(facet.field())) {
				counts.put(
					facet.name(),
					compiler.hierarchyFacetCounter(facet.field())
						.count(scope, facet.path(), facet.depth(), facet.limit(), facet.order())
				);
			} else if(facet.ranges().isEmpty()) {
				/*
				 * Counting a level of a tree is what a field holding paths
				 * answers and no other field can, so asking any other for it is
				 * refused rather than answered with the whole values.
				 */
				if(facet.asksForATree()) {
					throw new IndexFieldUsageException(facet.field(), "hierarchy");
				}

				counts.put(
					facet.name(),
					compiler.facetCounter(facet.field())
						.count(reader, scope, facet.limit(), facet.order())
				);
			} else {
				counts.put(
					facet.name(),
					compiler.rangeFacetCounter(facet.field(), facet.ranges())
						.count(reader, scope)
				);
			}
		}

		var total = 0L;
		for(var docs : whole.getMatchingDocs()) {
			total += docs.totalHits();
		}

		return new Faceted(counts.toImmutable(), total);
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
	 * Read the stored fields of one document.
	 *
	 * @param storedFields
	 * @param docId
	 * @param names
	 *   the stored fields to read, or {@code null} for all of them
	 * @return
	 * @throws IOException
	 */
	private static org.apache.lucene.document.Document storedDocument(
		StoredFields storedFields,
		int docId,
		Set<String> names
	) throws IOException {
		return names == null
			? storedFields.document(docId)
			: storedFields.document(docId, names);
	}

	/**
	 * Read the stored fields of a whole page of results at once, for a page
	 * whose documents are needed more than once.
	 *
	 * @param storedFields
	 * @param docIds
	 *   Lucene ids of the documents of the page, in any order
	 * @param names
	 *   the stored fields to read, or {@code null} for all of them
	 * @return
	 *   the documents, keyed by Lucene id
	 * @throws IOException
	 */
	private static IntObjectMap<org.apache.lucene.document.Document> readStored(
		StoredFields storedFields,
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

		var documents = IntObjectMaps.mutable
			.<org.apache.lucene.document.Document>ofInitialCapacity(ordered.length);
		for(var docId : ordered) {
			documents.put(docId, storedDocument(storedFields, docId, names));
		}

		return documents;
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
	 * @param docIds
	 *   Lucene ids of the documents of the page, in page order
	 * @param stored
	 *   the stored fields of those documents, read by
	 *   {@link #readStored(StoredFields, int[], Set)}
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
		int[] docIds,
		IntObjectMap<org.apache.lucene.document.Document> stored
	) throws IOException {
		var scoring = compiler.compileScoring(request.query());
		if(scoring == null) {
			return null;
		}

		return new Highlighter(searcher, targets, stored).highlight(scoring, docIds);
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

		commitChanges();
	}

	/**
	 * Commit and push whatever this instance holds, without asking whether the
	 * node may still write. Closing goes through here, as an index that is on
	 * its way out is committed for the state it was opened in rather than the
	 * one the node is in now.
	 */
	private void commitChanges() throws IOException {
		this.syncLock.writeLock().lock();
		try {
			if(this.writer == null) {
				return;
			}

			logger.atDebug().addKeyValue("index", id).log("Committing index");

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

			var searcher = new IndexSearcher(reader);
			searcher.setSimilarity(similarity);
			this.searcherManager.refreshLatest(searcher);

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
			sync();
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
			this.commitChanges();
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
