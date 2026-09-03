package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
import se.l4.exofind.engine.index.state.IndexUsage;
import se.l4.exofind.engine.index.state.IndexUsageFile;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * What a node opens again when it starts over a directory it filled before it
 * restarted, which is what a persistent volume or a process restarted in place
 * leaves it with. The copies are made hot or cold by writing their usage
 * records directly, the way the disk sweep is tested.
 */
public class IndexesStartupPreloadTest {
	/**
	 * How long a wait for the preload gives up after. Generously above what
	 * opening a few empty indexes takes, so a slow machine does not read as a
	 * preload that never ran.
	 */
	private static final Duration WAIT = Duration.ofSeconds(10);

	/**
	 * A readiness wait long enough that nothing in a test outlasts it, for the
	 * tests asking what the preload rather than the cap decides.
	 */
	private static final Duration LONG_WAIT = Duration.ofMinutes(10);

	@TempDir
	Path storageDirectory;

	private final List<Indexes> nodes = new ArrayList<>();

	@AfterEach
	void cleanup() {
		for(var node : nodes) {
			node.close();
		}
	}

	@Test
	public void testHeldCopiesAreOpenedAgain() throws Exception {
		createIndexes("books", "films");

		var node = node(8, OptionalInt.empty(), LONG_WAIT);
		node.refresh();
		awaitSettledPreload(node);

		assertThat(node.getOpen().keySet(), containsInAnyOrder("books@1", "films@1"));
	}

	@Test
	public void testNothingIsOpenedWithoutCopiesToOpen() throws Exception {
		var node = node(8, OptionalInt.empty(), LONG_WAIT);
		node.refresh();
		awaitSettledPreload(node);

		assertThat(node.getOpen().keySet(), is(emptyIterable()));
	}

	@Test
	public void testTheMostUsedCopiesAreOpenedFirst() throws Exception {
		createIndexes("hot", "cold");
		ageUsage("hot", Duration.ofDays(1), 8);
		ageUsage("cold", Duration.ofDays(1), 1);

		var node = node(1, OptionalInt.empty(), LONG_WAIT);
		node.refresh();
		awaitSettledPreload(node);

		assertThat(node.getOpen().keySet(), contains("hot@1"));
	}

	@Test
	public void testNoMoreCopiesAreOpenedThanTheNodeMayHoldOpen() throws Exception {
		createIndexes("hot", "cold");
		ageUsage("hot", Duration.ofDays(1), 8);
		ageUsage("cold", Duration.ofDays(1), 1);

		var node = node(8, OptionalInt.of(1), LONG_WAIT);
		node.refresh();
		awaitSettledPreload(node);

		assertThat(node.getOpen().keySet(), contains("hot@1"));
	}

	@Test
	public void testTurningThePreloadOffOpensNothing() throws Exception {
		createIndexes("books");

		var node = node(0, OptionalInt.empty(), LONG_WAIT);
		node.refresh();
		awaitSettledPreload(node);

		assertThat(node.getOpen().keySet(), is(emptyIterable()));
	}

	/**
	 * A copy of something the registry does not name is left alone. It is
	 * either a directory left by hand or one the sweep of unregistered copies
	 * is about to remove, and neither is something to open.
	 */
	@Test
	public void testACopyTheRegistryDoesNotNameIsNotOpened() throws Exception {
		createIndexes("books");
		Files.createDirectories(dir("ghost"));

		var node = node(8, OptionalInt.empty(), LONG_WAIT);
		node.refresh();
		awaitSettledPreload(node);

		assertThat(node.getOpen().keySet(), contains("books@1"));
	}

	/**
	 * Only the generation the index answers for is opened. An older one costs
	 * a writer and a merge thread to hold open, and answers only a request
	 * that asks for it by name.
	 */
	@Test
	public void testAGenerationTheIndexDoesNotAnswerFromIsNotOpened() throws Exception {
		var seeder = node(0, OptionalInt.empty(), LONG_WAIT);
		seeder.create("books", IndexDef.getDefaultInstance());
		seeder.createGeneration("books@2", IndexDef.getDefaultInstance());
		seeder.close();

		var node = node(8, OptionalInt.empty(), LONG_WAIT);
		node.refresh();
		awaitSettledPreload(node);

		assertThat(node.getOpen().keySet(), contains("books@1"));
	}

