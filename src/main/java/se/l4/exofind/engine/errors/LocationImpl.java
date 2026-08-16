package se.l4.exofind.engine.errors;

import java.util.Objects;

public class LocationImpl implements Location {
	public static final Location UNKNOWN = new LocationImpl("Unknown Location");

	private static final ThreadLocal<Location> CURRENT = new ThreadLocal<>();

	private final String message;

	public LocationImpl(String message) {
		this.message = message;
	}

	@Override
	public String toString() {
		return message;
	}

	@Override
	public String describe() {
		return message;
	}

	@Override
	public int hashCode() {
		return Objects.hash(message);
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) return true;
		if(obj == null) return false;
		if(getClass() != obj.getClass()) return false;
		LocationImpl other = (LocationImpl) obj;
		return Objects.equals(message, other.message);
	}

	public static Location code() {
		StackTraceElement[] trace = Thread.currentThread().getStackTrace();
		for(int i = 0; i < trace.length; i++) {
			String module = trace[i].getModuleName();
			String className = trace[i].getClassName();
			if(
				!"java.base".equals(module) && !className.contains("se.l4.exofind.engine.errors")
			) {
				return new LocationImpl(trace[i].toString());
			}
		}

		return LocationImpl.UNKNOWN;
	}

	public static Location automatic(Location picked) {
		if(picked != null) {
			return picked;
		}

		var current = CURRENT.get();
		return current == null ? code() : current;
	}

	/**
	 * Scope operations on the current thread to the given location. Should be
	 * used with a try-with-resources statement.
	 *
	 * <pre>
	 * try(var handle = Location.scope(currentLocation)) {
	 *    ...
	 * }
	 * </pre>
	 *
	 * @param location
	 * @return
	 */
	public static Handle scope(Location location) {
		var current = CURRENT.get();
		CURRENT.set(location);

		return () -> {
			if(current != null) {
				CURRENT.set(current);
			} else {
				CURRENT.remove();
			}
		};
	}
}
