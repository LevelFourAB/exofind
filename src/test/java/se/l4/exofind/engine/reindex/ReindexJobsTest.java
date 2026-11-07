package se.l4.exofind.engine.reindex;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ListIterable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeIdentity;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.DocumentPatch;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexSourceNotKeptException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LiveGenerationMovedException;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int64FieldTypeDef;
import se.l4.exofind.engine.index.schema.SignalConfig;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.LocalIndexerOwnership;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * Tests for filling a generation by reindexing from another one - the copy,
 * the drain at the promote, who promotes, what is refused up front, what a
 * record left by a dead node resumes into, and what a node that stops while a
 * job copies leaves behind.
 */
public class ReindexJobsTest {
	private static final Duration WAIT = Duration.ofSeconds(10);

	@TempDir
	Path storageDirectory;

	NodeState nodeState;
	IndexRegistry registry;
	Indexes indexes;
	LocalReindexJobStorage storage;
	ReindexJobs jobs;

	@BeforeEach
	void setup() throws IOException {
		nodeState = new NodeState(true);
		nodeState.updateOwnership(true);

		registry = new IndexRegistry(
			new LocalRegistryStorage(storageDirectory.resolve("registry.ef.bin")),
			Duration.ofMinutes(5)
		);

		indexes = newIndexes();

		storage = new LocalReindexJobStorage(
			storageDirectory.resolve("jobs").resolve("reindex").resolve("records")
		);

		jobs = newJobs();
	}

	@AfterEach
	void cleanup() {
		jobs.stop();
		indexes.close();
	}

	private Indexes newIndexes() throws IOException {
		return newIndexes(OptionalInt.empty(), Duration.ofSeconds(10));
	}

