package se.l4.exofind.engine.errors;

import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.map.MapIterable;

/**
 * EngineException is the root of exceptions for the engine, based around the
 * concept of an error type. These exceptions will always have a
 * machine-readable code and arguments combined with a human readable message.
 */
public class EngineException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final ErrorType type;
	private volatile MapIterable<String, Object> arguments;

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

	/**
	 * Get the HTTP status this exception is answered with, which is the status
	 * of its type.
	 *
	 * @return
	 */
	public int getStatus() {
		return type.getStatus();
	}

	public MapIterable<String, Object> getArguments() {
		return arguments;
	}

	/**
	 * Add arguments that say where the failure stopped a larger operation,
	 * such as the position in a batch and how much of the batch was applied.
	 * An argument the exception already holds keeps its value. The message
	 * does not change.
	 *
	 * @param more
	 *   the arguments to add
	 * @return
	 *   this exception, so a caller can throw the result
	 */
	public EngineException withArguments(MapIterable<String, Object> more) {
		var all = Maps.mutable.<String, Object>empty();
		more.forEachKeyValue(all::put);
		arguments.forEachKeyValue(all::put);
		this.arguments = all;
		return this;
	}
}
