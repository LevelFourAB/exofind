package se.l4.exofind.engine.index;

import java.time.Duration;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a search collects for longer than the node allows and is
 * abandoned part of the way through.
 *
 * <p>The results found before the budget ran out are dropped, because a page
 * assembled from part of the index would read as a complete answer. Repeating
 * the request unchanged costs the same again, so a caller has to narrow the
 * search to get an answer.
 *
 * @see SearchDeadline
 */
public class SearchTimeoutException extends IndexException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("search:timeout")
		.withArguments("index", "timeout")
		.withMessage(
			"Searching `{{index}}` took longer than {{timeout}} and was abandoned; narrow the search"
		);

	/**
	 * @param timeout
	 *   the budget the search ran past, reported to the caller in
	 *   milliseconds
	 */
	public SearchTimeoutException(String index, Duration timeout) {
		super(TYPE, "index", index, "timeout", timeout.toMillis() + "ms");
	}
}