	private Indexes newIndexes(OptionalInt maxOpen, Duration closeGracePeriod) throws IOException {
		return new Indexes(
			nodeState,
			new NoopSyncProvider(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			storageDirectory,
			maxOpen,
			Duration.ofMinutes(5),
			Duration.ofMinutes(10),
			4,
			closeGracePeriod,
			0,
			Duration.ZERO,
			Optional.empty(),
			Optional.empty(),
			Duration.ofHours(24),
			Duration.ofHours(168),
			Duration.ofHours(1)
		);
	}

	private ReindexJobs newJobs() {
		return newJobs(indexes, "node-a");
	}

	private ReindexJobs newJobs(Indexes indexes) {
		return newJobs(indexes, "node-a");
	}

	private ReindexJobs newJobs(Indexes indexes, String node) {
		return newJobs(indexes, node, 2);
	}

	private ReindexJobs newJobs(Indexes indexes, String node, int maxConcurrent) {
		var ownership = new LocalIndexerOwnership();
		ownership.start((index, owner) -> {
		});

		return new ReindexJobs(
			nodeState,
			new NodeIdentity(node),
			indexes,
			registry,
			storage,
			ownership,
			maxConcurrent,
			Duration.ofMinutes(5),
			Duration.ofMinutes(5)
		);
	}

	@Test
	public void aReindexCopiesEverythingAndPromotes() throws Exception {
		var source = catalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		var accepted = jobs.start("catalogue@2", null, null, "tester");
		assertThat(accepted.phase(), is(ReindexPhase.PENDING));
		assertThat(accepted.id(), is(notNullValue()));
		assertThat(accepted.startedBy(), is("tester"));
		assertThat(accepted.node(), is("node-a"));
		assertThat(accepted.finishedAt(), is(nullValue()));

		awaitPhase("catalogue", ReindexPhase.DONE);

		assertThat(registry.get("catalogue").orElseThrow().live(), is("2"));

		// The record keeps what the accept wrote and says when the job ended
		var done = jobs.get("catalogue").orElseThrow();
		assertThat(done.id(), is(accepted.id()));
		assertThat(done.startedBy(), is("tester"));
		assertThat(done.node(), is("node-a"));
		assertThat(done.finishedAt(), is(notNullValue()));
		assertThat(done.finishedAt().isBefore(done.startedAt()), is(false));

		var target = indexes.getOrThrow("catalogue@2");
		assertThat(target.getDocumentCount(), is(3L));
		assertThat(target.getDocument("1").get("name"), is("Blueberry jam"));

		// Tracking ended with the job
		assertTrue(source.getChangeLog().isEmpty());
	}

	@Test
	public void aManualJobStopsReadyAndThePromoteDrainsIt() throws Exception {
		var source = catalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		jobs.start("catalogue@2", null, "manual", "tester");
		awaitPhase("catalogue", ReindexPhase.READY);

		// Not promoted until the caller says so
		assertThat(registry.get("catalogue").orElseThrow().live(), is("1"));

		/*
		 * Changes that land after the copy caught up have to be carried over
		 * by the drain the promote runs - a write, and a removal that only
		 * the change log knows the key of.
		 */
		source.addDocument(doc("4", "Rosehip soup", "soup"));
		source.deleteDocument("1");

		assertTrue(jobs.promoteThroughJob("catalogue@2"));

		awaitPhase("catalogue", ReindexPhase.DONE);
		assertThat(registry.get("catalogue").orElseThrow().live(), is("2"));

		var target = indexes.getOrThrow("catalogue@2");
		assertThat(target.getDocumentCount(), is(3L));
		assertThat(target.getDocument("4"), is(notNullValue()));
		assertThat(target.getDocument("1"), is(nullValue()));
	}

	/**
	 * The promote is what makes the target answer for the index name, so only
	 * the node that still writes the index may make it. The commit before it
	 * pushes to the storage, which is long enough for the index to be handed
	 * to another node in the meantime.
	 */
	@Test
	public void aPromoteStopsWhenTheIndexMovesAwayAfterTheLastCommit() throws Exception {
		catalogue();
		var target = indexes.createGeneration("catalogue@2", definition().build());

		/*
		 * Armed only once the job waits for the promote, so the commits of the
		 * copy are left alone.
		 */
		var handOver = new AtomicBoolean();
		var handedOver = Mockito.spy(target);
		Mockito.doAnswer(invocation -> {
			target.commit();
			if(handOver.get()) {
				nodeState.updateOwnership(false);
			}

			return null;
		}).when(handedOver).commit();

		var withHandover = Mockito.spy(indexes);
		Mockito.doReturn(handedOver).when(withHandover).getOrThrow("catalogue@2");

		jobs.stop();
		jobs = newJobs(withHandover);

		jobs.start("catalogue@2", null, "manual", "tester");
		awaitPhase("catalogue", ReindexPhase.READY);

		handOver.set(true);
		assertThat(jobs.promoteThroughJob("catalogue@2"), is(false));

		// The generation the index answers for is the successor's to change
		assertThat(registry.get("catalogue").orElseThrow().live(), is("1"));
	}

	@Test
	public void theRecordSaysHowFarTheCopyCame() throws Exception {
		catalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		jobs.start("catalogue@2", null, null, "tester");
		awaitPhase("catalogue", ReindexPhase.DONE);

		var job = jobs.get("catalogue").orElseThrow();
		assertThat(job.documentsCopied(), is(3L));
		assertThat(job.sourceDocCount(), is(3L));
		assertThat(job.cursor(), is("3"));
	}

	@Test
	public void aTargetHoldingDocumentsIsRefused() throws Exception {
		catalogue();
		var target = indexes.createGeneration("catalogue@2", definition().build());
		target.addDocument(doc("9", "Stray", "misc"));
		target.commit();

		assertThrows(
			ValidationException.class,
			() -> jobs.start("catalogue@2", null, null, "tester")
		);
	}

	@Test
	public void theLiveGenerationIsRefusedAsTarget() throws Exception {
		catalogue();

		assertThrows(
			ValidationException.class,
			() -> jobs.start("catalogue@1", null, null, "tester")
		);
	}

	@Test
	public void aSourceThatKeepsNoCopiesIsRefused() throws Exception {
		var index = indexes.create(
			"logs",
			definition().setSource(IndexDef.SourceMode.SOURCE_MODE_NONE).build()
		);
		index.addDocument(doc("1", "One", "misc"));
		index.commit();

		indexes.createGeneration("logs@2", definition().build());

		assertThrows(
			IndexSourceNotKeptException.class,
			() -> jobs.start("logs@2", null, null, "tester")
		);
	}

	@Test
	public void aPrimaryKeyThatDiffersInTypeIsRefused() throws Exception {
		catalogue();

		indexes.createGeneration(
			"catalogue@2",
			IndexDef.newBuilder()
				.putFields(
					"id",
					FieldDef.newBuilder()
						.setPrimaryKey(true)
						.setType(
							FieldTypeDef.newBuilder()
								.setInt64(Int64FieldTypeDef.getDefaultInstance())
						)
						.build()
				)
				.build()
		);

		assertThrows(
			ValidationException.class,
			() -> jobs.start("catalogue@2", null, null, "tester")
		);
	}

	@Test
	public void aSecondJobForTheIndexIsRefused() throws Exception {
		catalogue();
		indexes.createGeneration("catalogue@2", definition().build());
		indexes.createGeneration("catalogue@3", definition().build());

		jobs.start("catalogue@2", null, "manual", "tester");
		awaitPhase("catalogue", ReindexPhase.READY);

		assertThrows(
			ReindexInProgressException.class,
			() -> jobs.start("catalogue@3", null, null, "tester")
		);
	}

	@Test
	public void cancellingEndsTrackingAndLeavesThePartialTarget() throws Exception {
		var source = catalogue();
		indexes.createGeneration("catalogue@2", definition().build());
		indexes.createGeneration("catalogue@3", definition().build());

		jobs.start("catalogue@2", null, "manual", "tester");
		awaitPhase("catalogue", ReindexPhase.READY);

		var cancelled = jobs.cancel("catalogue");

		assertThat(cancelled.phase(), is(ReindexPhase.CANCELLED));
		assertThat(cancelled.finishedAt(), is(notNullValue()));
		assertThat(registry.get("catalogue").orElseThrow().live(), is("1"));
		assertTrue(source.getChangeLog().isEmpty());

		// A finished job frees the index for the next one, with an id of its own
		var next = jobs.start("catalogue@3", null, "manual", "tester");
		assertThat(next.id(), is(not(cancelled.id())));
	}

	@Test
	public void cancellingAJobThatWaitsForASlotAnswersAtOnce() throws Exception {
		// One job at a time, so the second one waits in the queue
		jobs.stop();
		jobs = newJobs(indexes, "node-a", 1);

		catalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		var pantry = indexes.create("pantry", definition().build());
		pantry.addDocument(doc("1", "Sea salt", "staples"));
		pantry.commit();
		indexes.createGeneration("pantry@2", definition().build());

		/*
		 * The hold stops the copy of the first job in its first batch, so the
		 * only slot stays taken while the second job is cancelled.
		 */
		try(var hold = indexes.getOrThrow("catalogue@2").holdWrites()) {
			jobs.start("catalogue@2", null, "manual", "tester");
			awaitPhase("catalogue", ReindexPhase.COPYING);

			jobs.start("pantry@2", null, "manual", "tester");
			awaitPhase("pantry", ReindexPhase.PENDING);

			var started = System.nanoTime();
			var cancelled = jobs.cancel("pantry");
			var waited = Duration.ofNanos(System.nanoTime() - started);

			assertThat(cancelled.phase(), is(ReindexPhase.CANCELLED));
			assertTrue(
				waited.compareTo(Duration.ofSeconds(5)) < 0,
				"The cancel waited " + waited + " for a job that had not started"
			);
		}
	}

	@Test
	public void writesToTheTargetAreRefusedWhileTheJobFills() throws Exception {
		catalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		jobs.start("catalogue@2", null, "manual", "tester");
		awaitPhase("catalogue", ReindexPhase.READY);

		assertThrows(
			ReindexTargetBusyException.class,
			() -> jobs.checkTargetWritable("catalogue@2")
		);

		// The index itself, and the source generation, stay writable
		jobs.checkTargetWritable("catalogue");
		jobs.checkTargetWritable("catalogue@1");
	}

	@Test
	public void promotingTheTargetOfAnUnresumedJobIsRefused() throws Exception {
		catalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		/*
		 * A record another node wrote and nothing here carries - the promote
		 * still has to be gated by it, or a partial target would go live.
		 */
		var now = Instant.now();
		storage.write(
			"catalogue",
			new ReindexJob(
				"job-1", "catalogue", "2", "1",
				ReindexPhase.COPYING,
				null, 0, 3, 0, null, false, "tester", "node-b", now, now, null, null, null
			).toStore(),
			null
		);

		assertThrows(
			ReindexTargetBusyException.class,
			() -> jobs.promoteThroughJob("catalogue@2")
		);
	}

	@Test
	public void aRecordLeftByADeadNodeIsResumed() throws Exception {
		catalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		jobs.start("catalogue@2", null, "manual", "tester");
		awaitPhase("catalogue", ReindexPhase.READY);

		// The node dies; a successor with the same storage picks the job up
		jobs.stop();
		jobs = newJobs(indexes, "node-b");
		jobs.onStart(null);

		await(() -> {
			try {
				return jobs.promoteThroughJob("catalogue@2");
			} catch(ReindexTargetBusyException e) {
				// Not resumed yet
				return false;
			}
		});

		awaitPhase("catalogue", ReindexPhase.DONE);
		assertThat(registry.get("catalogue").orElseThrow().live(), is("2"));

		// The record names the node that finished the job, not the one that died
		var done = jobs.get("catalogue").orElseThrow();
		assertThat(done.node(), is("node-b"));
		assertThat(done.startedBy(), is("tester"));
	}

	/**
	 * A node that gains an index with a job on it serves writes from the
	 * moment it claims the index, which is before anything resumes the job.
	 * Those writes have to be recorded, or they sit in the source alone and
	 * the promote drops them.
	 */
	@Test
	public void writesASuccessorTakesBeforeTheJobResumesAreCarriedOver() throws Exception {
		catalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		jobs.start("catalogue@2", null, "manual", "tester");
		awaitPhase("catalogue", ReindexPhase.READY);

		/*
		 * The node dies with the job left ready, and a successor opens the
		 * index over the same storage. A change of ownership on one node is
		 * not used here: Indexes reopens on its own for it, on another
		 * thread, and a write that meets that reopen is refused.
		 */
		jobs.stop();
		indexes.close();
		indexes = newIndexes();
		var source = indexes.getOrThrow("catalogue");

		// Served before anything picked the record up again
		source.addDocument(doc("4", "Rosehip soup", "soup"));
		source.deleteDocument("1");

		jobs = newJobs();
		jobs.onStart(null);

		await(() -> {
			try {
				return jobs.promoteThroughJob("catalogue@2");
			} catch(ReindexTargetBusyException e) {
				return false;
			}
		});

		awaitPhase("catalogue", ReindexPhase.DONE);

		var target = indexes.getOrThrow("catalogue@2");
		assertThat(target.getDocument("4"), is(notNullValue()));
		assertThat(target.getDocument("1"), is(nullValue()));
	}

	/**
	 * The target was filled from one generation, so it is only complete
	 * against that one. An operator who promotes a third generation while the
	 * job waits sends the writes there untracked, and promoting the target
	 * over it would take every one of them off the index name.
	 */
	@Test
	public void aPromoteIsRefusedWhenAnotherGenerationWentLive() throws Exception {
		catalogue();
		indexes.createGeneration("catalogue@2", definition().build());
		indexes.createGeneration("catalogue@3", definition().build());

		jobs.start("catalogue@2", null, "manual", "tester");
		awaitPhase("catalogue", ReindexPhase.READY);

		registry.promote("catalogue", "3");

		assertThrows(
			LiveGenerationMovedException.class,
			() -> jobs.promoteThroughJob("catalogue@2")
		);

		// The generation that was promoted stays live, and the job is closed
		assertThat(registry.get("catalogue").orElseThrow().live(), is("3"));
		awaitPhase("catalogue", ReindexPhase.FAILED);
	}

	@Test
	public void stoppingWhileCopyingLeavesTheIndexesCommittable() throws Exception {
		/*
		 * Enough documents that the copy is still reading when the stop lands
		 * in it - a job cut short mid-batch would take the writer of the
		 * generation it fills with it, and everything else the node holds
		 * would then be left open and uncommitted behind it.
		 */
		var source = indexes.create("catalogue", definition().build());
		for(var i = 0; i < 50_000; i++) {
			var key = String.format("%08d", i);
			source.addDocument(doc(key, "Jar " + key, "preserves"));
		}
		source.commit();

		indexes.createGeneration("catalogue@2", definition().build());

		jobs.start("catalogue@2", null, null, "tester");
		await(() -> jobs.get("catalogue")
			.map(job -> job.phase() == ReindexPhase.COPYING && job.documentsCopied() > 0)
			.orElse(false));

		jobs.stop();

		// Both writers came through the stop, so the node can close on them
		indexes.getOrThrow("catalogue").commit();
		indexes.getOrThrow("catalogue@2").commit();
		indexes.close();

		// The record is left as it stood, for whoever picks the index up next
		var job = jobs.get("catalogue").orElseThrow();
		assertThat(job.phase(), is(ReindexPhase.COPYING));
		assertThat(job.cursor(), is(notNullValue()));
	}

	@Test
	public void aDocumentTheTargetRefusesFailsTheJobBeforeAnyPromote() throws Exception {
		catalogue();

		/*
		 * The target reads `category` as a number, which the documents of the
		 * source do not hold - the job has to stop with the offending key
		 * rather than promote what it could index.
		 */
		indexes.createGeneration(
			"catalogue@2",
			definition()
				.putFields(
					"category",
					FieldDef.newBuilder()
						.setType(
							FieldTypeDef.newBuilder()
								.setDouble(DoubleFieldTypeDef.getDefaultInstance())
						)
						.build()
				)
				.build()
		);

		jobs.start("catalogue@2", null, null, "tester");
		awaitPhase("catalogue", ReindexPhase.FAILED);

		var job = jobs.get("catalogue").orElseThrow();
		assertThat(job.error(), is(notNullValue()));
		assertThat(job.error(), containsString("`1`"));
		assertThat(job.finishedAt(), is(notNullValue()));
		assertThat(registry.get("catalogue").orElseThrow().live(), is("1"));
	}

	/**
	 * The drain writes each changed document to the target in full. A
	 * document indexed again without a signal value holds none in the
	 * source, so the target must hold none either.
	 */
	@Test
	public void aSignalGoneFromADocumentIndexedAgainIsGoneFromTheTarget() throws Exception {
		var source = signalCatalogue();
		indexes.createGeneration("catalogue@2", signalDefinition().build());
		jobs.start("catalogue@2", null, "manual", "tester");
		awaitPhase("catalogue", ReindexPhase.READY);
		assertThat(indexes.getOrThrow("catalogue@2").getDocument("1").get("popularity"), is(0.9));

		source.deleteDocument("1");
		source.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Blueberry jam")
			)
		);

