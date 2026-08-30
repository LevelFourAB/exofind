package se.l4.exofind.engine.index.registry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import se.l4.exofind.engine.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Reads the registry for the whole node and hands each read to the parts that
 * work from it.
 *
 * <p>The registry carries a version hint for every manifest and every settings
 * object the deployment holds, so one conditional request answers whether
 * anything this node holds has changed. A node reads it at the shortest
 * interval any {@link Listener} asks for. Each listener then decides for itself
 * what to fetch from the storage, and how often it may fetch the same thing.
 *
 * <p>Listeners run on their own executors, so a pull that takes minutes does
 * not delay another listener. A listener still working from an earlier read is
 * passed over until it finishes.
 *
 * <p>Safe for concurrent use.
 */
@Singleton
public class RegistryPoller {
	private static final Log logger = Log.of(RegistryPoller.class);

	/**
	 * Longest the poller sleeps before working out what it owes. A listener
	 * that starts wanting reads shortens what every listener waits for, so a
	 * sleep no longer than this keeps a listener asking for a long interval
	 * from holding one asking for a short one. A wake that finds no read due
	 * costs nothing.
	 */
	private static final Duration MAX_DELAY = Duration.ofSeconds(30);

	/**
	 * Shortest the poller sleeps between reads. An interval configured at or
	 * near zero would otherwise turn the polling thread into a spin.
	 */
	private static final Duration MIN_DELAY = Duration.ofMillis(100);

	/**
	 * A part of the node that works from the registry, called after each read.
	 */
	public interface Listener {
		/**
		 * How often this listener wants the registry read, or empty while it
		 * wants nothing. The node reads at the shortest interval any listener
		 * asks for, and a listener asking for nothing is not called.
		 *
		 * <p>Read again before every poll, so a listener may change its answer
		 * at any time. A change is picked up within 30 seconds.
		 *
		 * @return
		 */
		Optional<Duration> pollInterval();

		/**
		 * Where {@link #onRegistryPolled(boolean)} runs. May block for as long
		 * as the listener's own work takes.
		 *
		 * @return
		 */
		Executor executor();

		/**
		 * Act on the registry as this node now holds it. Runs on
		 * {@link #executor()}, never on the polling thread, and never next to
		 * another call for the same listener.
		 *
		 * @param changed
		 *   whether the read moved this node's copy; {@code false} when the
		 *   stored registry is the copy the node already held, and when it
		 *   could not be read
		 */
		void onRegistryPolled(boolean changed);
	}

	private final IndexRegistry registry;

	/**
	 * The listeners the container holds, resolved at startup so that building
	 * this bean does not force every listener into existence. {@code null} for
	 * a poller given its listeners directly.
	 */
	private final Instance<Listener> discovered;

	private final ScheduledExecutorService executor;

	private volatile List<Registration> registrations;

	/**
	 * When the registry was last read, as {@link System#nanoTime()}. One
	 * timestamp for every listener, as a read serves all of them.
	 */
	private volatile long lastPollNanos;

	private volatile boolean polledEver;

	/**
	 * One listener, and whether it is still working from an earlier read.
	 */
	private static final class Registration {
		final Listener listener;
		final AtomicBoolean busy = new AtomicBoolean();

		Registration(Listener listener) {
			this.listener = listener;
		}
	}

	@Inject
	public RegistryPoller(IndexRegistry registry, Instance<Listener> listeners) {
		this.registry = registry;
		this.discovered = listeners;
		this.registrations = List.of();
		this.executor = newExecutor();
	}

	/**
	 * A poller over the listeners named here, for a node assembled without the
	 * container. Reads nothing until {@link #poll()} is called.
	 *
	 * @param registry
	 * @param listeners
	 */
	public RegistryPoller(IndexRegistry registry, List<Listener> listeners) {
		this.registry = registry;
		this.discovered = null;
		this.registrations = register(listeners);
		this.executor = newExecutor();
	}

	private static ScheduledExecutorService newExecutor() {
		return Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "registry-poller");
			thread.setDaemon(true);
			return thread;
		});
	}

	private static List<Registration> register(Iterable<Listener> listeners) {
		var registrations = new ArrayList<Registration>();
		for(var listener : listeners) {
			registrations.add(new Registration(listener));
		}

		return List.copyOf(registrations);
	}

	void onStart(@Observes StartupEvent event) {
		if(discovered != null) {
			registrations = register(discovered);
		}

		// The first read happens at once, so a node syncs from the moment it starts
		executor.execute(this::tick);
	}

	@PreDestroy
	void stop() {
		executor.shutdownNow();
	}

	/**
	 * Read the registry once, if the shortest interval any listener wants has
	 * passed, and pass what it says to every listener that wants reads and is
	 * not still working from an earlier one.
	 */
	void poll() {
		var wanted = shortestInterval();
		if(wanted == null) {
			return;
		}

		var now = System.nanoTime();
		if(polledEver && now - lastPollNanos < wanted.toNanos()) {
			return;
		}

		var due = new ArrayList<Registration>();
		for(var registration : registrations) {
			if(registration.listener.pollInterval().isEmpty()) {
				continue;
			}

			/*
			 * Queueing a second call behind a listener that has not finished
			 * the first would only have it act on a registry the next poll
			 * reads anyway.
			 */
			if(registration.busy.compareAndSet(false, true)) {
				due.add(registration);
			}
		}

		if(due.isEmpty()) {
			return;
		}

		lastPollNanos = now;
		polledEver = true;

		var before = registry.version();
		registry.refresh();
		var changed = !Objects.equals(before, registry.version());

		for(var registration : due) {
			dispatch(registration, changed);
		}
	}

	/**
	 * The shortest interval any listener wants a read at, or {@code null} when
	 * none wants one.
	 */
	private Duration shortestInterval() {
		Duration shortest = null;
		for(var registration : registrations) {
			var wanted = registration.listener.pollInterval().orElse(null);
			if(wanted != null && (shortest == null || wanted.compareTo(shortest) < 0)) {
				shortest = wanted;
			}
		}

		return shortest;
	}

	private void dispatch(Registration registration, boolean changed) {
		try {
			registration.listener.executor().execute(() -> {
				try {
					registration.listener.onRegistryPolled(changed);
				} catch(RuntimeException e) {
					logger.atWarn()
						.setCause(e)
						.log("A listener could not act on the registry; " + e.getMessage());
				} finally {
					registration.busy.set(false);
				}
			});
		} catch(RejectedExecutionException e) {
			// The listener is shutting down and has nothing more to keep current
			registration.busy.set(false);
		}
	}

	private void tick() {
		try {
			poll();
		} catch(RuntimeException e) {
			/*
			 * Letting this out would cancel the schedule, leaving the node on
			 * whatever it happens to hold until it is restarted.
			 */
			logger.atWarn()
				.setCause(e)
				.log("Could not poll the registry; " + e.getMessage());
		} finally {
			scheduleNext();
		}
	}

	private void scheduleNext() {
		var delay = MAX_DELAY;

		var wanted = shortestInterval();
		if(wanted != null) {
			var remaining = polledEver
				? wanted.minusNanos(System.nanoTime() - lastPollNanos)
				: Duration.ZERO;

			if(remaining.compareTo(delay) < 0) {
				delay = remaining;
			}
		}

		if(delay.compareTo(MIN_DELAY) < 0) {
			delay = MIN_DELAY;
		}

		try {
			executor.schedule(this::tick, delay.toMillis(), TimeUnit.MILLISECONDS);
		} catch(RejectedExecutionException e) {
			// Shutting down, so there is no next read to arrange
		}
	}
}
