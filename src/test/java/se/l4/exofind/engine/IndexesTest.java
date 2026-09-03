package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.errors.ValidationException;
import se.l4.exofind.engine.index.Index;
import se.l4.exofind.engine.index.IndexClosedException;
import se.l4.exofind.engine.index.IndexName;
import se.l4.exofind.engine.index.IndexNotFoundException;
import se.l4.exofind.engine.index.IndexState;
import se.l4.exofind.engine.index.IndexStorageHeldException;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.state.IndexRemovals;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.storage.StorageMode;

public class IndexesTest {
	@TempDir
	Path storageDirectory;

	Indexes indexes;

	@BeforeEach
	void setup() throws IOException {
		indexes = newNode(storageDirectory, registry(), OptionalInt.empty());
	}

	@AfterEach
	void cleanup() {
		indexes.close();
	}

	/**
	 * Node state as it looks once ownership has settled: a candidate that has
	 * been granted the indexer role, or a node that never competes for it.
	 */
	private static NodeState nodeState(boolean indexer) {
		var state = new NodeState(indexer);
		state.updateOwnership(indexer);
		return state;
	}

	/**
	 * A registry over the shared file. Two of these stand for two nodes reading
	 * and writing the same registry, which is how they learn about each other's
	 * indexes.
	 */
	private IndexRegistry registry() {
		return new IndexRegistry(
			new LocalRegistryStorage(storageDirectory.resolve("registry.ef.bin")),
			Duration.ofMinutes(5)
		);
	}

	private static Indexes newNode(Path directory, IndexRegistry registry, OptionalInt maxOpen)
		throws IOException {
		return newNode(directory, registry, maxOpen, new RecordingIndexRemovals());
	}

