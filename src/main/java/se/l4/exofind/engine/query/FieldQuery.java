package se.l4.exofind.engine.query;

import se.l4.exofind.engine.query.matchers.Matcher;

/**
 * Match documents by what a single field holds.
 *
 * The field says which values it can be asked about - a matcher the type of
 * the field has no meaning for is rejected rather than answered with nothing.
 *
 * @param field
 *   name of the field, as it is called in the definition of the index
 * @param matcher
 *   what to look for in it
 */
public record FieldQuery(String field, Matcher matcher) implements Query {
	@Override
	public String type() {
		return "field";
	}

	@Override
	public boolean scores() {
		return false;
	}
}
