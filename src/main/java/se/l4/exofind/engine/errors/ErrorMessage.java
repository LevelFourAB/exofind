package se.l4.exofind.engine.errors;

import java.util.Objects;

import org.eclipse.collections.api.map.MapIterable;

/**
 * ErrorMessage describes an error that has occurred, such as a validation
 * error, but includes information that makes it easier to use for both
 * machine and human consumption.
 * 
 * Messages are usually created using {@link ErrorType}.
 */
public class ErrorMessage {
	private final ErrorType type;
	private final Location location;
	private final MapIterable<String, Object> arguments;

	ErrorMessage(
		ErrorType type,
		Location location,
		MapIterable<String, Object> arguments
	) {
		this.type = type;
		this.location = location;
		this.arguments = arguments;
	}

	public Location getLocation() {
		return location;
	}

	/**
	 * Get this message as it would read somewhere else, keeping what went
	 * wrong and changing only where it is said to have happened. Used when a
	 * message travels out of the thing it was raised about and into something
	 * larger - a document refused by the index becomes one of several in the
	 * request that carried it, and the caller has to be told which.
	 *
	 * @param location
	 * @return
	 *   a message with the same code and arguments, at the given location
	 */
	public ErrorMessage at(Location location) {
		return new ErrorMessage(type, location, arguments);
	}

	public String getMessage() {
		return type.format(arguments);
	}

	public String getCode() {
		return type.getCode();
	}

	public MapIterable<String, Object> getArguments() {
		return arguments;
	}

	public String format() {
		return location.describe() + ": " + type.getCode() + ": " + type.format(arguments);
	}

	@Override
	public int hashCode() {
		return Objects.hash(arguments, type, location);
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) return true;
		if(obj == null) return false;
		if(getClass() != obj.getClass()) return false;
		ErrorMessage other = (ErrorMessage) obj;
		return Objects.equals(type, other.type)
			&& Objects.equals(location, other.location)
			&& Objects.equals(arguments, other.arguments);
	}

	@Override
	public String toString() {
		return "ErrorMessage{"
			+ "type=" + type
			+ ", arguments=" + arguments
			+ ", location=" + location
			+ "}";
	}
}
