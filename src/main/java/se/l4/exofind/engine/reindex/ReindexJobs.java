package se.l4.exofind.engine.reindex;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.ObjectLocation;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.ChangeLog;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexNoPrimaryKeyException;
import se.l4.exofind.engine.index.IndexNotFoundException;
import se.l4.exofind.engine.index.IndexOutOfDateException;
import se.l4.exofind.engine.index.IndexReadonlyException;
import se.l4.exofind.engine.index.IndexSourceNotKeptException;
import se.l4.exofind.engine.index.IndexState;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.RegisteredIndex;
import se.l4.exofind.engine.index.state.IndexerOwnership;
import se.l4.exofind.engine.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * ReindexJobs fills new generations by reindexing from the ones they replace,
 * one job per index at a time, without the documents leaving the node.
 *
 * <p>A job streams the documents of a source generation into an empty target
 * through {@link Index#scanDocuments}, while a {@link ChangeLog} on the
 * source records what changes meanwhile. Replay rounds work the backlog down;
 * the final round runs under {@link Index#holdWrites()}, so the target is
 * complete at the moment it is promoted. A job asked to leave the promote to
 * its creator stops just before that and catches up periodically until the
 * promote endpoint finishes it.
 *
 * <p>Every step is checkpointed in a record the storage holds - see
 * {@link ReindexJobStorage} - so status is answered from the record on any
 * node, and the node holding the index resumes a half-finished job from it.
 * Ownership is only claimed on writes, so each candidate also sweeps the
 * records for unfinished jobs whose index nothing holds and claims them; the
 * winner resumes. How many jobs one node runs at once is bounded by
 * {@code indexer.reindex.max-concurrent}, with accepted jobs waiting in the
 * pending phase.
 */
@ApplicationScoped
public class ReindexJobs {
	private static final Log logger = Log.of(ReindexJobs.class);

	/**
	 * How many documents are copied between two checkpoints. Each batch is
	 * committed and pushed before the cursor moves past it, so the batch is
	 * also how much a resume can have to copy again.
	 */
	private static final int COPY_BATCH = 1000;

	/**
	 * Backlog small enough to leave to the held final drain, which is what
	 * bounds how long writes are held.
	 */
	private static final int SMALL_BACKLOG = 100;

	/**
	 * How many replay rounds are run before the job proceeds however large
	 * the backlog still is. A source written faster than it can be replayed
	 * would otherwise never converge; the held drain is what makes the end
	 * certain.
	 */
	private static final int MAX_REPLAY_ROUNDS = 10;

	/**
	 * How long a cancel waits for the job to let go before writing the record
	 * anyway.
	 */
	private static final Duration CANCEL_WAIT = Duration.ofSeconds(15);

	/**
	 * How long a stop waits for the running jobs to end themselves. Jobs are
	 * never interrupted, so this is a wait for the batch in flight to finish
	 * rather than for the job.
	 */
	private static final Duration STOP_WAIT = Duration.ofSeconds(30);

	private static final ErrorType TARGET_GENERATION_REQUIRED =
		ErrorType.withCode("reindex:target_generation_required")
			.withArguments("name")
			.withMessage(
				"`{{name}}` names an index rather than the generation to fill,"
					+ " which is written like `books@2`"
			);

	private static final ErrorType TARGET_IS_LIVE =
		ErrorType.withCode("reindex:target_is_live")
			.withArguments("name")
			.withMessage(
				"The generation `{{name}}` is the one its index answers for and"
					+ " cannot be filled by a reindex"
			);

	private static final ErrorType TARGET_NOT_EMPTY =
		ErrorType.withCode("reindex:target_not_empty")
			.withArguments("name")
			.withMessage(
				"The generation `{{name}}` already holds documents, and a reindex"
					+ " only fills an empty one"
			);

	private static final ErrorType SOURCE_OTHER_INDEX =
		ErrorType.withCode("reindex:source_other_index")
			.withArguments("from", "index")
			.withMessage(
				"`{{from}}` does not belong to `{{index}}` - a generation is"
					+ " filled from another generation of its own index"
			);

	private static final ErrorType SOURCE_IS_TARGET =
		ErrorType.withCode("reindex:source_is_target")
			.withArguments("name")
			.withMessage("The generation `{{name}}` cannot be filled from itself");

	private static final ErrorType PRIMARY_KEY_MISMATCH =
		ErrorType.withCode("reindex:primary_key_mismatch")
			.withArguments("source", "target")
			.withMessage(
				"The primary key of `{{target}}` differs from `{{source}}` in name"
					+ " or type, so changes to the source cannot be carried over by"
					+ " key"
			);

	private static final ErrorType PROMOTE_UNKNOWN =
		ErrorType.withCode("reindex:promote_unknown")
			.withArguments("value")
			.withMessage(
				"A reindex promotes on its own with `auto` or leaves it to the"
					+ " caller with `manual`, not with `{{value}}`"
			);

	private static final ErrorType DOCUMENT_REFUSED =
		ErrorType.withCode("reindex:document_refused")
			.withArguments("key", "reason")
			.withMessage(
				"The target refused the document with key `{{key}}`: {{reason}}"
			);

	private static final ErrorType IO_ERROR = ErrorType.withCode("reindex:io_error")
		.withMessage("The reindex record could not be read or written");

	private final NodeState nodeState;
	private final Indexes indexes;
	private final IndexRegistry registry;
	private final ReindexJobStorage storage;
	private final IndexerOwnership ownership;

	private final Duration sweepInterval;
	private final Duration catchUpInterval;

	/**
	 * How long the final drain waits after the promote before the one
	 * post-promote sweep, for writes that resolved the index name before the
	 * promote to land in the source.
	 */
	private final Duration promoteGrace;

	/**
	 * Runs the job bodies. Its size is the node's budget: accepted jobs past
	 * it wait in the queue, in the pending phase.
	 */
	private final ExecutorService pool;

	/**
	 * Runs the sweep, the catch-up rounds of ready jobs and resume checks -
	 * everything short, so the pool's slots stay for the jobs themselves.
	 */
	private final ScheduledExecutorService scheduler;

	/**
	 * The jobs this node is carrying, by index name - queued, running, or
	 * sitting ready. What refusing a second job and refusing writes to a
	 * target read, so both are answered without the storage.
	 */
	private final ConcurrentHashMap<String, Running> running;

	/**
	 * Held while a job is accepted, so two starts for the same index race for
	 * the record write rather than both submitting.
	 */
	private final ReentrantLock startLock;

	private final NodeState.Listener nodeStateListener;

	private volatile boolean stopped;

	@Inject
	public ReindexJobs(
		NodeState nodeState,
		Indexes indexes,
		IndexRegistry registry,
		ReindexJobStorage storage,
		IndexerOwnership ownership,
		@ConfigProperty(name = "exofind.indexer.reindex.max-concurrent", defaultValue = "2")
		int maxConcurrent,
		@ConfigProperty(name = "exofind.indexer.reindex.sweep-interval", defaultValue = "30s")
		Duration sweepInterval,
		@ConfigProperty(name = "exofind.indexer.reindex.catchup-interval", defaultValue = "30s")
		Duration catchUpInterval,
		@ConfigProperty(name = "exofind.indexer.reindex.promote-grace", defaultValue = "1s")
		Duration promoteGrace
	) {
		this.nodeState = nodeState;
		this.indexes = indexes;
		this.registry = registry;
		this.storage = storage;
		this.ownership = ownership;
		this.sweepInterval = sweepInterval;
		this.catchUpInterval = catchUpInterval;
		this.promoteGrace = promoteGrace;

		/*
		 * Daemon threads, as a stop leaves a job that has not reached its next
		 * batch running rather than interrupting it out of Lucene - the JVM
		 * must not wait for one that is stuck.
		 */
		this.pool = Executors.newFixedThreadPool(Math.max(1, maxConcurrent), runnable -> {
			var thread = new Thread(runnable, "reindex-job");
			thread.setDaemon(true);
			return thread;
		});
		this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "reindex-jobs");
			thread.setDaemon(true);
			return thread;
		});
		this.running = new ConcurrentHashMap<>();
		this.startLock = new ReentrantLock();

		/*
		 * Gaining an index is what makes a half-finished job of it this
		 * node's to resume; losing one means whatever runs here has to let
		 * go, as the successor resumes from the record.
		 */
		this.nodeStateListener = (state, index) -> scheduler.execute(() -> {
			if(index == null) {
				resumeHeld();
			} else if(state.isIndexer(index)) {
				resume(index);
			} else {
				var current = running.get(index);
				if(current != null) {
					abandon(current);
				}
			}
		});
	}

	void onStart(@Observes StartupEvent event) {
		nodeState.addListener(nodeStateListener);

		if(nodeState.isIndexerCandidate()) {
			/*
			 * Ownership may have settled before the listener above was
			 * registered, so what this node already holds is looked at once
			 * right away - which is also what picks the jobs of a node
			 * storing locally up when it restarts.
			 */
			scheduler.execute(this::resumeHeld);
			scheduler.scheduleWithFixedDelay(
				this::sweep,
				sweepInterval.toMillis(),
				sweepInterval.toMillis(),
				TimeUnit.MILLISECONDS
			);
		}
	}

	@PreDestroy
	void stop() {
		stopped = true;
		nodeState.removeListener(nodeStateListener);

		/*
		 * Shut down without interrupting what is running. A job reads the flag
		 * above between batches and ends itself, while an interrupt that lands
		 * in Lucene invalidates the lock the writer holds on the directory,
		 * which closes that writer and drops everything it had not committed -
		 * both what the job had copied and, for the source, whatever else was
		 * waiting to be written.
		 */
		scheduler.shutdown();
		pool.shutdown();

		try {
			if(!pool.awaitTermination(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
				logger.atWarn()
					.addKeyValue("waited", STOP_WAIT)
					.log("Reindex jobs are still running; leaving them to the records");
			}
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Start filling a generation from another generation of its index. The
	 * job is accepted once its record is written; the work itself runs in the
	 * background within the node's budget.
	 *
	 * @param targetName
	 *   the generation to fill, as {@code index@generation}
	 * @param from
	 *   the generation to read from - the index itself, or one generation of
	 *   it - or {@code null} for whichever is live
	 * @param promote
	 *   {@code auto} or {@code null} to promote once caught up,
	 *   {@code manual} to stop in the ready phase and leave the promote to
	 *   the caller
	 * @return
	 *   the job as accepted
	 * @throws ValidationException
	 *   if the target is not a pinned, empty, non-live generation, the source
	 *   does not belong to the same index, or the primary keys differ
	 * @throws IndexNotFoundException
	 *   if the deployment holds no such generation
	 * @throws IndexNoPrimaryKeyException
	 *   if the source or target declares no primary key
	 * @throws IndexSourceNotKeptException
	 *   if the source keeps no copy of its documents
	 * @throws ReindexInProgressException
	 *   if the index already has a job that is not finished
	 */
	public ReindexJob start(String targetName, String from, String promote) {
		var target = IndexName.parse(targetName);
		if(!target.isPinned()) {
			throw new ValidationException(
				TARGET_GENERATION_REQUIRED.toMessage(ObjectLocation.root(), "name", targetName)
			);
		}

		var manual = parsePromote(promote);
		var index = target.index();

		var registered = indexes.getRegistered(index)
			.orElseThrow(() -> new IndexNotFoundException(targetName));
		if(!registered.hasGeneration(target.generation())) {
			throw new IndexNotFoundException(targetName);
		}

		if(target.generation().equals(registered.live())) {
			throw new ValidationException(
				TARGET_IS_LIVE.toMessage(ObjectLocation.root(), "name", targetName)
			);
		}

		var sourceGen = resolveSource(registered, from, target);
		var sourceName = IndexName.of(index, sourceGen).toString();

		var sourceIndex = indexes.getOrThrow(sourceName);
		var targetIndex = indexes.getOrThrow(targetName);

		var sourceKey = sourceIndex.getPrimaryKey()
			.orElseThrow(() -> new IndexNoPrimaryKeyException(sourceName));
		if(!sourceIndex.isSourceStored()) {
			throw new IndexSourceNotKeptException(sourceName);
		}

		var targetKey = targetIndex.getPrimaryKey()
			.orElseThrow(() -> new IndexNoPrimaryKeyException(targetName));

		/*
		 * The change log carries keys as encoded terms and the replay removes
		 * documents in the target by them, so the two encodings have to be
		 * comparable - which they are exactly when the fields share a name
		 * and a type.
		 */
		if(
			!sourceKey.getName().equals(targetKey.getName())
				|| sourceKey.getDef().getType().getTypeCase()
					!= targetKey.getDef().getType().getTypeCase()
		) {
			throw new ValidationException(
				PRIMARY_KEY_MISMATCH.toMessage(
					ObjectLocation.root(),
					"source", sourceName,
					"target", targetName
				)
			);
		}

		long sourceCount;
		try {
			if(targetIndex.getDocumentCount() > 0 || targetIndex.getState() == IndexState.MODIFIED) {
				throw new ValidationException(
					TARGET_NOT_EMPTY.toMessage(ObjectLocation.root(), "name", targetName)
				);
			}

			sourceCount = sourceIndex.getDocumentCount();
		} catch(IOException e) {
			throw new EngineException(IO_ERROR, e);
		}

		startLock.lock();
		try {
			if(running.containsKey(index)) {
				throw new ReindexInProgressException(index);
			}

			String expectedVersion = null;
			var existing = storage.read(index);
			if(existing.isPresent()) {
				var previous = ReindexJob.fromStore(existing.get().record());
				if(previous.isEmpty() || !previous.get().phase().isFinished()) {
					throw new ReindexInProgressException(index);
				}

				expectedVersion = existing.get().version();
			}

			var now = Instant.now();
			var job = new ReindexJob(
				index,
				target.generation(),
				sourceGen,
				ReindexPhase.PENDING,
				null,
				0,
				sourceCount,
				0,
				null,
				manual,
				now,
				now
			);

			var version = storage.write(index, job.toStore(), expectedVersion);
			if(version == null) {
				throw new ReindexInProgressException(index);
			}

			var accepted = new Running(index, job, version);
			running.put(index, accepted);
			submit(accepted);

			logger.atInfo()
				.addKeyValue("index", index)
				.addKeyValue("target", job.targetName())
				.addKeyValue("source", job.sourceName())
				.log("Accepted a reindex");

			return job;
		} catch(IOException e) {
			throw new EngineException(IO_ERROR, e);
		} finally {
			startLock.unlock();
		}
	}

	/**
	 * Get the job of one index as its record stands, finished ones included.
	 * Answered from the record, so any node gives the same
	 * checkpoint-granular answer.
	 *
	 * @param index
	 *   name of the index, without a generation
	 */
	public Optional<ReindexJob> get(String index) {
		try {
			return storage.read(index)
				.flatMap(stored -> ReindexJob.fromStore(stored.record()));
		} catch(IOException e) {
			throw new EngineException(IO_ERROR, e);
		}
	}

	/**
	 * Every job there is a record of, finished ones included, in no
	 * particular order. One listing of the record prefix, whatever node is
	 * asked.
	 */
	public ListIterable<ReindexJob> list() {
		try {
			var jobs = Lists.mutable.<ReindexJob>empty();
			for(var stored : storage.list()) {
				ReindexJob.fromStore(stored.record()).ifPresent(jobs::add);
			}

			return jobs;
		} catch(IOException e) {
			throw new EngineException(IO_ERROR, e);
		}
	}

	/**
	 * Stop the job of an index. Tracking on the source ends and the partial
	 * target is left as it is, for a normal generation delete. Cancelling a
	 * job that already finished changes nothing.
	 *
	 * @param index
	 *   name of the index, without a generation
	 * @return
	 *   the job as its record stands after the cancel
	 * @throws ReindexNotFoundException
	 *   if the index has no job
	 */
	public ReindexJob cancel(String index) {
		var current = running.get(index);
		if(current != null) {
			current.cancelled = true;

			var catchUp = current.catchUp;
			if(catchUp != null) {
				catchUp.cancel(false);
			}

			/*
			 * The thread runs the job until it notices the flag, and lets go
			 * of the record when it does. The cleanup happens here rather
			 * than there, so a cancel of a job with no thread - one sitting
			 * ready - ends the same way.
			 */
			try {
				current.stopped.get(CANCEL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
			} catch(InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch(java.util.concurrent.ExecutionException | TimeoutException e) {
				// The record write below says what happened either way
			}

			current.lock.lock();
			try {
				if(running.get(index) == current && !current.job.phase().isFinished()) {
					finishCancelled(current);
				}
			} finally {
				current.lock.unlock();
			}

			return get(index).orElseThrow(() -> new ReindexNotFoundException(index));
		}

		/*
		 * Not carried here - a job of a dead node the sweep has not claimed
		 * yet, reached because the cancel was routed to whoever now writes
		 * the index. Cancelled straight against the record.
		 */
		try {
			var stored = storage.read(index)
				.orElseThrow(() -> new ReindexNotFoundException(index));
			var job = ReindexJob.fromStore(stored.record())
				.orElseThrow(() -> new ReindexNotFoundException(index));

			if(job.phase().isFinished()) {
				return job;
			}

			endTrackingQuietly(job);

			var cancelled = withPhase(job, ReindexPhase.CANCELLED);
			var version = storage.write(index, cancelled.toStore(), stored.version());
			if(version == null) {
				// Somebody resumed it in between; they own the record now
				return get(index).orElseThrow(() -> new ReindexNotFoundException(index));
			}

			return cancelled;
		} catch(IOException e) {
			throw new EngineException(IO_ERROR, e);
		}
	}

	/**
	 * Refuse a change to a generation a job of this node is filling. Called
	 * with the name a write arrived under; a name that is not a pinned
	 * generation is never a target and passes.
	 *
	 * @param name
	 *   the index, or one generation of it
	 * @throws ReindexTargetBusyException
	 *   if the name is the target of a job that is not finished
	 */
	public void checkTargetWritable(String name) {
		var parsed = IndexName.tryParse(name).orElse(null);
		if(parsed == null || !parsed.isPinned()) {
			return;
		}

		var current = running.get(parsed.index());
		if(current == null) {
			return;
		}

		var job = current.job;
		if(!job.phase().isFinished() && job.target().equals(parsed.generation())) {
			throw new ReindexTargetBusyException(name);
		}
	}

	/**
	 * Serve a promote through the job that is filling the named generation,
	 * when there is one. A ready job is finished the way an automatic one
	 * finishes itself - hold, final drain, conditional promote, release, one
	 * post-promote sweep; one that has not caught up yet is refused, because
	 * promoting a partial target is what the job exists to prevent.
	 *
	 * @param name
	 *   the generation being promoted, as {@code index@generation}
	 * @return
	 *   whether the promote was performed here - {@code false} means the
	 *   name is not the target of a job this node carries, and the ordinary
	 *   promote proceeds
	 * @throws ReindexTargetBusyException
	 *   if the job filling the generation is not ready yet
	 */
	public boolean promoteThroughJob(String name) {
		var parsed = IndexName.parse(name);
		if(!parsed.isPinned()) {
			return false;
		}

		var current = running.get(parsed.index());
		if(current == null) {
			/*
			 * Not carried here yet - resuming after a failover, or waiting
			 * for the sweep. The record still gates the promote: the job
			 * exists to keep a partial target from going live, and it cannot
			 * be finished here before it has been resumed.
			 */
			var recorded = get(parsed.index()).orElse(null);
			if(
				recorded != null
					&& !recorded.phase().isFinished()
					&& recorded.target().equals(parsed.generation())
			) {
				throw new ReindexTargetBusyException(name);
			}

			return false;
		}

		var job = current.job;
		if(job.phase().isFinished() || !job.target().equals(parsed.generation())) {
			return false;
		}

		if(job.phase() != ReindexPhase.READY) {
			throw new ReindexTargetBusyException(name);
		}

		var catchUp = current.catchUp;
		if(catchUp != null) {
			catchUp.cancel(false);
		}

		current.lock.lock();
		try {
			if(running.get(parsed.index()) != current
				|| current.job.phase() != ReindexPhase.READY) {
				return false;
			}

			var source = indexes.getOrThrow(current.job.sourceName());
			var target = indexes.getOrThrow(current.job.targetName());
			var log = source.beginChangeTracking();

			promoteAndFinish(current, source, target, log);
			return true;
		} catch(JobCancelled e) {
			finishCancelled(current);
			throw new ReindexTargetBusyException(name);
		} catch(JobLost e) {
			abandon(current);
			return false;
		} catch(DocumentRefused e) {
			fail(current, e);
			throw e.toEngineException();
		} catch(IOException e) {
			throw new EngineException(IO_ERROR, e);
		} finally {
			current.lock.unlock();
		}
	}

	/**
	 * Read who promotes: {@code auto} or {@code null} means the job does,
	 * {@code manual} the caller. Public so a request carrying the value next
	 * to other work can refuse it before doing any.
	 *
	 * @throws ValidationException
	 *   if the value is neither
	 */
	public static boolean parsePromote(String promote) {
		if(promote == null || promote.equals("auto")) {
			return false;
		}

		if(promote.equals("manual")) {
			return true;
		}

		throw new ValidationException(
			PROMOTE_UNKNOWN.toMessage(ObjectLocation.root(), "value", promote)
		);
	}

	/**
	 * Which generation the documents are read from.
	 */
	private static String resolveSource(RegisteredIndex registered, String from, IndexName target) {
		String generation;
		if(from == null) {
			generation = registered.live();
		} else {
			var parsed = IndexName.parse(from);
			if(!parsed.index().equals(target.index())) {
				throw new ValidationException(
					SOURCE_OTHER_INDEX.toMessage(
						ObjectLocation.root(),
						"from", from,
						"index", target.index()
					)
				);
			}

			generation = parsed.isPinned() ? parsed.generation() : registered.live();
		}

		if(generation == null) {
			throw new IndexNotFoundException(target.index());
		}

		if(!registered.hasGeneration(generation)) {
			throw new IndexNotFoundException(
				IndexName.of(target.index(), generation).toString()
			);
		}

		if(generation.equals(target.generation())) {
			throw new ValidationException(
				SOURCE_IS_TARGET.toMessage(ObjectLocation.root(), "name", target.toString())
			);
		}

		return generation;
	}

	private void submit(Running job) {
		try {
			pool.execute(() -> run(job));
		} catch(RejectedExecutionException e) {
			// Shutting down; the record stays for whoever resumes it
			running.remove(job.index, job);
		}
	}

	/**
	 * The body of one job, entered at whatever phase the record says.
	 * Everything it needs is re-resolved here rather than carried from the
	 * accept, so a resume enters the same way a fresh job does.
	 */
	private void run(Running current) {
		current.lock.lock();
		try {
			/*
			 * Checked before anything is touched: a job cancelled while it
			 * waited in the queue must not restart tracking on the source, and
			 * one whose record a cancel already closed is gone.
			 */
			if(running.get(current.index) != current) {
				return;
			}

			ensureRunnable(current);

			var job = current.job;
			var source = indexes.getOrThrow(job.sourceName());
			var target = indexes.getOrThrow(job.targetName());

			/*
			 * Loads the pushed log on a resume and starts a fresh one on a
			 * new job - the log has to be recording before the source commit
			 * below, or a write between the two would be in neither the
			 * commit nor the log.
			 */
			var log = source.beginChangeTracking();

			if(job.phase() == ReindexPhase.PENDING || job.phase() == ReindexPhase.COPYING) {
				/*
				 * The copy reads the last commit, so the source is committed
				 * once tracking is on - everything up to this moment is then
				 * in the commit, and everything after it in the log.
				 */
				source.commit();
				checkpoint(current, j -> withPhase(j, ReindexPhase.COPYING));

				copy(current, source, target, log);

				checkpoint(current, j -> withBacklog(
					withPhase(j, ReindexPhase.REPLAYING),
					log.size()
				));
			}

			if(current.job.phase() == ReindexPhase.REPLAYING) {
				replayRounds(current, source, target, log);
			}

			if(current.job.phase() == ReindexPhase.READY
				|| (current.job.phase() == ReindexPhase.REPLAYING && current.job.manualPromote())) {
				checkpoint(current, j -> withBacklog(
					withPhase(j, ReindexPhase.READY),
					log.size()
				));
				scheduleCatchUp(current);
				return;
			}

			if(current.job.phase() == ReindexPhase.PROMOTING
				&& current.job.target().equals(liveOf(current.index))) {
				/*
				 * The promote landed before the record could say so - the job
				 * died in between. Only what follows the promote is left.
				 */
				finishPromoted(current, source, target, log);
				return;
			}

			promoteAndFinish(current, source, target, log);
		} catch(JobCancelled e) {
			// The cancel that raised the flag writes the record
		} catch(JobLost e) {
			abandon(current);
		} catch(DocumentRefused e) {
			fail(current, e);
		} catch(IndexReadonlyException | IndexOutOfDateException e) {
			/*
			 * The index moved to another node mid-step; the record is the
			 * successor's to pick up, so nothing is written here.
			 */
			abandon(current);
		} catch(Exception e) {
			logger.atError()
				.addKeyValue("index", current.index)
				.setCause(e)
				.log("Reindex failed; " + e.getMessage());

			fail(current, e.getMessage());
		} finally {
			current.stopped.complete(null);
			current.lock.unlock();
		}
	}

	/**
	 * Stream the source into the target in primary key order, committing and
	 * checkpointing per batch.
	 */
	private void copy(Running current, Index source, Index target, ChangeLog log)
		throws IOException {
		var keyField = source.getPrimaryKey().orElseThrow().getName();
		var after = current.job.cursor() == null
			? null
			: source.parsePrimaryKey(current.job.cursor());

		while(true) {
			ensureRunnable(current);

			var lastKey = new String[1];
			var read = source.scanDocuments(after, COPY_BATCH, document -> {
				try {
					target.addDocument(document);
				} catch(ValidationException e) {
					throw new DocumentRefused(
						String.valueOf(document.get(keyField)),
						e.getMessage()
					);
				}

				lastKey[0] = String.valueOf(document.get(keyField));
			});

			if(read > 0) {
				/*
				 * The push has to land before the cursor moves - a resume can
				 * then only re-copy, and copying by key is idempotent.
				 */
				target.commit();

				var cursor = lastKey[0];
				var copied = read;
				checkpoint(current, j -> new ReindexJob(
					j.index(),
					j.target(),
					j.source(),
					j.phase(),
					cursor,
					j.documentsCopied() + copied,
					j.sourceDocCount(),
					log.size(),
					null,
					j.manualPromote(),
					j.startedAt(),
					j.updatedAt()
				));

				after = source.parsePrimaryKey(cursor);
			}

			if(read < COPY_BATCH) {
				return;
			}
		}
	}

	/**
	 * Work the backlog down until it is small enough for the held drain,
	 * bounded so a source written faster than it replays still reaches the
	 * hold.
	 */
	private void replayRounds(Running current, Index source, Index target, ChangeLog log)
		throws IOException {
		for(var round = 0; round < MAX_REPLAY_ROUNDS && log.size() > SMALL_BACKLOG; round++) {
			replayRound(current, source, target, log);
		}
	}

	/**
	 * One round: everything the log names right now is read from the source
	 * as it is and carried over, and forgotten once the target's push holds
	 * it - a key written again meanwhile survives the forget.
	 */
	private void replayRound(Running current, Index source, Index target, ChangeLog log)
		throws IOException {
		var snapshot = log.snapshot();
		if(snapshot.keys().isEmpty()) {
			return;
		}

		replay(current, source, target, snapshot);
		target.commit();
		log.forget(snapshot);

		checkpoint(current, j -> withBacklog(j, log.size()));
	}

	private void replay(
		Running current,
		Index source,
		Index target,
		ChangeLog.Snapshot snapshot
	) throws IOException {
		var keyField = source.getPrimaryKey().orElseThrow().getName();

		for(var key : snapshot.keys()) {
			ensureRunnable(current);

			var document = source.getDocumentByKeyTerm(key);
			if(document == null) {
				target.deleteDocumentByKeyTerm(key);
			} else {
				try {
					target.addDocument(document);
				} catch(ValidationException e) {
					throw new DocumentRefused(
						String.valueOf(document.get(keyField)),
						e.getMessage()
					);
				}
			}
		}
	}

	/**
	 * The end of a job that promotes: hold writes, drain what is left,
	 * promote conditionally, release - then what every promote is followed
	 * by.
	 */
	private void promoteAndFinish(Running current, Index source, Index target, ChangeLog log)
		throws IOException {
		checkpoint(current, j -> withPhase(j, ReindexPhase.PROMOTING));

		/*
		 * The hold covers only this final round: writes already underway
		 * finish and are in the log, new ones wait at the gate, so the log
		 * is empty at the moment of the promote.
		 */
		try(var hold = source.holdWrites()) {
			var snapshot = log.snapshot();
			replay(current, source, target, snapshot);
			target.commit();
			log.forget(snapshot);

			registry.promote(current.index, current.job.target());
		}

		logger.atInfo()
			.addKeyValue("index", current.index)
			.addKeyValue("target", current.job.targetName())
			.log("Reindex promoted the generation it filled");

		finishPromoted(current, source, target, log);
	}

	/**
	 * What follows the promote: one sweep for writes that resolved the index
	 * name before it and landed in the source after the release, then the
	 * end of tracking.
	 */
	private void finishPromoted(Running current, Index source, Index target, ChangeLog log)
		throws IOException {
		if(!promoteGrace.isZero()) {
			try {
				Thread.sleep(promoteGrace.toMillis());
			} catch(InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		var sweep = log.snapshot();
		if(!sweep.keys().isEmpty()) {
			replay(current, source, target, sweep);
			target.commit();
			log.forget(sweep);
		}

		source.endChangeTracking();

		/*
		 * Pushed so the remote stops carrying the log file - left there, a
		 * later job would resume keys tracking already answered for.
		 */
		source.commit();

		checkpoint(current, j -> withBacklog(withPhase(j, ReindexPhase.DONE), 0));
		running.remove(current.index, current);
	}

	/**
	 * Keep a ready job caught up until the creator promotes, one small round
	 * per interval.
	 */
	private void scheduleCatchUp(Running current) {
		if(stopped) {
			return;
		}

		current.catchUp = scheduler.scheduleWithFixedDelay(
			() -> catchUp(current),
			catchUpInterval.toMillis(),
			catchUpInterval.toMillis(),
			TimeUnit.MILLISECONDS
		);
	}

	private void catchUp(Running current) {
		if(!current.lock.tryLock()) {
			// A promote or cancel has the job; it ends the schedule itself
			return;
		}

		try {
			if(running.get(current.index) != current
				|| current.job.phase() != ReindexPhase.READY) {
				return;
			}

			ensureRunnable(current);

			var source = indexes.getOrThrow(current.job.sourceName());
			var target = indexes.getOrThrow(current.job.targetName());
			var log = source.beginChangeTracking();

			replayRound(current, source, target, log);
		} catch(JobCancelled e) {
			// The cancel writes the record; the schedule is already cancelled
		} catch(JobLost e) {
			abandon(current);
		} catch(DocumentRefused e) {
			fail(current, e);
		} catch(IndexReadonlyException | IndexOutOfDateException e) {
			abandon(current);
		} catch(Exception e) {
			logger.atWarn()
				.addKeyValue("index", current.index)
				.setCause(e)
				.log("Could not catch the ready reindex up; " + e.getMessage());
		} finally {
			current.lock.unlock();
		}
	}

	/**
	 * Pick up the unfinished jobs whose index nothing holds. Ownership is only
	 * claimed on writes, so without this an index with a half-finished job and
	 * no traffic would sit unclaimed forever.
	 */
	private void sweep() {
		if(stopped || !nodeState.isIndexerCandidate()) {
			return;
		}

		try {
			/*
			 * Every candidate runs this a few times a minute, so it reads the
			 * jobs the storage tracks as unfinished. Reading every record
			 * would cost one read per index ever reindexed, on every pass.
			 */
			for(var stored : storage.listUnfinished()) {
				var job = ReindexJob.fromStore(stored.record()).orElse(null);
				if(job == null || job.phase().isFinished()) {
					continue;
				}

				var index = job.index();
				if(running.containsKey(index)) {
					continue;
				}

				if(nodeState.isIndexer(index)) {
					resume(index);
					continue;
				}

				if(ownership.hasHolder(index)) {
					// The holder resumes it; a lapsed claim frees it for a later pass
					continue;
				}

				if(ownership.tryClaim(index)) {
					indexes.reopenForWriting(index);
					resume(index);
				}
			}
		} catch(IOException | RuntimeException e) {
			// Letting this out would cancel the schedule
			logger.atWarn()
				.setCause(e)
				.log("Could not sweep the reindex records; " + e.getMessage());
		}
	}

	/**
	 * Pick the jobs of every index this node holds up, for ownership that
	 * settled before the listener could hear about it.
	 */
	private void resumeHeld() {
		try {
			/*
			 * Every record, not the narrowed listing the sweep reads: a job
			 * the storage has lost track of is resumed by nothing else. This
			 * runs when a node starts and when ownership settles, which is
			 * rare enough to pay a full pass for.
			 */
			for(var stored : storage.list()) {
				var job = ReindexJob.fromStore(stored.record()).orElse(null);
				if(job != null && !job.phase().isFinished()) {
					resume(job.index());
				}
			}
		} catch(IOException | RuntimeException e) {
			logger.atWarn()
				.setCause(e)
				.log("Could not look for reindexes to resume; " + e.getMessage());
		}
	}

	/**
	 * Carry the job of one index here, from wherever its record says it
	 * stands. Does nothing for an index this node does not write, a job
	 * already carried, or a finished one.
	 */
	private void resume(String index) {
		if(stopped || running.containsKey(index) || !nodeState.isIndexer(index)) {
			return;
		}

		try {
			var stored = storage.read(index).orElse(null);
			if(stored == null) {
				return;
			}

			var job = ReindexJob.fromStore(stored.record()).orElse(null);
			if(job == null || job.phase().isFinished()) {
				return;
			}

			var registered = indexes.getRegistered(index).orElse(null);
			if(
				registered == null
					|| !registered.hasGeneration(job.target())
					|| !registered.hasGeneration(job.source())
			) {
				/*
				 * Half of the job is gone - the target or the source was
				 * deleted while nothing ran it. There is nothing to resume
				 * into, so the record says the job failed rather than being
				 * retried forever.
				 */
				storage.write(
					index,
					withError(job, "The source or target generation no longer exists")
						.toStore(),
					stored.version()
				);
				return;
			}

			var resumed = new Running(index, job, stored.version());
			if(running.putIfAbsent(index, resumed) == null) {
				logger.atInfo()
					.addKeyValue("index", index)
					.addKeyValue("phase", job.phase().id())
					.log("Resuming a reindex");

				submit(resumed);
			}
		} catch(IOException e) {
			logger.atWarn()
				.addKeyValue("index", index)
				.setCause(e)
				.log("Could not resume the reindex; " + e.getMessage());
		}
	}

	/**
	 * Refuse to go on when the job was cancelled or the index is no longer
	 * this node's to write. Called between steps, where letting go leaves the
	 * record at a checkpoint a successor resumes from.
	 */
	private void ensureRunnable(Running current) {
		if(current.cancelled) {
			throw new JobCancelled();
		}

		if(stopped || !nodeState.isIndexer(current.index)) {
			throw new JobLost();
		}
	}

	/**
	 * Replace the record, keeping the version in step. A write that is
	 * refused means another node resumed the job, which ends it here without
	 * touching anything further.
	 */
	private void checkpoint(Running current, UnaryOperator<ReindexJob> change) throws IOException {
		var updated = touch(change.apply(current.job));

		var version = storage.write(current.index, updated.toStore(), current.version);
		if(version == null) {
			throw new JobLost();
		}

		current.job = updated;
		current.version = version;
	}

	/**
	 * End a job that a cancel stopped: tracking ends, the record says
	 * cancelled, the partial target stays. Called under the job's lock.
	 */
	private void finishCancelled(Running current) {
		endTrackingQuietly(current.job);

		try {
			checkpoint(current, j -> withPhase(j, ReindexPhase.CANCELLED));
		} catch(IOException | JobLost e) {
			// The record is another node's, or unreachable - either way not ours
		}

		running.remove(current.index, current);

		logger.atInfo()
			.addKeyValue("index", current.index)
			.log("Reindex was cancelled");
	}

	private void fail(Running current, DocumentRefused refused) {
		fail(
			current,
			DOCUMENT_REFUSED.format(
				ErrorType.toArguments("key", refused.key, "reason", refused.reason)
			)
		);
	}

	/**
	 * End a job that cannot continue, before any promote: tracking ends and
	 * the record carries the reason. The target is never promoted partial or
	 * wrong, which is what makes every reindex safe to attempt.
	 */
	private void fail(Running current, String reason) {
		endTrackingQuietly(current.job);

		try {
			checkpoint(current, j -> withError(j, reason));
		} catch(IOException | JobLost e) {
			logger.atWarn()
				.addKeyValue("index", current.index)
				.log("Could not record why the reindex failed");
		}

		running.remove(current.index, current);

		logger.atError()
			.addKeyValue("index", current.index)
			.log("Reindex failed; " + reason);
	}

	/**
	 * Let go without touching the record - the job is another node's now, or
	 * this node is shutting down.
	 */
	private void abandon(Running current) {
		var catchUp = current.catchUp;
		if(catchUp != null) {
			catchUp.cancel(false);
		}

		running.remove(current.index, current);
	}

	/**
	 * Stop the source recording changes for a job that is ending without a
	 * promote. Best effort - a source that cannot be reached leaves its log
	 * for the delete of the target to make irrelevant.
	 */
	private void endTrackingQuietly(ReindexJob job) {
		try {
			var source = indexes.getOrThrow(job.sourceName());
			source.endChangeTracking();
			source.commit();
		} catch(IOException | RuntimeException e) {
			logger.atWarn()
				.addKeyValue("index", job.index())
				.setCause(e)
				.log("Could not end change tracking on the source; " + e.getMessage());
		}
	}

	private String liveOf(String index) {
		return indexes.getRegistered(index).map(RegisteredIndex::live).orElse(null);
	}

	private static ReindexJob withPhase(ReindexJob job, ReindexPhase phase) {
		return new ReindexJob(
			job.index(),
			job.target(),
			job.source(),
			phase,
			job.cursor(),
			job.documentsCopied(),
			job.sourceDocCount(),
			job.backlog(),
			null,
			job.manualPromote(),
			job.startedAt(),
			job.updatedAt()
		);
	}

	private static ReindexJob withBacklog(ReindexJob job, long backlog) {
		return new ReindexJob(
			job.index(),
			job.target(),
			job.source(),
			job.phase(),
			job.cursor(),
			job.documentsCopied(),
			job.sourceDocCount(),
			backlog,
			job.error(),
			job.manualPromote(),
			job.startedAt(),
			job.updatedAt()
		);
	}

	private static ReindexJob withError(ReindexJob job, String error) {
		return new ReindexJob(
			job.index(),
			job.target(),
			job.source(),
			ReindexPhase.FAILED,
			job.cursor(),
			job.documentsCopied(),
			job.sourceDocCount(),
			job.backlog(),
			error,
			job.manualPromote(),
			job.startedAt(),
			job.updatedAt()
		);
	}

	private static ReindexJob touch(ReindexJob job) {
		return new ReindexJob(
			job.index(),
			job.target(),
			job.source(),
			job.phase(),
			job.cursor(),
			job.documentsCopied(),
			job.sourceDocCount(),
			job.backlog(),
			job.error(),
			job.manualPromote(),
			job.startedAt(),
			Instant.now()
		);
	}

	/**
	 * One job as this node carries it. The lock serializes the phases of the
	 * job against a promote, a cancel and the catch-up rounds; the record
	 * version moves only under it.
	 */
	private static final class Running {
		final String index;
		final ReentrantLock lock;
		final java.util.concurrent.CompletableFuture<Void> stopped;

		volatile ReindexJob job;
		String version;
		volatile boolean cancelled;
		volatile ScheduledFuture<?> catchUp;

		Running(String index, ReindexJob job, String version) {
			this.index = index;
			this.job = job;
			this.version = version;
			this.lock = new ReentrantLock();
			this.stopped = new java.util.concurrent.CompletableFuture<>();
		}
	}

	/**
	 * Raised between steps when the cancel flag is up. The cancel that
	 * raised it writes the record.
	 */
	private static final class JobCancelled extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	/**
	 * Raised when the job stopped being this node's - the record moved under
	 * it, ownership went, or the node is shutting down. Nothing more may be
	 * written.
	 */
	private static final class JobLost extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	/**
	 * A document the target refused, carried with its key so the failure
	 * names the document to fix.
	 */
	private static final class DocumentRefused extends RuntimeException {
		private static final long serialVersionUID = 1L;

		final String key;
		final String reason;

		DocumentRefused(String key, String reason) {
			this.key = key;
			this.reason = reason;
		}

		EngineException toEngineException() {
			return new EngineException(DOCUMENT_REFUSED, "key", key, "reason", reason);
		}
	}
}
