package se.l4.exofind.engine.query.matchers;

/**
 * Match values that are equal to a given value.
 *
 * Equality is judged the same way the field was written, so a string field
 * that ignores case when filtering also ignores it here.
 *
 * @param value
 *   the value to look for, of whatever type the field holds
 */
public record EqualsMatcher(Object value) implements Matcher {
	@Override
	public String id() {
		return "equals";
	}
}
