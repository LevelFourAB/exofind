package se.l4.exofind.engine.index.registry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.l4.exofind.engine.Indexes;
import se.l4.exofind.engine.NodeState;
import se.l4.exofind.engine.index.schema.IndexDef;
import se.l4.exofind.engine.index.state.NoopRemoteSyncProvider;
import se.l4.exofind.engine.index.state.NoopSyncProvider;
import se.l4.exofind.engine.index.state.RecordingIndexRemovals;
import se.l4.exofind.engine.index.state.StateSyncProvider;
import se.l4.exofind.engine.storage.StorageMode;

/**
 * How one poller serves several listeners: the registry is read once for all of
 * them, a listener that wants nothing is passed over, and every listener is
 * told whether the read moved this node's copy.
 */
public class RegistryPollerTest {
	InMemoryRegistryStorage storage;
	IndexRegistry registry;

	@BeforeEach
	void setup() {
		storage = new InMemoryRegistryStorage();
		registry = new IndexRegistry(storage, Duration.ofMinutes(5));
	}

	/**
	 * A listener that records what it was told, and runs its work on the
	 * calling thread so a test needs no waiting. An interval of
	 * {@link Duration#ZERO} leaves every call to {@code poll} due, which is
	 * what the tests that are not about the interval want.
	 */
	static class RecordingListener implements RegistryPoller.Listener {
		final List<Boolean> polls = new ArrayList<>();
		volatile Optional<Duration> interval;

		RecordingListener(Duration interval) {
			this.interval = Optional.ofNullable(interval);
		}

		@Override
		public Optional<Duration> pollInterval() {
			return interval;
		}

		@Override
		public Executor executor() {
			return Runnable::run;
		}

		@Override
		public void onRegistryPolled(boolean changed) {
			polls.add(changed);
		}
	}

	/**
	 * One read answers for every listener, so what a node spends on the
	 * registry stays the same however many parts of it work from the registry.
	 * A listener wanting a longer interval than the read is called anyway, and
	 * decides for itself what to do with it.
	 */
	@Test
	public void testOneReadServesEveryListener() {
		var first = new RecordingListener(Duration.ofSeconds(10));
		var second = new RecordingListener(Duration.ofSeconds(30));
		var poller = new RegistryPoller(registry, List.of(first, second));

		poller.poll();

		assertThat(storage.reads, is(1));
		assertThat(first.polls.size(), is(1));
		assertThat(second.polls.size(), is(1));
	}

	/**
	 * The node reads at the shortest interval any listener asks for, and no
	 * more often, so a listener asking rarely does not have its interval
	 * shortened by the poller waking up.
	 */
	@Test
	public void testNothingIsReadAgainInsideTheShortestInterval() {
		var listener = new RecordingListener(Duration.ofMinutes(5));
		var poller = new RegistryPoller(registry, List.of(listener));

		poller.poll();
		poller.poll();
		poller.poll();

		assertThat(storage.reads, is(1));
		assertThat(listener.polls.size(), is(1));
	}

	@Test
	public void testAListenerWantingNothingIsNotCalled() {
		var wanting = new RecordingListener(Duration.ZERO);
		var idle = new RecordingListener(null);
		var poller = new RegistryPoller(registry, List.of(wanting, idle));

		poller.poll();

		assertThat(wanting.polls.size(), is(1));
		assertThat(idle.polls.size(), is(0));
	}

	/**
	 * Nothing is read at all while every listener wants nothing, so a node
	 * serving no searches and holding no indexes costs the storage nothing.
	 */
	@Test
	public void testNothingIsReadWhileNoListenerWantsIt() {
		var poller = new RegistryPoller(registry, List.of(new RecordingListener(null)));

		poller.poll();

		assertThat(storage.reads, is(0));
	}

	/**
	 * A listener is called on every read and decides for itself what a read
	 * that changed nothing is worth, so the flag separates the two.
	 */
	@Test
	public void testListenersAreToldWhetherTheCopyMoved() {
		// A second registry over the same storage stands for another node writing
		var elsewhere = new IndexRegistry(storage, Duration.ofMinutes(5));
		elsewhere.create("books", "1");

		var listener = new RecordingListener(Duration.ZERO);
		var poller = new RegistryPoller(registry, List.of(listener));

		poller.poll();
		poller.poll();

		elsewhere.create("films", "1");
		poller.poll();

		assertThat(listener.polls, contains(true, false, true));
	}

	@TempDir
	Path storageDirectory;

	private Indexes node(IndexRegistry registry, StateSyncProvider syncProvider) throws IOException {
		var state = new NodeState(true);
		state.updateOwnership(true);

		return new Indexes(
			state,
			syncProvider,
			registry,
			new RegistryHints(registry, StorageMode.OBJECT),
			new RecordingIndexRemovals(),
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
			Duration.ofHours(1)
		);
	}

	/**
	 * A listener that hands each poll to the node and runs it on the calling
	 * thread, so the test needs no waiting.
	 */
	private static RegistryPoller.Listener onCallingThread(Indexes node) {
		return new RegistryPoller.Listener() {
			@Override
			public Optional<Duration> pollInterval() {
				return Optional.of(Duration.ZERO);
			}

			@Override
			public Executor executor() {
				return Runnable::run;
			}

			@Override
			public void onRegistryPolled(boolean changed) {
				node.onRegistryPolled(changed);
			}
		};
	}

	/**
	 * Other parts of a node read the registry too: the hint flush, a read
	 * that waits for freshness, a lookup of a name the node does not know.
	 * When one of them reads a delete before the poller does, the node must
	 * still remove its copy of the deleted index.
	 */
	@Test
	public void testANodeRemovesTheCopyOfAnIndexDeletedWhenAnotherReadTookTheChangeFirst()
		throws IOException
	{
		// Given node S holding copies of books and films, which copy a remote
		var seeder = node(new IndexRegistry(storage, Duration.ofMinutes(5)), new NoopSyncProvider());
		try {
			seeder.create("books", IndexDef.getDefaultInstance());
			seeder.create("films", IndexDef.getDefaultInstance());
		} finally {
			seeder.close();
		}

		var nodeRegistry = new IndexRegistry(storage, Duration.ofMinutes(5));
		var node = node(nodeRegistry, new NoopRemoteSyncProvider());
		try {
			var poller = new RegistryPoller(nodeRegistry, List.of(onCallingThread(node)));
			poller.poll();

			var books = storageDirectory.resolve("indexes").resolve("books@1");
			assertThat(Files.isDirectory(books), is(true));

			// And another node deletes books
			var other = new IndexRegistry(storage, Duration.ofMinutes(5));
			other.refresh();
			other.remove("books");

			// And another read on S takes the change before the poller does
			nodeRegistry.refresh();

			// When the poller polls
			poller.poll();

			// Then S removes its copy of books
			assertThat(
				"copy of the deleted index kept",
				Files.exists(books),
				is(false)
			);
			assertThat(
				Files.isDirectory(storageDirectory.resolve("indexes").resolve("films@1")),
				is(true)
			);
		} finally {
			node.close();
		}
	}
}
