package se.l4.exofind.engine.auth;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a request carries no credential this node accepts.
 *
 * <p>A credential that is absent, malformed, unknown or lapsed all raise this
 * and say the same thing, so that a caller can not learn which keys exist by
 * comparing the answers.
 */
public class UnauthenticatedException extends AuthException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("auth:unauthenticated")
		.withMessage("A valid API key is required");

	public UnauthenticatedException() {
		super(TYPE);
	}
}
