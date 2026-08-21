package se.l4.exofind.engine.index.state;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a request being passed along to the indexer could not be
 * delivered to it - the address in the lease did not answer, or the
 * connection died before the response arrived.
 *
 * <p>Whether the indexer served the request cannot be known from here, so a
 * caller retries the way it would retry any failed write: safely for the
 * requests that state what should be, carefully for the ones that do not.
 */
public class IndexerUnreachableException extends EngineException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("indexer:unreachable")
		.withArguments("reason")
		.withMessage("The indexer could not be reached: {{reason}}");

	public IndexerUnreachableException(Throwable cause) {
		super(TYPE, cause, "reason", String.valueOf(cause.getMessage()));
	}
}
