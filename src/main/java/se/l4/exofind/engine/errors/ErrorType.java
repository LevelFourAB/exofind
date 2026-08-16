package se.l4.exofind.engine.errors;

import java.util.Objects;

import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.set.ImmutableSet;

/**
 * ErrorType defines a type of error with machine-readable code and
 * arguments, as well as a human-readable message.
 * 
 * Example:
 * 
 * <pre>
 * var type = ErrorType.withCode("error.code")
 * 	.withArguments("arg1", "arg2")
 * 	.withMessage("This is an error with {{arg1}} and {{arg2}}");
 * </pre>
 * 
 * To turn into a message:
 * 
 * <pre>
 * var message = type.toMessage(Location.code(), "arg1", "value1", "arg2", "value2");
 * </pre>
 */
public class ErrorType {
	private final String code;
	private final ImmutableSet<String> arguments;
	private final String message;

	private ErrorType(
		String code,
		ImmutableSet<String> arguments,
		String message
	) {
		this.code = code;
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
		return new ErrorType(code, Sets.immutable.empty(), "");
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
		return new ErrorType(code, Sets.immutable.of(arguments), message);
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
		return new ErrorType(code, arguments, message);
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
		return Objects.hash(code, arguments, message);
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) return true;
		if(obj == null) return false;
		if(getClass() != obj.getClass()) return false;
		ErrorType other = (ErrorType) obj;
		return Objects.equals(code, other.code) && Objects.equals(arguments, other.arguments)
			&& Objects.equals(message, other.message);
	}

	@Override
	public String toString() {
		return "ErrorType{code=" + code + ", arguments=" + arguments + ", message=" + message + "}";
	}
}
