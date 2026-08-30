package se.l4.exofind.engine.api.v1alpha1.search;

/**
 * How much one search may ask a node to do.
 *
 * <p>Every number here bounds work that a single request decides the size of:
 * how many results are ranked, how far a neighbour or fusion search reads, and
 * how large the tree of clauses may grow. A deployment configures each one
 * under {@code exofind.search}.
 *
 * <p>These caps apply to the request as it arrives. The time budget in
 * {@link se.l4.exofind.engine.index.SearchDeadline} bounds what a search costs
 * once it runs.
 *
 * @param maxLimit
 *   how many results one page may hold
 * @param maxPageDepth
 *   how deep into the results offset paging may reach
 * @param maxRescoreWindow
 *   how many results a second pass may reach
 * @param maxKnnK
 *   how many neighbours one {@code knn} clause may ask for
 * @param maxFuseDepth
 *   how far down each ranking of a {@code fuse} clause is read
 * @param maxClauses
 *   how many clauses one request may hold, counted across the whole body
 * @param maxClauseDepth
 *   how deeply clauses may be nested inside each other
 */
public record SearchLimits(
	int maxLimit,
	int maxPageDepth,
	int maxRescoreWindow,
	int maxKnnK,
	int maxFuseDepth,
	int maxClauses,
	int maxClauseDepth
) {
	/*
	 * The defaults are text because the configuration annotations carry them
	 * too, and an annotation takes a constant string. Reading both from one
	 * constant keeps the default a node runs with and the default a test
	 * asserts on from drifting apart.
	 */

	/** Default for {@link #maxLimit()}. */
	public static final String DEFAULT_MAX_LIMIT = "1000";

	/** Default for {@link #maxPageDepth()}. */
	public static final String DEFAULT_MAX_PAGE_DEPTH = "10000";

	/** Default for {@link #maxRescoreWindow()}. */
	public static final String DEFAULT_MAX_RESCORE_WINDOW = "1000";

	/** Default for {@link #maxKnnK()}. */
	public static final String DEFAULT_MAX_KNN_K = "1000";

	/** Default for {@link #maxFuseDepth()}. */
	public static final String DEFAULT_MAX_FUSE_DEPTH = "1000";

	/** Default for {@link #maxClauses()}. */
	public static final String DEFAULT_MAX_CLAUSES = "1024";

	/** Default for {@link #maxClauseDepth()}. */
	public static final String DEFAULT_MAX_CLAUSE_DEPTH = "20";

	public SearchLimits {
		require("exofind.search.max-limit", maxLimit);
		require("exofind.search.max-page-depth", maxPageDepth);
		require("exofind.search.max-rescore-window", maxRescoreWindow);
		require("exofind.search.max-knn-k", maxKnnK);
		require("exofind.search.max-fuse-depth", maxFuseDepth);
		require("exofind.search.max-clauses", maxClauses);
		require("exofind.search.max-clause-depth", maxClauseDepth);
	}

	private static void require(String setting, int value) {
		if(value < 1) {
			throw new IllegalArgumentException(
				setting + " has to be at least 1, a node that answers no search serves nothing"
			);
		}
	}

	/**
	 * The limits a node runs with when nothing is configured.
	 */
	public static SearchLimits defaults() {
		return new SearchLimits(
			Integer.parseInt(DEFAULT_MAX_LIMIT),
			Integer.parseInt(DEFAULT_MAX_PAGE_DEPTH),
			Integer.parseInt(DEFAULT_MAX_RESCORE_WINDOW),
			Integer.parseInt(DEFAULT_MAX_KNN_K),
			Integer.parseInt(DEFAULT_MAX_FUSE_DEPTH),
			Integer.parseInt(DEFAULT_MAX_CLAUSES),
			Integer.parseInt(DEFAULT_MAX_CLAUSE_DEPTH)
		);
	}

	public SearchLimits withMaxLimit(int maxLimit) {
		return new SearchLimits(
			maxLimit, maxPageDepth, maxRescoreWindow, maxKnnK, maxFuseDepth, maxClauses,
			maxClauseDepth
		);
	}

	public SearchLimits withMaxPageDepth(int maxPageDepth) {
		return new SearchLimits(
			maxLimit, maxPageDepth, maxRescoreWindow, maxKnnK, maxFuseDepth, maxClauses,
			maxClauseDepth
		);
	}

	public SearchLimits withMaxRescoreWindow(int maxRescoreWindow) {
		return new SearchLimits(
			maxLimit, maxPageDepth, maxRescoreWindow, maxKnnK, maxFuseDepth, maxClauses,
			maxClauseDepth
		);
	}

	public SearchLimits withMaxKnnK(int maxKnnK) {
		return new SearchLimits(
			maxLimit, maxPageDepth, maxRescoreWindow, maxKnnK, maxFuseDepth, maxClauses,
			maxClauseDepth
		);
	}

	public SearchLimits withMaxFuseDepth(int maxFuseDepth) {
		return new SearchLimits(
			maxLimit, maxPageDepth, maxRescoreWindow, maxKnnK, maxFuseDepth, maxClauses,
			maxClauseDepth
		);
	}

	public SearchLimits withMaxClauses(int maxClauses) {
		return new SearchLimits(
			maxLimit, maxPageDepth, maxRescoreWindow, maxKnnK, maxFuseDepth, maxClauses,
			maxClauseDepth
		);
	}

	public SearchLimits withMaxClauseDepth(int maxClauseDepth) {
		return new SearchLimits(
			maxLimit, maxPageDepth, maxRescoreWindow, maxKnnK, maxFuseDepth, maxClauses,
			maxClauseDepth
		);
	}
}
