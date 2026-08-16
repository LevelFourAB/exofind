package se.l4.exofind.engine.query.matchers;

/**
 * Match documents that have any value for the field at all, which is how
 * documents missing it are told apart from the rest.
 */
public record AnyMatcher() implements Matcher {
	public static final AnyMatcher INSTANCE = new AnyMatcher();

	@Override
	public String id() {
		return "any";
	}
}
