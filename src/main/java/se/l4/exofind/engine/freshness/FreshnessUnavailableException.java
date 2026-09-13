package se.l4.exofind.engine.freshness;

import java.time.Duration;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;
import se.l4.exofind.engine.errors.Retryable;

/**
 * Thrown when a read demanded a state of an index that the answering node did
 * not reach inside the time it may wait.
 *
 * <p>The state is not lost, it is late: the writer has not committed it yet,
 * the push has not landed, or this node has not pulled it. Sending the same
 * request again once the wait named in the response has passed is expected to
 * succeed, which is what separates this from a request that has to change.
 */
public class FreshnessUnavailableException extends EngineException implements Retryable {
	private static final long serialVersionUID = 1L;

	/**
	 * How long a client is told to wait before asking again. One second is
	 * about what a commit and a pull take, so a client that follows it asks
	 * once more rather than many times.
	 */
	private static final Duration RETRY_AFTER = Duration.ofSeconds(1);

	private static final ErrorType TYPE = ErrorType.withCode("search:freshness:unavailable")
		.withStatus(503)
		.withArguments("index", "wait")
		.withMessage(
			"`{{index}}` did not reach the state the freshness token asks for within {{wait}};"
				+ " send the request again after the `Retry-After` header"
		);

	/**
	 * @param index
	 *   the name the read used
	 * @param wait
	 *   how long the node waited, reported to the caller in milliseconds
	 */
	public FreshnessUnavailableException(String index, Duration wait) {
		super(TYPE, "index", index, "wait", wait.toMillis() + "ms");
	}

	@Override
	public Duration retryAfter() {
		return RETRY_AFTER;
	}
}
