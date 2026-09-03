package se.l4.exofind.engine.metrics;

import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import se.l4.exofind.engine.index.IndexName;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Records what the engine did while serving a request.
 *
 * <p>Every method takes the measurement already made by the caller rather than
 * timing anything itself, so a call site that measures for its own response
 * body reports the same number it returns.
 *
 * <p>Safe for concurrent use. A meter that has not been used yet is created on
 * the first call, and looked up on the ones after.
 */
@ApplicationScoped
public class RequestMetrics {
	private final MeterRegistry registry;
	private final boolean searchByIndex;

	public RequestMetrics(
		MeterRegistry registry,
		@ConfigProperty(
			name = "exofind.metrics.index.search-histogram",
			defaultValue = "false"
		) boolean searchByIndex
	) {
		this.registry = registry;
		this.searchByIndex = searchByIndex;
	}

	/**
	 * Metrics that go nowhere, for an index opened outside a node.
	 *
	 * <p>Recording against these costs a map lookup and nothing else. The
	 * registry has no backing registry to hand meters to, so nothing is
	 * exported and nothing accumulates beyond one meter per set of tags.
	 */
	public static RequestMetrics none() {
		return new RequestMetrics(new CompositeMeterRegistry(), false);
	}

	/**
	 * Record a search that was answered.
	 *
	 * @param name
	 *   the index as the request named it, with or without a generation
	 * @param nanos
	 *   how long the search took
	 * @param ok
	 *   whether the search answered rather than failing
	 */
	public void recordSearch(String name, long nanos, boolean ok) {
		registry.timer(Meters.SEARCH, searchTags(name, ok))
			.record(nanos, TimeUnit.NANOSECONDS);
	}

	/**
	 * Record a word a search let go of before it matched.
	 *
	 * @param reason
	 *   what made the word the one to go
	 */
	public void recordRelaxation(String reason) {
		registry.counter(Meters.SEARCH_RELAXATION, Meters.TAG_REASON, reason)
			.increment();
	}

	/**
	 * Record pieces of search work handed to the search threads of the node.
	 *
	 * @param thread
	 *   which kind of thread ran the pieces, {@link Meters#THREAD_POOL} or
	 *   {@link Meters#THREAD_REQUEST}
	 * @param count
	 *   how many pieces ran there; nothing is recorded for zero
	 */
	public void recordSearchPieces(String thread, long count) {
		if(count <= 0) {
			return;
		}

		registry.counter(Meters.SEARCH_PIECES, Meters.TAG_THREAD, thread)
			.increment(count);
	}

	/**
	 * Record a write served by this node.
	 *
	 * @param operation
	 *   what the request asked for, such as {@code add} or {@code delete}
	 * @param nanos
	 *   how long the write took
	 * @param documents
	 *   how many documents the write covered
	 * @param ok
	 *   whether the write was applied rather than failing
	 */
	public void recordWrite(String operation, long nanos, long documents, boolean ok) {
		registry.timer(
				Meters.WRITE,
				Meters.TAG_OPERATION, operation,
				Meters.TAG_OUTCOME, outcome(ok)
			)
			.record(nanos, TimeUnit.NANOSECONDS);

		if(documents > 0) {
			registry.counter(Meters.WRITE_DOCUMENTS, Meters.TAG_OPERATION, operation)
				.increment(documents);
		}
	}

	/**
	 * Record a write this node handed to the node holding the index.
	 *
	 * @param outcome
	 *   how the handover ended, such as {@code success} or the name of what
	 *   stopped it
	 */
	public void recordForward(String outcome) {
		registry.counter(Meters.WRITE_FORWARDED, Meters.TAG_OUTCOME, outcome)
			.increment();
	}

	/**
	 * Record a Lucene commit.
	 *
	 * @param trigger
	 *   one of the {@code TRIGGER_} constants of {@link Meters}
	 * @param nanos
	 *   how long the commit took
	 * @param ok
	 *   whether the commit succeeded
	 */
	public void recordCommit(String trigger, long nanos, boolean ok) {
		registry.timer(
				Meters.COMMIT,
				Meters.TAG_TRIGGER, trigger,
				Meters.TAG_OUTCOME, outcome(ok)
			)
			.record(nanos, TimeUnit.NANOSECONDS);
	}

	/**
	 * Record an index being pushed to remote storage.
	 */
	public void recordPush(long nanos, boolean ok) {
		registry.timer(Meters.SYNC_PUSH, Meters.TAG_OUTCOME, outcome(ok))
			.record(nanos, TimeUnit.NANOSECONDS);
	}

	/**
	 * Record an index being pulled from remote storage.
	 */
	public void recordPull(long nanos, boolean ok) {
		registry.timer(Meters.SYNC_PULL, Meters.TAG_OUTCOME, outcome(ok))
			.record(nanos, TimeUnit.NANOSECONDS);
	}

	/**
	 * Record a synchronization refused because another node had written the
	 * index.
	 *
	 * @param operation
	 *   {@code push} or {@code pull}
	 */
	public void recordConflict(String operation) {
		registry.counter(Meters.SYNC_CONFLICT, Meters.TAG_OPERATION, operation)
			.increment();
	}

	/**
	 * Record a request answered with an error code.
	 *
	 * @param code
	 *   the code as {@code docs/reference/errors.md} lists it
	 */
	public void recordError(String code) {
		registry.counter(Meters.API_ERROR, Meters.TAG_CODE, code).increment();
	}

	/**
	 * Record a request refused for its credential.
	 *
	 * @param reason
	 *   why the credential was refused
	 */
	public void recordAuthFailure(String reason) {
		registry.counter(Meters.AUTH_FAILURE, Meters.TAG_REASON, reason).increment();
	}

	/**
	 * Tags of a search, carrying the index name only where a deployment has
	 * asked for it. Naming the index turns one histogram into one per index,
	 * which a deployment holding many of them pays for per index.
	 */
	private Tags searchTags(String name, boolean ok) {
		var tags = Tags.of(Meters.TAG_OUTCOME, outcome(ok));
		if(!searchByIndex) {
			return tags;
		}

		return tags.and(Meters.TAG_INDEX, IndexName.parse(name).index());
	}

	private static String outcome(boolean ok) {
		return ok ? Meters.OUTCOME_SUCCESS : Meters.OUTCOME_ERROR;
	}
}
