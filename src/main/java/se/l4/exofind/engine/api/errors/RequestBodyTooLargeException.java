package se.l4.exofind.engine.api.errors;

import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.map.MapIterable;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a request body carries more bytes than the node accepts.
 *
 * <p>Thrown while the body is being read, by the stream
 * {@link RequestBodyLimitFilter} wraps it in. The caller is still sending, so
 * the answer closes the connection - see {@link EngineExceptionMapper}.
 *
 * <p>An endpoint that acts on a body as it reads it catches this and throws it
 * again with how far it had got, so the caller can send the rest rather than
 * the whole body again.
 *
 * <p>A parser reading the body wraps what the stream throws into a failure of
 * its own - Jackson reports it as a body it could not read. Every place that
 * answers such a failure asks {@link #wrappedIn(Throwable)} first, so a body
 * the node refused is not reported as a body the caller wrote wrongly.
 */
public class RequestBodyTooLargeException extends EngineException {
	private static final long serialVersionUID = 1L;

	/**
	 * The one declaration of this code. {@link RefusedRequestMapper} answers
	 * with it as well, for a body refused by the length it states before any of
	 * it arrives.
	 *
	 * <p>The message states no size, so that it reads the same whether or not
	 * the answer carries the {@code limit} argument. The size is the argument,
	 * which is where a caller reads it from.
	 */
	static final ErrorType TYPE = ErrorType.withCode("request:body_too_large")
		.withStatus(413)
		.withArguments("limit")
		.withMessage("The request body is larger than this node accepts");

	/**
	 * @param limit
	 *   how many bytes the body may carry
	 */
	public RequestBodyTooLargeException(long limit) {
		super(TYPE, "limit", limit);
	}

	/**
	 * Find this refusal inside what a parser reading the body threw.
	 *
	 * @param e
	 *   the failure a parser reported
	 * @return
	 *   the refusal, or {@code null} where the failure is not one
	 */
	public static RequestBodyTooLargeException wrappedIn(Throwable e) {
		for(var cause = e; cause != null; cause = cause.getCause()) {
			if(cause instanceof RequestBodyTooLargeException refused) {
				return refused;
			}

			if(cause.getCause() == cause) {
				return null;
			}
		}

		return null;
	}

	/**
	 * @param cause
	 *   where the body stopped being read
	 * @param arguments
	 *   what the request had got through before the body passed the limit, as
	 *   key-value pairs. An endpoint that acts on a body as it reads it says
	 *   how far it got so the caller can send the rest, and one that reads its
	 *   body in one go passes none.
	 */
	public RequestBodyTooLargeException(
		RequestBodyTooLargeException cause,
		Object... arguments
	) {
		super(TYPE, alsoFrom(cause, arguments), cause);
	}

	/**
	 * The arguments an endpoint adds, together with the limit the body passed.
	 * The limit is what the message is rendered with, so it is carried over
	 * rather than stated again at every call site.
	 */
	private static MapIterable<String, Object> alsoFrom(
		RequestBodyTooLargeException cause,
		Object... arguments
	) {
		var all = Maps.mutable.<String, Object>empty();
		ErrorType.toArguments(arguments).forEachKeyValue(all::put);
		cause.getArguments().forEachKeyValue(all::put);

		return all;
	}
}
