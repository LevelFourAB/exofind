package se.l4.exofind.engine.api.errors;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * The error codes every endpoint can answer with about the shape of a request,
 * declared once so that each mapper reports the same mistake the same way.
 *
 * <p>The codes a mapper reports about the request it reads belong beside that
 * mapper. Only a code that means the same thing wherever it is answered from
 * belongs here.
 */
public final class RequestErrors {
	/**
	 * A place in the request holds {@code null} where a value is needed. The
	 * {@code path} of the error says where.
	 */
	public static final ErrorType VALUE_REQUIRED = ErrorType.withCode("request:value_required")
		.withStatus(400)
		.withMessage("A value is needed here, `null` says nothing");

	private RequestErrors() {
	}
}
