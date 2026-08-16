package se.l4.exofind.engine.index;

import java.util.ArrayList;
import java.util.List;

/**
 * Document that can be indexed via {@link Index}.
 *
 * A value given to a field the definition declares as an object is itself a
 * {@code Document}, holding the fields of that value by the names inside the
 * object.
 */
public record Document(Value... fields) {
	public record Value(String name, Object value, String locale) {
		public Value(String name, Object value) {
			this(name, value, null);
		}
	}

	/**
	 * Get the first value given for a field. Enough for a field that holds a
	 * single value; one declared multiple reads back through
	 * {@link #getAll(String)}.
	 *
	 * @param name
	 * @return
	 *   the first value, or {@code null} when the document has none
	 */
	public Object get(String name) {
		for(var field : fields) {
			if(field.name.equals(name)) {
				return field.value;
			}
		}

		return null;
	}

	/**
	 * Get every value given for a field, in the order they were given.
	 *
	 * @param name
	 * @return
	 *   the values, empty when the document has none
	 */
	public List<Object> getAll(String name) {
		var result = new ArrayList<Object>();
		for(var field : fields) {
			if(field.name.equals(name)) {
				result.add(field.value);
			}
		}

		return result;
	}
}
