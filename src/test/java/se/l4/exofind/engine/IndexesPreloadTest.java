package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.SearchThreads;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * What a node opens when it is given an index to write. A node writes every
 * index it holds, so it copies the generation and opens its writer at once,
 * within the limits that stop a node holding many indexes from keeping a
 * writer for each of them.
 */
public class IndexesPreloadTest {
	/**
	 * How long a wait for the open to land gives up after. Generously above
	 * what opening an empty index takes, so a slow machine does not read as
	 * an open that never happened.
	 */
	private static final Duration OPEN_WAIT = Duration.ofSeconds(10);

	/**
	 * How long an index that should stay closed is given to prove it. The
	 * node decides on the refresh thread as soon as it is given the index,
	 * so this only has to cover the hand-off between the threads.
	 */
	private static final Duration SETTLE = Duration.ofMillis(500);

	@TempDir
	Path storageDirectory;

	private final List<Indexes> nodes = new ArrayList<>();

	@AfterEach
	void cleanup() {
		for(var node : nodes) {
			node.close();
		}
	}

	/**
	 * A registry over the shared file. Two of these stand for two nodes
	 * reading and writing the same registry.
	 */
	private IndexRegistry registry() {
		return new IndexRegistry(
			new LocalRegistryStorage(storageDirectory.resolve("registry.ef.bin")),
			Duration.ofMinutes(5)
		);
	}

	/**
	 * The node the indexes are created on. It holds every index at once, so
	 * it is never given one index at a time.
	 */
	private Indexes writer() throws IOException {
		var state = new NodeState(true);
		state.updateOwnership(true);

		return node(state, storageDirectory.resolve("writer"), OptionalInt.empty(), 16);
	}

	private Indexes node(
		NodeState state,
		Path directory,
		OptionalInt maxOpen,
		int preloadIdleLimit
	) throws IOException {
		var registry = registry();
		var node = new Indexes(
			state,
			new NoopSyncProvider(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			new RecordingIndexRemovals(),
			RequestMetrics.none(),
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
			Duration.ofHours(1),
			Optional.empty(),
			preloadIdleLimit,
			0,
			Duration.ZERO,
			Duration.ZERO,
			SearchThreads.inline()
		);

		nodes.add(node);
		return node;
	}

	/**
	 * Wait for a generation to appear among the open ones. This is how an
	 * open that happened before any write shows from outside.
	 */
	private static void awaitOpen(Indexes node, String generation)
		throws InterruptedException {
		var deadline = System.nanoTime() + OPEN_WAIT.toNanos();
		while(System.nanoTime() < deadline) {
			if(node.getOpen().containsKey(generation)) {
				return;
			}

			Thread.sleep(10);
		}

		assertThat(node.getOpen().keySet(), contains(generation));
	}

	/**
	 * Being given an index to write opens the generation it answers from,
	 * so the copy and the writer are ready before the first write asks for
	 * them.
	 */
	@Test
	public void testClaimOpensTheLiveGeneration() throws Exception {
		writer().create("books", IndexDef.getDefaultInstance());

		var state = new NodeState(true);
		var taker = node(state, storageDirectory.resolve("taker"), OptionalInt.empty(), 16);
		taker.refresh();

		assertThat(taker.getOpen().keySet(), is(emptyIterable()));

		state.updateOwnership("books", true);
		awaitOpen(taker, "books@1");
	}

	/**
	 * An index the registry does not name yet opens nothing. This happens
	 * while a create is running: the create takes the index first and writes
	 * the registry after, and it opens the generation itself.
	 */
	@Test
	public void testClaimAheadOfTheRegistryOpensNothing() throws Exception {
		var state = new NodeState(true);
		var taker = node(state, storageDirectory.resolve("taker"), OptionalInt.empty(), 16);
		taker.refresh();

		state.updateOwnership("books", true);
		Thread.sleep(SETTLE.toMillis());

		assertThat(taker.getOpen().keySet(), is(emptyIterable()));
	}

	/**
	 * Opening an index early never closes one that is answering requests. A
	 * node that is already at its limit of open indexes waits for the first
	 * write instead.
	 */
	@Test
	public void testClaimIsNotOpenedAtTheOpenBudget() throws Exception {
		var writer = writer();
		writer.create("books", IndexDef.getDefaultInstance());
		writer.create("authors", IndexDef.getDefaultInstance());

		var state = new NodeState(true);
		var taker = node(state, storageDirectory.resolve("taker"), OptionalInt.of(1), 16);
		taker.refresh();

		// The one generation the limit allows, kept open by having been asked for
		taker.getOrThrow("authors");

		state.updateOwnership("books", true);
		Thread.sleep(SETTLE.toMillis());

		assertThat(taker.getOpen().keySet(), contains("authors@1"));
	}

	/**
	 * A node above the idle limit keeps a writer only for the indexes that
	 * were being written when it was given them. An idle index waits for its
	 * first write, whenever that comes.
	 */
	@Test
	public void testIdleClaimIsNotOpenedAboveTheLimit() throws Exception {
		writer().create("books", IndexDef.getDefaultInstance());

		var state = new NodeState(true);
		var taker = node(state, storageDirectory.resolve("taker"), OptionalInt.empty(), 0);
		taker.refresh();

		state.updateOwnership("books", true);
		Thread.sleep(SETTLE.toMillis());

		assertThat(taker.getOpen().keySet(), is(emptyIterable()));
	}

	/**
	 * An index handed over while it was being written arrives with the write
	 * load of its last writer. Another write for it is about to arrive, so
	 * the node opens it however many indexes it holds.
	 */
	@Test
	public void testBusyClaimIsOpenedAboveTheLimit() throws Exception {
		writer().create("books", IndexDef.getDefaultInstance());

		var state = new NodeState(true);
		var taker = node(state, storageDirectory.resolve("taker"), OptionalInt.empty(), 0);
		taker.refresh();

		// The write load a handover copies from the leadership table
		state.recordWrite("books", 1000);

		state.updateOwnership("books", true);
		awaitOpen(taker, "books@1");
	}
}