	/**
	 * The preload picks what to open from the open counts, so counting itself
	 * would make every copy it opened more likely to be opened again whether
	 * or not anyone searched it.
	 */
	@Test
	public void testAPreloadDoesNotCountAsAnOpen() throws Exception {
		createIndexes("books");
		ageUsage("books", Duration.ofDays(30), 4);

		var served = node(0, OptionalInt.empty(), LONG_WAIT);
		served.refresh();
		served.getOrThrow("books");
		served.close();

		var afterRequest = IndexUsageFile.read(dir("books")).getDecayedOpens();
		assertThat(afterRequest, is(greaterThan(0d)));

		var node = node(8, OptionalInt.empty(), LONG_WAIT);
		node.refresh();
		awaitSettledPreload(node);
		node.close();

		assertThat(IndexUsageFile.read(dir("books")).getDecayedOpens(), is(afterRequest));
	}

	/**
	 * A node reports itself ready only once the preload is done with, so a
	 * rolling upgrade does not route searches to a node that is still opening
	 * what it holds.
	 */
	@Test
	public void testTheNodeIsNotSettledUntilThePreloadIsDone() throws Exception {
		createIndexes("books");

		var node = node(8, OptionalInt.empty(), LONG_WAIT);
		assertThat(node.hasSettledPreload(), is(false));

		node.refresh();
		awaitSettledPreload(node);

		assertThat(node.getOpen().keySet(), contains("books@1"));
	}

	/**
	 * The wait is a cap rather than a promise. A node whose copies are slow to
	 * open - or whose registry it never managed to read - serves what it has
	 * instead of staying out of the deployment.
	 */
	@Test
	public void testTheNodeSettlesOnceTheWaitPasses() throws Exception {
		createIndexes("books");

		var node = node(8, OptionalInt.empty(), Duration.ofMillis(50));

		// Nothing calls refresh, so only the wait passing can settle this node
		awaitSettledPreload(node);
	}

	@Test
	public void testANodeThatWaitsForNothingIsSettledAtOnce() throws Exception {
		createIndexes("books");

		assertThat(
			node(8, OptionalInt.empty(), Duration.ZERO).hasSettledPreload(),
			is(true)
		);
	}

	/**
	 * Create the given indexes and shut the node down again, leaving their
	 * directories the way a node that was restarted would.
	 */
	private void createIndexes(String... names) throws IOException {
		var seeder = node(0, OptionalInt.empty(), LONG_WAIT);
		try {
			for(var name : names) {
				seeder.create(name, IndexDef.getDefaultInstance());
			}
		} finally {
			seeder.close();
		}
	}

	/**
	 * A node over the shared directory, which holds every index it finds
	 * there.
	 *
	 * @param preloadMaxIndexes
	 *   how many copies it opens when it starts
	 * @param maxOpen
	 *   how many generations it may hold open at once
	 * @param readinessWait
	 *   how long it waits for the preload before it reports itself ready
	 */
	private Indexes node(
		int preloadMaxIndexes,
		OptionalInt maxOpen,
		Duration readinessWait
	) throws IOException {
		var state = new NodeState(true);
		state.updateOwnership(true);

		var registry = new IndexRegistry(
			new LocalRegistryStorage(storageDirectory.resolve("registry.ef.bin")),
			Duration.ofMinutes(5)
		);

		var node = new Indexes(
			state,
			new NoopSyncProvider(),
			registry,
			new RegistryHints(registry, StorageMode.LOCAL),
			new RecordingIndexRemovals(),
			RequestMetrics.none(),
			storageDirectory,
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
			16,
			preloadMaxIndexes,
			Duration.ofMinutes(5),
			readinessWait,
			SearchThreads.inline()
		);

		nodes.add(node);
		return node;
	}

	/**
	 * The directory of an index, which is the directory of its first
	 * generation unless the test made another one.
	 */
	private Path dir(String name) {
		return storageDirectory.resolve("indexes").resolve(name + "@1");
	}

	/**
	 * Write the usage record of a copy directly, standing in for a node that
	 * opened it that many times and last used it that long ago.
	 */
	private void ageUsage(String name, Duration age, double opens) throws IOException {
		var at = Instant.now().minus(age);

		IndexUsageFile.write(
			dir(name),
			IndexUsage.newBuilder()
				.setLastUsedMs(at.toEpochMilli())
				.setDecayedOpens(opens)
				.setDecayedAtMs(at.toEpochMilli())
				.build()
		);
	}

	/**
	 * Wait for a node to stop waiting for its preload, which is what says the
	 * opens it decided on have all been tried.
	 */
	private static void awaitSettledPreload(Indexes node) throws InterruptedException {
		var deadline = System.nanoTime() + WAIT.toNanos();
		while(System.nanoTime() < deadline) {
			if(node.hasSettledPreload()) {
				return;
			}

			Thread.sleep(10);
		}

		fail("The node never stopped waiting for its preload");
	}
}
