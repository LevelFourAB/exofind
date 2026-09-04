package se.l4.exofind.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.index.FacetWarmer;
import se.l4.exofind.engine.index.SearchThreads;
import se.l4.exofind.engine.index.registry.IndexRegistry;
import se.l4.exofind.engine.index.registry.LocalRegistryStorage;
import se.l4.exofind.engine.index.registry.RegistryHints;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.metrics.Meters;
import se.l4.exofind.engine.metrics.RequestMetrics;
import se.l4.exofind.engine.storage.StorageMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * What a node reports about opening a generation: how long the request that
 * asked for it waited, and what opened it. The numbers say whether a search
 * met a copy that was already open, so they are what tells an operator
 * whether the preload is worth what it holds.
 */
public class IndexesOpenMetricsTest {
	/**
	 * How long a wait for the preload gives up after. Generously above what
	 * opening an empty index takes, so a slow machine does not read as a
	 * preload that never ran.
	 */
	private static final Duration WAIT = Duration.ofSeconds(10);

	/**
	 * A readiness wait long enough that nothing in a test outlasts it, so the
	 * preload rather than the wait decides what is open.
	 */
	private static final Duration LONG_WAIT = Duration.ofMinutes(10);

	@TempDir
	Path storageDirectory;

	private final List<Indexes> nodes = new ArrayList<>();

	private SimpleMeterRegistry registry;

	@BeforeEach
	void createRegistry() {
		registry = new SimpleMeterRegistry();
	}

	@AfterEach
	void cleanup() {
		for(var node : nodes) {
			node.close();
		}
	}

	@Test
	public void testARequestThatOpensAGenerationIsTimedAsHavingWaited() throws Exception {
		createIndexes("books");

		var node = node(0, LONG_WAIT);
		node.refresh();
		node.getOrThrow("books");

		assertThat(waits(Meters.OUTCOME_SUCCESS), is(1L));
		assertThat(waitedNanos(Meters.OUTCOME_SUCCESS), is(greaterThan(0d)));
	}

	/**
	 * The timer counts the requests that waited rather than the requests that
	 * were served, so a generation that is already open adds nothing to it.
	 */
	@Test
	public void testARequestForAnOpenGenerationIsNotTimed() throws Exception {
		createIndexes("books");

		var node = node(0, LONG_WAIT);
		node.refresh();
		node.getOrThrow("books");
		node.getOrThrow("books");
		node.getOrThrow("books");

		assertThat(waits(Meters.OUTCOME_SUCCESS), is(1L));
	}

	@Test
	public void testAnOpenARequestWaitedForIsCountedAgainstTheRequest() throws Exception {
		createIndexes("books");

		var node = node(0, LONG_WAIT);
		node.refresh();
		node.getOrThrow("books");

		assertThat(opens(Meters.SOURCE_REQUEST), is(1L));
		assertThat(opens(Meters.SOURCE_PRELOAD), is(0L));
	}

	/**
	 * An open the preload made is counted apart from one a request waited
	 * for, which is how the two together say how many opens the preload
	 * caught before anything asked.
	 */
	@Test
	public void testAnOpenThePreloadMadeIsCountedAgainstThePreload() throws Exception {
		createIndexes("books");

		var node = node(8, LONG_WAIT);
		node.refresh();
		awaitSettledPreload(node);

		assertThat(node.getOpen().keySet(), contains("books@1"));
		assertThat(opens(Meters.SOURCE_PRELOAD), is(1L));
		assertThat(opens(Meters.SOURCE_REQUEST), is(0L));

		// The generation is open, so the request that follows waits for nothing
		node.getOrThrow("books");

		assertThat(opens(Meters.SOURCE_REQUEST), is(0L));
		assertThat(waits(Meters.OUTCOME_SUCCESS), is(0L));
	}

	/**
	 * Create the given indexes and shut the node down again, leaving their
	 * directories the way a node that was restarted would. The node that
	 * seeds them reports to a registry of its own, so the opens it makes are
	 * not the ones a test counts.
	 */
	private void createIndexes(String... names) throws IOException {
		var seeder = node(0, LONG_WAIT, RequestMetrics.none());
		try {
			for(var name : names) {
				seeder.create(name, IndexDef.getDefaultInstance());
			}
		} finally {
			seeder.close();
		}
	}

	private Indexes node(int preloadMaxIndexes, Duration readinessWait) throws IOException {
		return node(preloadMaxIndexes, readinessWait, new RequestMetrics(registry, false));
	}

	/**
	 * A node over the shared directory, which holds every index it finds
	 * there.
	 *
	 * @param preloadMaxIndexes
	 *   how many copies it opens when it starts
	 * @param readinessWait
	 *   how long it waits for the preload before it reports itself ready
	 * @param metrics
	 *   what it reports its opens to
	 */
	private Indexes node(
		int preloadMaxIndexes,
		Duration readinessWait,
		RequestMetrics metrics
	) throws IOException {
		var state = new NodeState(true);
		state.updateOwnership(true);

		var indexRegistry = new IndexRegistry(
			new LocalRegistryStorage(storageDirectory.resolve("registry.ef.bin")),
			Duration.ofMinutes(5)
		);

		var node = new Indexes(
			state,
			new NoopSyncProvider(),
			indexRegistry,
			new RegistryHints(indexRegistry, StorageMode.LOCAL),
			new RecordingIndexRemovals(),
			metrics,
			storageDirectory,
			OptionalInt.empty(),
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
			SearchThreads.inline(),
			FacetWarmer.none()
		);

		nodes.add(node);
		return node;
	}

	/**
	 * How many requests were timed as having waited for a generation to open.
	 */
	private long waits(String outcome) {
		var timer = registry.find(Meters.INDEXES_OPEN_WAIT)
			.tag(Meters.TAG_OUTCOME, outcome)
			.timer();

		return timer == null ? 0 : timer.count();
	}

	/**
	 * How long those requests waited in total, which says the timer measured
	 * the wait rather than recording a zero.
	 */
	private double waitedNanos(String outcome) {
		return registry.get(Meters.INDEXES_OPEN_WAIT)
			.tag(Meters.TAG_OUTCOME, outcome)
			.timer()
			.totalTime(TimeUnit.NANOSECONDS);
	}

	/**
	 * How many generations were opened by the given source.
	 */
	private long opens(String source) {
		var counter = registry.find(Meters.INDEXES_OPENED)
			.tag(Meters.TAG_SOURCE, source)
			.counter();

		return counter == null ? 0 : (long) counter.count();
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

		fail("The preload did not settle within " + WAIT);
	}
}
