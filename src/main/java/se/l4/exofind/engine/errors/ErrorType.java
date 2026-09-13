package se.l4.exofind.engine.errors;

import java.util.Objects;

import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.set.ImmutableSet;

/**
 * ErrorType defines a type of error with a machine-readable code, the HTTP
 * status the code is answered with, its arguments, and a human-readable
 * message.
 *
 * <p>Example:
 *
 * <pre>
 * var type = ErrorType.withCode("search:facet:limit_out_of_range")
 * 	.withStatus(400)
 * 	.withArguments("max")
 * 	.withMessage("A facet brings back between 1 and {{max}} values");
 * </pre>
 *
 * <p>To turn into a message:
 *
 * <pre>
 * var message = type.toMessage(Location.code(), "max", 1000);
 * </pre>
 *
 * <p>The status is the one the code answers with as the {@code code} of the
 * response. A code carried inside the {@code errors} of a validation failure is
 * answered with the status of the envelope instead. A type declared without a
 * status is answered with 500, because an unstated status is a failure of the
 * node rather than of the request.
 *
 * <p>A code is part of the API: {@code docs/reference/errors.md} states the
 * grammar a new code follows and the words its last segment is built from.
 */
public class ErrorType {
	/** The status of a type that does not state one. */
	public static final int UNSTATED_STATUS = 500;

	private final String code;
	private final int status;
	private final ImmutableSet<String> arguments;
	private final String message;

	private ErrorType(
		String code,
		int status,
		ImmutableSet<String> arguments,
		String message
	) {
		this.code = code;
		this.status = status;
		this.arguments = arguments;
		this.message = message;
	}

	/**
	 * Get the machine-readable code for this message.
	 * 
	 * @return
	 */
	public String getCode() {
		return code;
	}

	/**
	 * Change the code of this message.
	 * 
	 * @param code
	 * @return
	 */
	public static ErrorType withCode(String code) {
		return new ErrorType(code, UNSTATED_STATUS, Sets.immutable.empty(), "");
	}

	/**
	 * Get the HTTP status this type is answered with.
	 *
	 * @return
	 */
	public int getStatus() {
		return status;
	}

	/**
	 * Change the HTTP status this type is answered with.
	 *
	 * @param status
	 * @return
	 */
	public ErrorType withStatus(int status) {
		return new ErrorType(code, status, arguments, message);
	}

	/**
	 * Get the arguments this type of message expects.
	 * 
	 * @return
	 */
	public ImmutableSet<String> getArguments() {
		return arguments;
	}

	/**
	 * Change the arguments of this message.
	 * 
	 * @param arguments
	 * @return
	 */
	public ErrorType withArguments(String... arguments) {
		return new ErrorType(code, status, Sets.immutable.of(arguments), message);
	}

	/**
	 * Get the message, including placeholders.
	 * 
	 * @return
	 */
	public String getMessage() {
		return message;
	}

	/**
	 * Change the message of this type.
	 * 
	 * @param message
	 * @return
	 */
	public ErrorType withMessage(String message) {
		return new ErrorType(code, status, arguments, message);
	}

	/**
	 * Format a human-readable message using the provided arguments.
	 * 
	 * @param arguments
	 * @return
	 */
	public String format(MapIterable<String, Object> arguments) {
		String result = message;

		for(var e : arguments.keyValuesView()) {
			result = result.replace("{{" + e.getOne() + "}}", String.valueOf(e.getTwo()));
		}

		return result;
	}

	/**
	 * Create a new {@link ErrorMessage} using this type.
	 * 
	 * @param location location of the error
	 * @param arguments arguments as key-value pairs
	 * @return
	 */
	public ErrorMessage toMessage(Location location, MapIterable<String, Object> arguments) {
		return new ErrorMessage(this, location, arguments);
	}

	/**
	 * Create a new {@link ErrorMessage} using this type.
	 * 
	 * @param location location of the error
	 * @param arguments arguments as key-value pairs
	 * @return
	 */
	public ErrorMessage toMessage(Location location, Object... arguments) {
		return toMessage(location, toArguments(arguments));
	}

	public static MapIterable<String, Object> toArguments(Object... arguments) {
		if(arguments.length % 2 != 0) {
			throw new IllegalArgumentException("Arguments must be key-value pairs");
		}

		var argumentsMap = Maps.mutable.<String, Object>empty();
		for(int i = 0; i < arguments.length; i += 2) {
			argumentsMap.put(String.valueOf(arguments[i]), arguments[i + 1]);
		}

		return argumentsMap;
	}

	@Override
	public int hashCode() {
		return Objects.hash(code, status, arguments, message);
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) return true;
		if(obj == null) return false;
		if(getClass() != obj.getClass()) return false;
		ErrorType other = (ErrorType) obj;
		return Objects.equals(code, other.code) && status == other.status
			&& Objects.equals(arguments, other.arguments)
			&& Objects.equals(message, other.message);
	}

	@Override
	public String toString() {
		return "ErrorType{code=" + code + ", status=" + status + ", arguments=" + arguments + ", message=" + message + "}";
	}
}
