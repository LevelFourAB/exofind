package se.l4.exofind.engine.index;

import org.eclipse.collections.api.map.MapIterable;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;

/**
 * IndexException is the root of exceptions for the index.
 */
public class IndexException extends EngineException {
	public IndexException(ErrorType type, MapIterable<String, Object> arguments) {
		super(type, arguments);
	}

	public IndexException(ErrorType type, Object... arguments) {
		super(type, arguments);
	}

	public IndexException(ErrorType type, MapIterable<String, Object> arguments, Throwable cause) {
		super(type, arguments, cause);
	}

	public IndexException(ErrorType type, Throwable cause, Object... arguments) {
		super(type, cause, arguments);
	}
}