	private static Indexes newNode(
		Path directory,
		IndexRegistry registry,
		OptionalInt maxOpen,
		IndexRemovals removals
	)
		throws IOException {
		return new Indexes(
			nodeState(true),
			new NoopSyncProvider(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			removals,
			directory,
			maxOpen,
			Duration.ofMinutes(5),
			Duration.ofMinutes(10),
			4,
			Duration.ofMillis(100),
			0,
			Duration.ZERO,
			Optional.empty(),
			Optional.empty(),
			Duration.ofHours(24),
			Duration.ofHours(168),
			Duration.ofHours(1)
		);
	}

	/**
	 * An index is created with a first generation, and answering for it is what
	 * its name does - the generation is not something a caller has to know
	 * about to use the index.
	 */
	@Test
	public void testCreateOpensFirstGeneration() {
		var created = indexes.create("books", IndexDef.getDefaultInstance());

		assertThat(created.getId(), is("books@1"));
		assertThat(indexes.getIndexNames(), contains("books"));
		assertThat(indexes.getOrThrow("books"), is(sameInstance(created)));
		assertThat(indexes.getOrThrow("books@1"), is(sameInstance(created)));
	}

	/**
	 * Creating an index names the index. Which generation comes first is the
	 * engine's to decide, so a name that already picks one is refused rather
	 * than taken as a request for it.
	 */
	@Test
	public void testCreateRefusesAGenerationName() {
		assertThrows(
			ValidationException.class,
			() -> indexes.create("books@1", IndexDef.getDefaultInstance())
		);
	}

	/**
	 * A node only keeps copies of the indexes it has been asked for, so one
	 * created on another node is found by reading the registry.
	 *
	 */
	@Test
	public void testIndexCreatedElsewhereIsFound() throws IOException {
		var other = newNode(storageDirectory.resolve("other"), registry(), OptionalInt.empty());
		try {
			assertThat(other.getIndexNames(), emptyIterable());

			indexes.create("books", IndexDef.getDefaultInstance());
			other.refresh();

			assertThat(other.getIndexNames(), contains("books"));
			assertThat(other.getOrThrow("books").getId(), is("books@1"));
		} finally {
			other.close();
		}
	}

	/**
	 * The registry is one object read at one version, so a name being absent
	 * from it is an answer rather than a gap - a node removes its copy of an
	 * index another node deleted the first time it reads that it is gone.
	 */
	@Test
	public void testIndexDeletedElsewhereIsRemoved() throws IOException {
		var other = newNode(storageDirectory.resolve("other"), registry(), OptionalInt.empty());
		try {
			indexes.create("books", IndexDef.getDefaultInstance());

			other.refresh();
			other.getOrThrow("books");

			var dataPath = storageDirectory.resolve("other")
				.resolve("indexes")
				.resolve("books@1");
			assertThat(Files.isDirectory(dataPath), is(true));

			indexes.delete("books");
			other.refresh();

			assertThat(other.getIndexNames(), emptyIterable());
			assertThat(Files.exists(dataPath), is(false));
		} finally {
			other.close();
		}
	}

	/**
	 * A local copy of something the registry still holds is left alone, however
	 * many refreshes go by.
	 */
	@Test
	public void testRegisteredIndexSurvivesRefreshes() {
		indexes.create("books", IndexDef.getDefaultInstance());

		for(var i = 0; i < 4; i++) {
			indexes.refresh();
		}

		assertThat(indexes.getIndexNames(), contains("books"));
	}

	/**
	 * A generation is added without the index moving to it, which is what lets
	 * one be filled with documents while the index goes on answering searches
	 * from the generation it already had.
	 */
	@Test
	public void testAddedGenerationDoesNotChangeWhatTheNameAnswers() {
		var first = indexes.create("books", IndexDef.getDefaultInstance());
		var second = indexes.createGeneration("books@2", IndexDef.getDefaultInstance());

		assertThat(second.getId(), is("books@2"));
		assertThat(indexes.getOrThrow("books"), is(sameInstance(first)));
		assertThat(indexes.getOrThrow("books@2"), is(sameInstance(second)));
	}

	/**
	 * Promoting is the whole of a rollout: the name answers from the promoted
	 * generation from then on, and nothing a caller holds changes.
	 */
	@Test
	public void testPromotedGenerationAnswersForTheName() {
		indexes.create("books", IndexDef.getDefaultInstance());
		var second = indexes.createGeneration("books@2", IndexDef.getDefaultInstance());

		indexes.promote("books@2");

		assertThat(indexes.getOrThrow("books"), is(sameInstance(second)));
		assertThat(indexes.getRegistered("books").orElseThrow().live(), is("2"));
	}

	/**
	 * Promoting names a generation, as an index is never promoted to itself.
	 */
	@Test
	public void testPromoteRefusesAnIndexName() {
		indexes.create("books", IndexDef.getDefaultInstance());

		assertThrows(ValidationException.class, () -> indexes.promote("books"));
	}

	/**
	 * A generation an index does not have is no more findable than an index
	 * that does not exist, so the two are answered the same way.
	 */
	@Test
	public void testUnknownGenerationIsNotFound() {
		indexes.create("books", IndexDef.getDefaultInstance());

		assertThat(indexes.get("books@7"), is(Optional.empty()));
		assertThrows(IndexNotFoundException.class, () -> indexes.getOrThrow("books@7"));
	}

	/**
	 * Removing the generation an index answers from would leave the index
	 * answering for nothing, so it is refused until another one is promoted.
	 */
	@Test
	public void testLiveGenerationCannotBeDeleted() throws IOException {
		indexes.create("books", IndexDef.getDefaultInstance());
		indexes.createGeneration("books@2", IndexDef.getDefaultInstance());

		assertThrows(ValidationException.class, () -> indexes.delete("books@1"));

		indexes.promote("books@2");
		indexes.delete("books@1");

		assertThat(indexes.get("books@1"), is(Optional.empty()));
		assertThat(indexes.getOrThrow("books").getId(), is("books@2"));
	}

	/**
	 * Deleting the index takes every generation with it, so nothing is left
	 * standing under a name the deployment no longer holds.
	 */
	@Test
	public void testDeletingIndexRemovesEveryGeneration() throws IOException {
		indexes.create("books", IndexDef.getDefaultInstance());
		indexes.createGeneration("books@2", IndexDef.getDefaultInstance());

		indexes.delete("books");

		assertThat(indexes.getIndexNames(), emptyIterable());
		assertThat(
			Files.exists(storageDirectory.resolve("indexes").resolve("books@1")),
			is(false)
		);
		assertThat(
			Files.exists(storageDirectory.resolve("indexes").resolve("books@2")),
			is(false)
		);
	}

	/**
	 * A delete takes the name out of the registry and marks what the shared
	 * storage holds under it, so a sweep can remove it later - the whole
	 * index for an index, one generation for a generation.
	 */
	@Test
	public void testDeletingMarksTheStorage() throws IOException {
		var removals = new RecordingIndexRemovals();
		var node = newNode(storageDirectory.resolve("marking"), registry(), OptionalInt.empty(), removals);
		try {
			node.create("books", IndexDef.getDefaultInstance());
			node.createGeneration("books@2", IndexDef.getDefaultInstance());

			node.delete("books@2");
			assertThat(removals.marks.keySet(), contains(IndexName.of("books", "2")));

			node.delete("books");
			assertThat(
				removals.marks.keySet(),
				contains(IndexName.of("books", "2"), IndexName.of("books"))
			);
		} finally {
			node.close();
		}
	}

	/**
	 * The registry write is what deletes; a mark that could not be written
	 * is logged and costs the caller nothing.
	 */
	@Test
	public void testDeleteSucceedsWhenTheMarkCanNotBeWritten() throws IOException {
		var removals = new RecordingIndexRemovals();
		removals.failMark = true;

		var node = newNode(storageDirectory.resolve("failing"), registry(), OptionalInt.empty(), removals);
		try {
			node.create("books", IndexDef.getDefaultInstance());
			node.delete("books");

			assertThat(node.getIndexNames(), emptyIterable());
		} finally {
			node.close();
		}
	}

	/**
	 * A name created again lands on the prefix its delete marked, so
	 * creating clears the index's prefix and then the first generation's
	 * before the generation is opened - opening pulls, and a pull of what
	 * the delete left would fill the new index with the old documents.
	 */
	@Test
	public void testCreatingClearsWhatADeleteLeft() throws IOException {
		var removals = new RecordingIndexRemovals();
		removals.marks.put(IndexName.of("books"), Instant.now());

		var node = newNode(storageDirectory.resolve("clearing"), registry(), OptionalInt.empty(), removals);
		try {
			node.create("books", IndexDef.getDefaultInstance());

			assertThat(removals.preparedIndexes, contains("books"));
			assertThat(removals.preparedGenerations, contains(IndexName.of("books", "1")));
			assertThat(removals.marks.isEmpty(), is(true));

			// Adding a generation touches only its own prefix
			node.createGeneration("books@2", IndexDef.getDefaultInstance());

			assertThat(removals.preparedIndexes, contains("books"));
			assertThat(
				removals.preparedGenerations,
				contains(IndexName.of("books", "1"), IndexName.of("books", "2"))
			);
		} finally {
			node.close();
		}
	}

	/**
	 * Storage that holds a generation nothing deleted refuses the creation,
	 * which then leaves no registration behind - the same request succeeds
	 * once the storage has been repaired or cleared.
	 */
	@Test
	public void testHeldStorageRefusesTheCreationAndRollsBack() throws IOException {
		var removals = new RecordingIndexRemovals();
		removals.held.add(IndexName.of("books", "1"));

		var node = newNode(storageDirectory.resolve("held"), registry(), OptionalInt.empty(), removals);
		try {
			assertThrows(
				IndexStorageHeldException.class,
				() -> node.create("books", IndexDef.getDefaultInstance())
			);

			assertThat(node.getIndexNames(), emptyIterable());

			removals.held.clear();
			assertThat(node.create("books", IndexDef.getDefaultInstance()).getId(), is("books@1"));
		} finally {
			node.close();
		}
	}

	/**
	 * With a cap on how many indexes stay open, opening one index too many
	 * retires another. The retired instance is closed in the background,
	 * refuses use after that, and asking for the index again opens a fresh
	 * instance from the files the close left in place.
	 */
	@Test
	public void testEvictedIndexIsClosedAndCanBeOpenedAgain() throws Exception {
		var capped = newNode(
			storageDirectory.resolve("capped"),
			registry(),
			OptionalInt.of(1)
		);

		try {
			var a = capped.create("a", IndexDef.getDefaultInstance());
			var b = capped.create("b", IndexDef.getDefaultInstance());

			var closed = awaitClosed(a, b);

			assertThrows(IndexClosedException.class, () -> closed.getDocument("1"));

			/*
			 * The files survive the close, so opening the index again only
			 * needs a fresh instance rather than the index being rebuilt.
			 */
			var dataPath = storageDirectory.resolve("capped")
				.resolve("indexes")
				.resolve(closed.getId());
			assertThat(Files.exists(dataPath.resolve(Index.DEFINITION_FILE)), is(true));

			var reopened = capped.getOrThrow(closed.getId());
			assertThat(reopened, is(not(sameInstance(closed))));
			assertThat(reopened.getState(), is(IndexState.USABLE));
		} finally {
			capped.close();
		}
	}

	/**
	 * Wait for the eviction to close one of the given instances, answering
	 * with the one it picked.
	 */
	private static Index awaitClosed(Index... candidates) throws InterruptedException {
		var deadline = System.currentTimeMillis() + 5000;
		while(System.currentTimeMillis() < deadline) {
			for(var index : candidates) {
				if(index.getState() == IndexState.CLOSED) {
					return index;
				}
			}

			Thread.sleep(10);
		}

		throw new AssertionError("No index instance was closed within the wait");
	}
}
