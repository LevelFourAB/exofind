package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Reorders the best results of a search in a second pass without changing which
 * documents matched.
 *
 * <pre>
 * {
 *   "window": 200,
 *   "boost": [ { "field": "brand", "equals": "adidas" } ],
 *   "signals": [ { "field": "purchases", "saturation": { "pivot": 50 } } ],
 *   "weight": 0.5
 * }
 * </pre>
 *
 * <p>The first pass ranks every match by relevance. The best {@code window}
 * results of that pass are scored again by the configured boosts and signals,
 * and the scores are combined. Results below the window keep their first-pass
 * relevance score, so rescoring cannot promote irrelevant documents onto the
 * first page.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	Reorders the best results of a search in a second pass without changing \
	which documents matched. Boosts and signals apply only inside the window, \
	reordering relevant results without promoting non-matching documents. \
	Applies only when results are ordered by relevance; providing an explicit \
	`sort` overrides rescoring. See \
	[Rescoring](https://exofind.dev/reference/search-api/#rescoring).""")
public record Rescore(
	/**
	 * Number of best results to score a second time.
	 */
	@Schema(
		description = """
			Number of best results to score a second time. Must be at least \
			`offset` plus `limit`, and at most \
			`EXOFIND_SEARCH_MAX_RESCORE_WINDOW`.""",
		required = true,
		examples = "200"
	)
	Integer window,

	/**
	 * Clauses that lift results satisfying them without filtering or narrowing
	 * search hits.
	 */
	@Schema(description = """
		Clauses that lift results satisfying them. Clauses do not filter or \
		narrow search hits; a result that satisfies none of them keeps its \
		first-pass score. Wrap a clause in `boost` to weigh it against the \
		others.""")
	List<Clause> boost,

	/**
	 * Document values evaluated in the second-pass score. These are the whole
	 * of what the second pass reads; ranking configured on the index is not
	 * applied again.
	 */
	@Schema(description = """
		Document values taken into the second score, written the same way as \
		top-level `signals`. Applied to every result in the window. The \
		ranking configured on the index belongs to the first pass and is not \
		applied again here, so `signalsMode` does not affect these.""")
	List<Signal> signals,

	/**
	 * Multiplier applied to the second-pass score before adding it to the
	 * first-pass score. Left out for 1.
	 */
	@Schema(
		description = """
			Multiplier applied to the second-pass score before adding it to \
			the first-pass score.""",
		defaultValue = "1"
	)
	Float weight
) {
}
