package se.l4.exofind.engine.index.registry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
