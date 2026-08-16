package se.l4.exofind.engine.api.errors;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when state an index holds can not be described by the version of the
 * API handling the request.
 *
 * The state is valid and the request is well formed - they belong to different
 * versions. Reading such an index is refused rather than answered with a
 * partial definition, and updating it is refused rather than rewriting it with
 * the parts that were left out dropped. Both are answered as a conflict, and
 * neither is fixed by changing the request; an API version that knows the state
 * is what serves it.
 */
public class UnrepresentableStateException extends EngineException {
	private static final long serialVersionUID = 1L;

	public UnrepresentableStateException(ErrorType type, Object... arguments) {
		super(type, arguments);
	}

	public UnrepresentableStateException(
		ErrorType type,
		Throwable cause,
		Object... arguments
	) {
		super(type, cause, arguments);
	}
}
