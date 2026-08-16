package se.l4.exofind.engine.query.matchers;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * Match values that are equal to any one of several values.
 *
 * This is what a list of ticked checkboxes turns into, so an empty list is
 * allowed and matches nothing - a filter nobody has picked a value in is the
 * caller's to leave out.
 *
 * @param values
 *   the values to look for
 */
public record InMatcher(ImmutableList<Object> values) implements Matcher {
	public InMatcher {
		if(values == null) {
			values = Lists.immutable.empty();
		}
	}

	@Override
	public String id() {
		return "in";
	}

	/**
	 * Match any of the given values.
	 *
	 * @param values
	 * @return
	 */
	public static InMatcher of(Object... values) {
		return new InMatcher(Lists.immutable.of(values));
	}

	/**
	 * Match any of the given values.
	 *
	 * @param values
	 * @return
	 */
	public static InMatcher of(Iterable<?> values) {
		return new InMatcher(Lists.immutable.<Object>ofAll(values));
	}
}
