package se.l4.exofind.engine.query.matchers;

/**
 * Match values that sit at or below a given path of a tree.
 *
 * What a category filter ticks: choosing {@code Men/Shoes} keeps everything
 * filed under it, however many levels further down it sits, and the path
 * itself. Only a field whose values are read as paths can answer it, as the
 * levels a value passes through have to have been written for anything to
 * match.
 *
 * Levels are matched whole, which is what tells this from
 * {@link PrefixMatcher}: {@code Men/Sho} is not a level, so it finds nothing
 * here while a prefix would have matched the shoes.
 *
 * @param path
 *   the path to match at and below, written the way the values are - the
 *   levels separated by the separator the field declares
 */
public record UnderMatcher(String path) implements Matcher {
	@Override
	public String id() {
		return "under";
	}
}
