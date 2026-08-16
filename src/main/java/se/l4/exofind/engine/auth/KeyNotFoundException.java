package se.l4.exofind.engine.auth;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a key is managed by an id no key is stored under.
 */
public class KeyNotFoundException extends AuthException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType TYPE = ErrorType.withCode("auth:key:not_found")
		.withArguments("key")
		.withMessage("There is no key with the id `{{key}}`");

	public KeyNotFoundException(String id) {
		super(TYPE, "key", id);
	}
}
