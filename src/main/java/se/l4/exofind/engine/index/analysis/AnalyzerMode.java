package se.l4.exofind.engine.index.analysis;

/**
 * Which side of a search an analyzer is being built for.
 *
 * The two are not always the same chain. Autocomplete indexes every prefix of a
 * value so that what a user has typed so far can be looked up as a term, but
 * the text they typed must not be cut into prefixes again - doing so on both
 * sides would match far more than what was asked for.
 */
public enum AnalyzerMode {
	/**
	 * Analyzing a value on its way into the index.
	 */
	INDEXING,

	/**
	 * Analyzing the text a user is searching for.
	 */
	QUERYING
}
