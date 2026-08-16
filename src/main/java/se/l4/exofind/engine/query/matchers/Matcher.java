package se.l4.exofind.engine.query.matchers;

/**
 * What a query looks for in the value of a single field.
 *
 * A matcher says what to look for and never how to find it. Turning one into
 * something that can be run is the job of the type of the field it is used on,
 * which is also what decides whether the matcher means anything for that type
 * at all - a boolean has nothing a prefix could match.
 *
 * The identifier is the name a matcher goes by outside the engine, so it is
 * part of what callers write and is never renamed or reused.
 */
public interface Matcher {
	/**
	 * Get the unique identifier for the matcher.
	 *
	 * @return
	 */
	String id();
}
