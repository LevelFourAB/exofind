package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A second pass over the best results of a search, as it is written on the
 * wire.
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
 * The first pass ranks every match by relevance. Its best {@code window}
 * results are scored again by the boosts and signals here, and the two scores
 * are added. Results below the window keep the order relevance gave them, so
 * nothing here can pull a poor match onto the first page.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	Reorders the best results of a search in a second pass, leaving which \
	documents matched untouched. Boosts and signals here apply only inside \
	the window, so they reorder relevant results without promoting \
	irrelevant ones. Applies only when results are ordered by relevance, so \
	an explicit `sort` overrides it. See \
	[Rescoring](https://exofind.dev/reference/search-api/#rescoring).""")
public record Rescore(
	/**
	 * How many of the best results take part.
	 */
	@Schema(
		description = """
			How many of the best results are scored a second time. Must be at \
			least as large as `offset` plus `limit`, and at most \
			`EXOFIND_SEARCH_MAX_RESCORE_WINDOW`.""",
		required = true,
		examples = "200"
	)
	Integer window,

	/**
	 * The clauses that lift what satisfies them. Nothing here narrows.
	 */
	@Schema(description = """
		Clauses that lift the results satisfying them. Nothing here narrows \
		a search: a result that satisfies none of them keeps its first-pass \
		score. Wrap a clause in `boost` to weigh it against the others.""")
	List<Clause> boost,

	/**
	 * The values of the documents themselves to take into the second score.
	 * These are the whole of what the second pass reads, so the ranking of the
	 * index is never applied again here.
	 */
	@Schema(description = """
		Document values taken into the second score, written the same way as \
		top-level `signals`. Applied to every result in the window. The \
		ranking configured on the index belongs to the first pass and is not \
		applied again here, so `signalsMode` does not affect these.""")
	List<Signal> signals,

	/**
	 * How much the second score counts against the first. Left out for 1.
	 */
	@Schema(
		description = "How much the second score counts against the first.",
		defaultValue = "1"
	)
	Float weight
) {
}
