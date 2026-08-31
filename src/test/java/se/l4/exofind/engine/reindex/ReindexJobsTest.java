package se.l4.exofind.engine.reindex;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.Document;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexSourceNotKeptException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.DoubleFieldTypeDef;
import se.l4.exofind.engine.index.schema.FieldDef;
import se.l4.exofind.engine.index.schema.FieldTypeDef;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.schema.Int64FieldTypeDef;
import se.l4.exofind.engine.index.schema.StringFieldTypeDef;
import se.l4.exofind.engine.index.state.LocalIndexerOwnership;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * Tests for filling a generation by reindexing from another one - the copy,
 * the drain at the promote, who promotes, what is refused up front, and what
 * a record left by a dead node resumes into.
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

		indexes = new Indexes(
			nodeState,
			new NoopSyncProvider(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			storageDirectory,
			OptionalInt.empty(),
			Duration.ofMinutes(5),
			Duration.ofMinutes(10),
			4,
			Duration.ofSeconds(10),
			0,
			Duration.ZERO,
			Optional.empty(),
			Optional.empty(),
			Duration.ofHours(24),
			Duration.ofHours(168),
			Duration.ofHours(1)
		);

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

	private ReindexJobs newJobs() {
		var ownership = new LocalIndexerOwnership();
		ownership.start((index, owner) -> {
		});

		return new ReindexJobs(
			nodeState,
			indexes,
			registry,
			storage,
			ownership,
			2,
			Duration.ofMinutes(5),
			Duration.ofMinutes(5),
			Duration.ZERO
		);
	}

	@Test
	public void aReindexCopiesEverythingAndPromotes() throws Exception {
		var source = catalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		var accepted = jobs.start("catalogue@2", null, null);
		assertThat(accepted.phase(), is(ReindexPhase.PENDING));

		awaitPhase("catalogue", ReindexPhase.DONE);

		assertThat(registry.get("catalogue").orElseThrow().live(), is("2"));

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

		jobs.start("catalogue@2", null, "manual");
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

	@Test
	public void theRecordSaysHowFarTheCopyCame() throws Exception {
		catalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		jobs.start("catalogue@2", null, null);
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
			() -> jobs.start("catalogue@2", null, null)
		);
	}

	@Test
	public void theLiveGenerationIsRefusedAsTarget() throws Exception {
		catalogue();

		assertThrows(
			ValidationException.class,
			() -> jobs.start("catalogue@1", null, null)
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
			() -> jobs.start("logs@2", null, null)
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
			() -> jobs.start("catalogue@2", null, null)
		);
	}

	@Test
	public void aSecondJobForTheIndexIsRefused() throws Exception {
		catalogue();
		indexes.createGeneration("catalogue@2", definition().build());
		indexes.createGeneration("catalogue@3", definition().build());

		jobs.start("catalogue@2", null, "manual");
		awaitPhase("catalogue", ReindexPhase.READY);

		assertThrows(
			ReindexInProgressException.class,
			() -> jobs.start("catalogue@3", null, null)
		);
	}

	@Test
	public void cancellingEndsTrackingAndLeavesThePartialTarget() throws Exception {
		var source = catalogue();
		indexes.createGeneration("catalogue@2", definition().build());
		indexes.createGeneration("catalogue@3", definition().build());

		jobs.start("catalogue@2", null, "manual");
		awaitPhase("catalogue", ReindexPhase.READY);

		var cancelled = jobs.cancel("catalogue");

		assertThat(cancelled.phase(), is(ReindexPhase.CANCELLED));
		assertThat(registry.get("catalogue").orElseThrow().live(), is("1"));
		assertTrue(source.getChangeLog().isEmpty());

		// A finished job frees the index for the next one
		jobs.start("catalogue@3", null, "manual");
	}

	@Test
	public void writesToTheTargetAreRefusedWhileTheJobFills() throws Exception {
		catalogue();
		indexes.createGeneration("catalogue@2", definition().build());

		jobs.start("catalogue@2", null, "manual");
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
				"catalogue", "2", "1",
				ReindexPhase.COPYING,
				null, 0, 3, 0, null, false, now, now
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

		jobs.start("catalogue@2", null, "manual");
		awaitPhase("catalogue", ReindexPhase.READY);

		// The node dies; a successor with the same storage picks the job up
		jobs.stop();
		jobs = newJobs();
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

		jobs.start("catalogue@2", null, null);
		awaitPhase("catalogue", ReindexPhase.FAILED);

		var job = jobs.get("catalogue").orElseThrow();
		assertThat(job.error(), is(notNullValue()));
		assertThat(job.error(), containsString("`1`"));
		assertThat(registry.get("catalogue").orElseThrow().live(), is("1"));
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
}
