package se.l4.exofind.engine.errors;

import java.util.regex.Pattern;

/**
 * Variant of {@link Location} to simplify creating locations that
 * describe a position in an object.
 *
 * <p>A location reads as a path through the request: names joined by {@code .},
 * and one element of a list as {@code [n]}, so {@code name} of the third
 * document of a bulk request reads {@code documents[2].name}. The API answers
 * with this form as the {@code path} of an error, and a query addresses a field
 * with the same form, so a path an error names goes into a request as it is.
 *
 * <p>A field name and a property of a request hold only the characters of
 * {@link #PLAIN_SEGMENT} and stand as they are. A key of a map the caller
 * fills, such as the metadata of an index, may hold a dot or a bracket and goes
 * in brackets and double quotes, as {@code metadata["build.sha"]}. Use
 * {@link #forKey(String)} for a key of such a map and {@link #forField(String)}
 * for a name.
 */
public interface ObjectLocation
	extends Location {
	/** The characters a segment carries without quoting. */
	Pattern PLAIN_SEGMENT = Pattern.compile("[a-zA-Z0-9_*]+");

	/**
	 * Create a new location that describes a field in the current object. The
	 * name goes in as it stands, so pass a name the engine holds to
	 * {@link #PLAIN_SEGMENT} or a path already in this form. A key whose
	 * characters the caller chose goes through {@link #forKey(String)}.
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
	 * Create a new location that describes one key of a map in the current
	 * object. A key outside {@link #PLAIN_SEGMENT} goes in brackets and double
	 * quotes, so a key holding a dot stays one segment of the path.
	 *
	 * @param key
	 * @return
	 */
	default ObjectLocation forKey(String key) {
		if(PLAIN_SEGMENT.matcher(key).matches()) {
			return forField(key);
		}

		return () -> describe() + '[' + '"' + escape(key) + '"' + ']';
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

	/**
	 * Escape the two characters a quoted segment cannot carry as themselves.
	 */
	private static String escape(String key) {
		return key.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
