package se.l4.exofind.engine.api.errors;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when the body of a request stopped arriving before it was read to the
 * end, which a streamed request finds in the middle of its work.
 *
 * <p>The node read what reached it and the rest never came - the caller
 * disconnected, or the connection broke. Nothing about the index is wrong, so
 * it is answered as a request that was not delivered rather than as a fault of
 * the node.
 */
public class RequestBodyUnreadableException extends EngineException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("request:unreadable")
		.withMessage("The request body could not be read to the end");

	public RequestBodyUnreadableException(Throwable cause) {
		super(TYPE, ErrorType.toArguments(), cause);
	}
}
