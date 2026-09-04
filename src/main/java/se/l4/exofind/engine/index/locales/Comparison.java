package se.l4.exofind.engine.index.locales;

/**
 * Which side of a number a comparative word puts the values on.
 *
 * A locale names its comparative words in {@link Comparatives}, and a search
 * that reads {@code under 100} turns the word into one of these and the
 * number into the bound.
 */
public enum Comparison {
	/**
	 * Values below the number, not the number itself - {@code under 100}.
	 */
	BELOW,

	/**
	 * Values up to and including the number - {@code max 100}.
	 */
	AT_MOST,

	/**
	 * Values above the number, not the number itself - {@code over 100}.
	 */
	ABOVE,

	/**
	 * Values from the number and upwards - {@code at least 100}.
	 */
	AT_LEAST
}
