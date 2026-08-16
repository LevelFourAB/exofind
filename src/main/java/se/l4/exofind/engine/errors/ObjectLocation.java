package se.l4.exofind.engine.errors;

/**
 * Variant of {@link Location} to simplify creating locations that
 * describe a position in an object.
 */
public interface ObjectLocation
	extends Location {
	/**
	 * Create a new location that describes a field in the current object.
	 * 
	 * @param name
	 * @return
	 */
	default ObjectLocation forField(String name) {
		return () -> {
			var current = describe();
			return current.isEmpty() ? name : current + '.' + name;
		};
	}

	/**
	 * Create a new location that describes an index in the current object
	 * and field.
	 * 
	 * @param idx
	 * @return
	 */
	default ObjectLocation forIndex(int idx) {
		return () -> describe() + '[' + idx + ']';
	}

	/**
	 * Create a new location that describes the root of an object.
	 * 
	 * @return
	 */
	static ObjectLocation root() {
		return () -> "";
	}
}
