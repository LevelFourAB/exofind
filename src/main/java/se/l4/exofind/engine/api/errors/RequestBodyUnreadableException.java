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

	private static final ErrorType TYPE = ErrorType.withCode("request:body_unreadable")
		.withStatus(400)
		.withMessage("The request body could not be read to the end");

	/**
	 * @param cause
	 * @param arguments
	 *   what the request had got through before the body stopped arriving, as
	 *   key-value pairs. A streamed batch says how far it got so the caller can
	 *   send the rest, and a request that reads its body in one go passes none.
	 */
	public RequestBodyUnreadableException(Throwable cause, Object... arguments) {
		super(TYPE, ErrorType.toArguments(arguments), cause);
	}
}
