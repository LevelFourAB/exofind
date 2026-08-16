package se.l4.exofind.engine.query.matchers;

/**
 * Match values that start with a given prefix.
 *
 * The prefix is compared against the whole value rather than the words inside
 * it, which is what makes it useful for identifiers and paths. Completing what
 * someone is typing into a search box is {@link TextMatcher} instead.
 *
 * @param value
 *   the prefix the value has to start with
 */
public record PrefixMatcher(String value) implements Matcher {
	@Override
	public String id() {
		return "prefix";
	}
}
