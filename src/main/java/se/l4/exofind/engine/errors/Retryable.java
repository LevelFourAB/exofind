package se.l4.exofind.engine.errors;

import java.time.Duration;

/**
 * An exception whose request is expected to succeed if sent again after a
 * wait. The API answers such an exception with a {@code Retry-After} header
 * carrying the wait, so a client backs off by what the node says instead of
 * by a guess of its own.
 */
public interface Retryable {
	/**
	 * Get how long a client should wait before sending the same request
	 * again.
	 *
	 * @return
	 */
	Duration retryAfter();
}
