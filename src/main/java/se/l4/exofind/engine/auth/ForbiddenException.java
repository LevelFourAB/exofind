package se.l4.exofind.engine.auth;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a caller is known but has not been granted what the request
 * needs.
 *
 * <p>The permission that was missing is named, because a caller that cannot see
 * what their key lacks has no way to ask for the right one. An index the caller
 * was not granted any permission on is answered as though it did not exist
 * instead, so this never confirms that an index is there.
 */
public class ForbiddenException extends AuthException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("auth:forbidden")
		.withArguments("permission")
		.withMessage("This API key is not granted `{{permission}}`");

	public ForbiddenException(Permission permission) {
		super(TYPE, "permission", permission.id());
	}
}
