package se.l4.exofind.engine.auth;

import org.eclipse.collections.api.map.MapIterable;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;

/**
 * AuthException is the root of exceptions about who a caller is and what they
 * may do.
 */
public class AuthException extends EngineException {
	private static final long serialVersionUID = 1L;

	public AuthException(ErrorType type, MapIterable<String, Object> arguments) {
		super(type, arguments);
	}

	public AuthException(ErrorType type, Object... arguments) {
		super(type, arguments);
	}

	public AuthException(ErrorType type, MapIterable<String, Object> arguments, Throwable cause) {
		super(type, arguments, cause);
	}

	public AuthException(ErrorType type, Throwable cause, Object... arguments) {
		super(type, cause, arguments);
	}
}
