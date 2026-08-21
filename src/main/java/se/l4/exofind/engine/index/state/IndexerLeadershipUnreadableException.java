package se.l4.exofind.engine.index.state;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when who writes which index was asked for and the shared state
 * saying so could not be read. Nothing about the request is wrong - the
 * storage holding the answer could not be reached, and asking again once it
 * can is answered.
 */
public class IndexerLeadershipUnreadableException extends EngineException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("indexer:leadership_unreadable")
		.withMessage("Which node writes which index could not be read right now");

	public IndexerLeadershipUnreadableException() {
		super(TYPE);
	}
}
