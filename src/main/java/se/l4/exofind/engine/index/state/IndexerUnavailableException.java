package se.l4.exofind.engine.index.state;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a request only the indexer serves reaches a node that is not
 * it and there is no indexer to pass the request along to - none is running,
 * the one running offered no address, or the request already was passed along
 * once and leadership has moved since.
 *
 * <p>The request itself is fine. Sent again once an indexer is up it is
 * served, so this refusal is answered as a conflict with the state of the
 * deployment rather than as a fault of the caller.
 */
public class IndexerUnavailableException extends EngineException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("indexer:unavailable")
		.withMessage("No node is serving writes right now");

	public IndexerUnavailableException() {
		super(TYPE);
	}
}