		assertTrue(jobs.promoteThroughJob("catalogue@2"));
		awaitPhase("catalogue", ReindexPhase.DONE);

		var target = indexes.getOrThrow("catalogue@2");
		assertThat(target.getDocument("1").get("popularity"), is(nullValue()));
		assertThat(target.getDocument("2").get("popularity"), is(0.2));
	}

	@Test
	public void aSignalEmptiedOnTheSourceIsEmptiedInTheTarget() throws Exception {
		var source = signalCatalogue();
		indexes.createGeneration("catalogue@2", signalDefinition().build());
		jobs.start("catalogue@2", null, "manual", "tester");
		awaitPhase("catalogue", ReindexPhase.READY);

		assertTrue(source.updateDocument(signalPatch("1", null)));

		assertTrue(jobs.promoteThroughJob("catalogue@2"));
		awaitPhase("catalogue", ReindexPhase.DONE);

		assertThat(indexes.getOrThrow("catalogue@2").getDocument("1").get("popularity"), is(nullValue()));
	}

	@Test
	public void aSignalChangedOnTheSourceReachesTheTarget() throws Exception {
		var source = signalCatalogue();
		indexes.createGeneration("catalogue@2", signalDefinition().build());
		jobs.start("catalogue@2", null, "manual", "tester");
		awaitPhase("catalogue", ReindexPhase.READY);

		assertTrue(source.updateDocument(signalPatch("1", 0.1)));

		assertTrue(jobs.promoteThroughJob("catalogue@2"));
		awaitPhase("catalogue", ReindexPhase.DONE);

		assertThat(indexes.getOrThrow("catalogue@2").getDocument("1").get("popularity"), is(0.1));
	}

	/**
	 * A field dropped from the definition of the source is not part of its
	 * documents anymore. A reindex into a generation without that field must
	 * copy those documents rather than fail on them.
	 */
	@Test
	public void aReindexCopiesDocumentsThatHeldAFieldTheSourceDropped() throws Exception {
		// Given
		var source = indexes.create(
			"catalogue",
			definition().putFields("extra", string().build()).build()
		);
		source.addDocument(
			new Document(
				new Document.Value("id", "d1"),
				new Document.Value("name", "Blueberry jam"),
				new Document.Value("category", "preserves"),
				new Document.Value("extra", "x")
			)
		);
		source.commit();
		source.updateDefinition(definition().build());
		indexes.createGeneration("catalogue@2", definition().build());

		// When
		jobs.start("catalogue@2", null, null, "tester");
		await(() -> jobs.get("catalogue")
			.map(job -> job.phase().isFinished())
			.orElse(false));

		// Then
		var job = jobs.get("catalogue").orElseThrow();
		assertThat(job.error(), is(nullValue()));
		assertThat(job.phase(), is(ReindexPhase.DONE));
		assertThat(
			indexes.getOrThrow("catalogue@2").getDocument("d1").get("name"),
			is("Blueberry jam")
		);
	}

	/**
	 * A job that fails stops recording the changes of the source. When the
	 * record cannot say that it failed, the job must not be resumed later from
	 * its last cursor: the changes since its start are then in neither the
	 * copy nor the log.
	 */
	@Test
	public void aJobWhoseFailureCannotBeRecordedDoesNotPromoteStaleDocuments()
		throws Exception {
		// Given
		var source = indexes.create("catalogue", definition().build());
		for(var i = 0; i < 1500; i++) {
			var key = String.format("k%04d", i);
			source.addDocument(doc(key, "Jar " + key, "preserves"));
		}
		source.commit();
		indexes.createGeneration("catalogue@2", definition().build());

		var faulty = new FaultyRecordStorage(storage);
		var failuresLeft = new AtomicInteger();
		var changed = new AtomicBoolean();
		faulty.refuse = job -> failuresLeft.get() > 0 && failuresLeft.getAndDecrement() > 0;
		faulty.afterWrite = job -> {
			if(job.cursor() != null && job.documentsCopied() == 1000 && !changed.getAndSet(true)) {
				try {
					// Changed after the first batch was copied, so only the log knows it
					source.addDocument(doc("k0001", "Updated jar", "preserves"));
				} catch(IOException e) {
					throw new UncheckedIOException(e);
				}

				// The next batch checkpoint and the failed record both fail
				failuresLeft.set(2);
			}
		};

		jobs.stop();
		jobs = newJobs(faulty, "node-a");

		// When
		jobs.start("catalogue@2", null, null, "tester");
		await(() -> faulty.refusedWrites() == 2);
		jobs.stop();

		// The storage recovers, and the node picks up what the records say
		jobs = newJobs(faulty, "node-a");
		jobs.onStart(null);
		await(() -> jobs.get("catalogue")
			.map(job -> job.phase().isFinished())
			.orElse(false));

		// Then
		var live = indexes.getOrThrow("catalogue");
		assertThat(live.getDocument("k0001").get("name"), is("Updated jar"));
	}

	/**
	 * A cancel whose record cannot be written did not take effect: the record
	 * still says that the job runs, and a later resume goes on with it. The
	 * caller is told so, and the source keeps the log that the resumed job
	 * replays the changes from.
	 */
	@Test
	public void aCancelWhoseRecordCannotBeWrittenKeepsTheChangeLog() throws Exception {
		// Given
		var source = catalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		var faulty = new FaultyRecordStorage(storage);
		faulty.refuse = job -> job.phase() == ReindexPhase.CANCELLED;

		var copying = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		faulty.afterWrite = job -> {
			if(job.phase() == ReindexPhase.COPYING && job.cursor() == null) {
				copying.countDown();
				try {
					release.await();
				} catch(InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		};

		jobs.stop();
		jobs = newJobs(faulty, "node-a");

		jobs.start("catalogue@2", null, null, "tester");
		assertTrue(copying.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));

		// When
		var refused = new AtomicBoolean();
		var cancelling = new Thread(() -> {
			try {
				jobs.cancel("catalogue");
			} catch(ReindexStorageException e) {
				refused.set(true);
			}
		});
		cancelling.start();

		// The cancel has raised its flag once it waits for the job to stop
		await(() -> cancelling.getState() == Thread.State.TIMED_WAITING);
		release.countDown();
		cancelling.join(WAIT.toMillis());

		// Then
		assertThat("the cancel answered an error", refused.get(), is(true));
		assertThat(registry.get("catalogue").orElseThrow().live(), is("1"));
		assertThat(
			jobs.get("catalogue").orElseThrow().phase(),
			is(not(ReindexPhase.CANCELLED))
		);
		assertThat("the source keeps its change log", source.getChangeLog().isPresent(), is(true));

		// And: a document changed now is in the target that the resumed job promotes
		source.addDocument(doc("1", "Updated jam", "preserves"));

		jobs.stop();
		jobs = newJobs(faulty, "node-a");
		jobs.onStart(null);
		await(() -> jobs.get("catalogue")
			.map(job -> job.phase().isFinished())
			.orElse(false));

		var live = indexes.getOrThrow("catalogue");
		assertThat(live.getDocument("1").get("name"), is("Updated jam"));
	}

	/**
	 * A cancel that reaches a node which carries no job for the index acts on
	 * the record. When the claim the request made resumes the job at the same
	 * moment, the cancel must not take the change log away from the resumed
	 * job and leave it to promote without it.
	 */
	@Test
	public void aCancelThatMeetsAResumeDoesNotLeaveTheJobWithoutItsLog() throws Exception {
		// Given
		var source = largeCatalogue();
		var target = indexes.createGeneration("catalogue@2", definition().build());
		for(var i = 0; i < 1000; i++) {
			var key = String.format("k%04d", i);
			target.addDocument(doc(key, "Jar " + key, "preserves"));
		}
		target.commit();
		writeCopyingRecord();

		// The node that ran the job tracked the source, and died after this change
		source.beginChangeTracking();
		source.addDocument(doc("k0001", "Updated jar", "preserves"));
		source.commit();

		var faulty = new FaultyRecordStorage(storage);
		var successor = newJobs(faulty, "node-a");
		var resumed = new AtomicBoolean();
		faulty.refuse = job -> {
			if(job.phase() == ReindexPhase.CANCELLED && !resumed.getAndSet(true)) {
				// The claim the cancel request made resumes the job meanwhile
				resumeAndAwaitCheckpoint(successor);
			}
			return false;
		};

		jobs.stop();
		jobs = successor;

		// When
		try {
			jobs.cancel("catalogue");
		} catch(ReindexStorageException e) {
			// Saying that the cancel was not recorded is a valid answer
		}
		awaitFinished("catalogue");

		// Then
		var live = indexes.getOrThrow("catalogue");
		assertThat(live.getDocument("k0001").get("name"), is("Updated jar"));
	}

	private void resumeAndAwaitCheckpoint(ReindexJobs successor) {
		try {
			var before = storage.read("catalogue").orElseThrow().version();
			successor.onStart(null);
			await(() -> {
				try {
					return !storage.read("catalogue").orElseThrow().version().equals(before);
				} catch(IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} catch(Exception e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * A job record belongs to the generations it was started on. An index
	 * deleted and created again under the same name holds other generations,
	 * and a resume must not carry the old job on into them from its old
	 * cursor.
	 */
	@Test
	public void aJobOfADeletedIndexIsNotResumedIntoTheIndexCreatedAgain() throws Exception {
		// Given
		largeCatalogue();
		indexes.createGeneration("catalogue@2", definition().build());
		writeCopyingRecord();
		indexes.delete("catalogue");
		largeCatalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		// When
		jobs.onStart(null);
		awaitFinishedOrRemoved("catalogue");

		// Then
		assertThat(indexes.getOrThrow("catalogue").getDocumentCount(), is(1500L));
		assertThat(indexes.getOrThrow("catalogue").getDocument("k0000"), is(notNullValue()));
	}

	/**
	 * A target generation deleted and created again starts empty, so a job
	 * that copied part of the old one must not go on from its old cursor and
	 * promote the new one without the keys it copied before.
	 */
	@Test
	public void aJobIsNotResumedIntoATargetGenerationCreatedAgain() throws Exception {
		// Given
		largeCatalogue();
		indexes.createGeneration("catalogue@2", definition().build());
		writeCopyingRecord();

		indexes.delete("catalogue@2");
		indexes.createGeneration("catalogue@2", definition().build());

		// When
		jobs.onStart(null);
		awaitFinished("catalogue");

		// Then
		assertThat(indexes.getOrThrow("catalogue").getDocumentCount(), is(1500L));
		assertThat(indexes.getOrThrow("catalogue").getDocument("k0000"), is(notNullValue()));
	}

	/**
	 * A job belongs to its index. Deleting the index takes the record with
	 * it, so that nothing resumes it and a reindex of an index created again
	 * under the name is not refused.
	 */
	@Test
	public void aDeleteOfTheIndexRemovesTheRecordOfItsJob() throws Exception {
		// Given a job that no node carries
		largeCatalogue();
		indexes.createGeneration("catalogue@2", definition().build());
		writeCopyingRecord();

		// When the index is deleted
		indexes.delete("catalogue");

		// Then the job has no record
		assertThat(jobs.get("catalogue").isPresent(), is(false));
	}

	/**
	 * A job whose target is deleted has nothing left to fill, so its record
	 * says that it failed.
	 */
	@Test
	public void aDeleteOfTheTargetGenerationFailsItsJob() throws Exception {
		// Given a job that no node carries
		largeCatalogue();
		indexes.createGeneration("catalogue@2", definition().build());
		writeCopyingRecord();

		// When the target generation is deleted
		indexes.delete("catalogue@2");

		// Then the job is failed
		assertThat(jobs.get("catalogue").orElseThrow().phase(), is(ReindexPhase.FAILED));
	}

	/**
	 * A record that an earlier version wrote does not say when its
	 * generations were created, so a resume cannot tell a generation created
	 * again from the one the job read. The delete of the index takes the
	 * record, so it is not resumed into the index created again.
	 */
	@Test
	public void aJobOfAnEarlierVersionIsNotResumedIntoTheIndexCreatedAgain() throws Exception {
		// Given a job of an earlier version that no node carries
		largeCatalogue();
		indexes.createGeneration("catalogue@2", definition().build());
		writeCopyingRecord(null, null);

		// And the index deleted and created again
		indexes.delete("catalogue");
		largeCatalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		// When the jobs are resumed and the generation is reindexed
		jobs.onStart(null);
		jobs.start("catalogue@2", null, null, "tester");
		awaitFinished("catalogue");

		// Then the promoted generation holds every document
		assertThat(indexes.getOrThrow("catalogue").getDocumentCount(), is(1500L));
		assertThat(indexes.getOrThrow("catalogue").getDocument("k0000"), is(notNullValue()));
	}

	/**
	 * A generation deleted and created again where this node did not see the
	 * delete leaves the record of the old job. A new reindex of the generation
	 * replaces that record rather than being refused for it.
	 */
	@Test
	public void aJobOfATargetGenerationCreatedAgainDoesNotRefuseANewReindex() throws Exception {
		// Given a job that no node carries
		largeCatalogue();
		indexes.createGeneration("catalogue@2", definition().build());
		writeCopyingRecord();

		// And the target generation deleted on another node and created again
		Thread.sleep(2);
		registry.removeGeneration("catalogue", "2");
		indexes.createGeneration("catalogue@2", definition().build());

		// When the generation is reindexed
		jobs.start("catalogue@2", null, null, "tester");
		awaitFinished("catalogue");

		// Then the promoted generation holds every document
		assertThat(indexes.getOrThrow("catalogue").getDocumentCount(), is(1500L));
	}

	/**
	 * A node that caps how many indexes it holds open closes the least used
	 * ones. A job uses its generations through instances of its own, so the
	 * cache sees no use of them during a long copy. The job holds them in the
	 * cache, so the cap does not close them under it.
	 */
	@Test
	public void aJobFinishesWhenOtherIndexesFillTheCacheDuringTheCopy() throws Exception {
		// Given a node that holds one index open and closes what it evicts at once
		jobs.stop();
		indexes.close();
		indexes = newIndexes(OptionalInt.of(1), Duration.ZERO);
		var faulty = new FaultyRecordStorage(storage);
		jobs = newJobs(faulty, "node-a");

		largeCatalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		// And other indexes opened after the first batch of the copy
		var filled = new AtomicBoolean();
		faulty.afterWrite = job -> {
			if(job.phase() == ReindexPhase.COPYING
				&& job.cursor() != null
				&& !filled.getAndSet(true)) {
				fillCache();
			}
		};

		// When
		jobs.start("catalogue@2", null, null, "tester");
		awaitFinished("catalogue");

		// Then
		var job = jobs.get("catalogue").orElseThrow();
		assertThat("job ended as " + job.phase() + ": " + job.error(), job.phase(), is(ReindexPhase.DONE));
		assertThat(indexes.getOrThrow("catalogue").getDocumentCount(), is(1500L));
	}

	/**
	 * Open other indexes, and wait until the cache closes the source of the
	 * job or a time has passed.
	 */
	private void fillCache() {
		try {
			for(var i = 0; i < 5; i++) {
				var other = "other" + i;
				indexes.create(other, definition().build());
				for(var touch = 0; touch < 5; touch++) {
					indexes.getOrThrow(other);
				}
			}

			var deadline = System.currentTimeMillis() + 2000;
			while(System.currentTimeMillis() < deadline
				&& indexes.getOpen().containsKey("catalogue@1")) {
				Thread.sleep(10);
			}
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private Index largeCatalogue() throws IOException {
		var index = indexes.create("catalogue", definition().build());
		for(var i = 0; i < 1500; i++) {
			var key = String.format("k%04d", i);
			index.addDocument(doc(key, "Jar " + key, "preserves"));
		}
		index.commit();
		return index;
	}

	/**
	 * Write the record a node leaves when it stops after the first batch of
	 * a copy from generation 1 into generation 2, naming the generations as
	 * the registry holds them now.
	 */
	private void writeCopyingRecord() throws IOException {
		var registered = registry.get("catalogue").orElseThrow();
		writeCopyingRecord(
			registered.generation("1").orElseThrow().createdAt(),
			registered.generation("2").orElseThrow().createdAt()
		);
	}

	/**
	 * Write the record a node leaves when it stops after the first batch of
	 * a copy from generation 1 into generation 2.
	 *
	 * @param sourceCreatedAt
	 *   when the source was created, or {@code null} for a record of an
	 *   earlier version that does not say
	 * @param targetCreatedAt
	 *   when the target was created, or {@code null}
	 */
	private void writeCopyingRecord(
		Instant sourceCreatedAt,
		Instant targetCreatedAt
	) throws IOException {
		var now = Instant.now();
		storage.write(
			"catalogue",
			new ReindexJob(
				"job-1", "catalogue", "2", "1",
				ReindexPhase.COPYING,
				"k0999", 1000, 1500, 0, null, false, "tester", "node-b", now, now, null,
				sourceCreatedAt,
				targetCreatedAt
			).toStore(),
			null
		);
	}

	private void awaitFinished(String index) throws Exception {
		await(() -> jobs.get(index)
			.map(job -> job.phase().isFinished())
			.orElse(false));
	}

	/**
	 * Wait until the job of an index is finished, or its record is gone, as
	 * it is after a delete of the index.
	 */
	private void awaitFinishedOrRemoved(String index) throws Exception {
		await(() -> jobs.get(index)
			.map(job -> job.phase().isFinished())
			.orElse(true));
	}

	private static Document doc(String id, String name, String category) {
		return new Document(
			new Document.Value("id", id),
			new Document.Value("name", name),
			new Document.Value("category", category)
		);
	}

	private Index catalogue() throws IOException {
		var index = indexes.create("catalogue", definition().build());

		index.addDocument(doc("1", "Blueberry jam", "preserves"));
		index.addDocument(doc("2", "Rye bread", "bread"));
		index.addDocument(doc("3", "Lingonberry jam", "preserves"));
		index.commit();

		return index;
	}

	private static IndexDef.Builder definition() {
		return IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build())
			.putFields("name", string().build())
			.putFields("category", string().build());
	}

	private static DocumentPatch signalPatch(String id, Double popularity) {
		var values = Lists.mutable.of(new Document.Value("id", id));
		if(popularity != null) {
			values.add(new Document.Value("popularity", popularity));
		}

		return DocumentPatch.replacing(Sets.immutable.of("id", "popularity"), values.toImmutable());
	}

	private Index signalCatalogue() throws IOException {
		var index = indexes.create("catalogue", signalDefinition().build());

		index.addDocument(
			new Document(
				new Document.Value("id", "1"),
				new Document.Value("name", "Blueberry jam"),
				new Document.Value("popularity", 0.9)
			)
		);
		index.addDocument(
			new Document(
				new Document.Value("id", "2"),
				new Document.Value("name", "Rye bread"),
				new Document.Value("popularity", 0.2)
			)
		);
		index.commit();

		return index;
	}

	private static IndexDef.Builder signalDefinition() {
		return IndexDef.newBuilder()
			.putFields("id", string().setPrimaryKey(true).build())
			.putFields("name", string().build())
			.putFields(
				"popularity",
				FieldDef.newBuilder()
					.setType(
						FieldTypeDef.newBuilder()
							.setDouble(DoubleFieldTypeDef.getDefaultInstance())
					)
					.setSignal(SignalConfig.getDefaultInstance())
					.build()
			);
	}

	private static FieldDef.Builder string() {
		return FieldDef.newBuilder()
			.setType(
				FieldTypeDef.newBuilder()
					.setString(StringFieldTypeDef.getDefaultInstance())
			);
	}

	private void awaitPhase(String index, ReindexPhase phase) throws Exception {
		await(() -> jobs.get(index)
			.map(job -> job.phase() == phase)
			.orElse(false));

		assertThat(jobs.get(index).orElseThrow().phase(), is(phase));
	}

	private static void await(BooleanSupplier condition) throws Exception {
		var deadline = System.nanoTime() + WAIT.toNanos();
		while(!condition.getAsBoolean()) {
			if(System.nanoTime() > deadline) {
				throw new AssertionError("Condition did not hold within " + WAIT);
			}

			Thread.sleep(20);
		}
	}

	private ReindexJobs newJobs(ReindexJobStorage storage, String node) {
		var ownership = new LocalIndexerOwnership();
		ownership.start((index, owner) -> {
		});

		return new ReindexJobs(
			nodeState,
			new NodeIdentity(node),
			indexes,
			registry,
			storage,
			ownership,
			2,
			Duration.ofMinutes(5),
			Duration.ofMinutes(5)
		);
	}

	/**
	 * Record storage that a test can make refuse writes, the way a storage
	 * outage or a full disk does, and that can run a step after a write
	 * landed.
	 */
	private static final class FaultyRecordStorage implements ReindexJobStorage {
		private final ReindexJobStorage delegate;
		private final AtomicInteger refused;

		private volatile Predicate<ReindexJob> refuse;
		private volatile Consumer<ReindexJob> afterWrite;

		FaultyRecordStorage(ReindexJobStorage delegate) {
			this.delegate = delegate;
			this.refused = new AtomicInteger();
			this.refuse = job -> false;
			this.afterWrite = job -> {
			};
		}

		int refusedWrites() {
			return refused.get();
		}

		@Override
		public Optional<Stored> read(String index) throws IOException {
			return delegate.read(index);
		}

		@Override
		public String write(String index, ReindexJobStore record, String expectedVersion)
			throws IOException {
			var job = ReindexJob.fromStore(record).orElse(null);
			if(job != null && refuse.test(job)) {
				refused.incrementAndGet();
				throw new IOException("The record storage is unreachable");
			}

			var version = delegate.write(index, record, expectedVersion);
			if(job != null && version != null) {
				afterWrite.accept(job);
			}

			return version;
		}

		@Override
		public void delete(String index) throws IOException {
			delegate.delete(index);
		}

		@Override
		public ListIterable<Stored> list() throws IOException {
			return delegate.list();
		}

		@Override
		public ListIterable<Stored> listUnfinished() throws IOException {
			return delegate.listUnfinished();
		}
	}
}
