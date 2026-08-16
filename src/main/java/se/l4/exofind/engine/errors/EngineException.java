package se.l4.exofind.engine.errors;

import org.eclipse.collections.api.map.MapIterable;

/**
 * EngineException is the root of exceptions for the engine, based around the
 * concept of an error type. These exceptions will always have a
 * machine-readable code and arguments combined with a human readable message.
 */
public class EngineException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final ErrorType type;
	private final MapIterable<String, Object> arguments;

	public EngineException(
		ErrorType type,
		MapIterable<String, Object> arguments
	) {
		super(type.format(arguments));

		this.type = type;
		this.arguments = arguments;
	}

	public EngineException(ErrorType type, Object... arguments) {
		this(type, ErrorType.toArguments(arguments));
	}

	public EngineException(
		ErrorType type,
		MapIterable<String, Object> arguments,
		Throwable cause
	) {
		super(type.format(arguments), cause);

		this.type = type;
		this.arguments = arguments;
	}

	public EngineException(ErrorType type, Throwable cause, Object... arguments) {
		this(type, ErrorType.toArguments(arguments), cause);
	}

	public String getCode() {
		return type.getCode();
	}

	public MapIterable<String, Object> getArguments() {
		return arguments;
	}
}
