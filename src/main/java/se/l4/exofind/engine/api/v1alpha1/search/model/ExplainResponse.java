package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Explains how a specific document or value hit scores for a search query.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = """
		Explains how a specific document or value hit scores for a search query. \
		See [Explaining a \
		result](https://exofind.dev/reference/search-api/#explaining-a-result).""",
	examples = ExplainResponse.EXAMPLE
)
public record ExplainResponse(
	/**
	 * Whether the hit satisfies the search.
	 */
	@Schema(description = """
		Whether the hit satisfies the search. A hit that does not match \
		appears in no search results.""")
	boolean matched,

	/**
	 * The relevance score of the hit, or 0 if the hit does not match.
	 */
	@Schema(
		description = """
			The relevance score of the hit. Returns `0` if the hit does not \
			match.""",
		examples = "7.42"
	)
	float score,

	/**
	 * Root score step explaining how the score was calculated.
	 */
	@Schema(description = "Root score step explaining how the score was calculated.")
	Detail detail,

	/**
	 * Relaxation details containing dropped words and the effective query text,
	 * or null if query relaxation did not run.
	 */
	@Schema(description = """
		Relaxation details containing dropped words and the effective query \
		text. Omitted if query relaxation did not run. When zero results \
		trigger query relaxation, the explanation tree reflects the relaxed \
		query that executed.""")
	SearchResponse.Relaxed relaxed,

	/**
	 * The filters the search read out of the query text, or null if nothing
	 * was read.
	 */
	@Schema(description = """
		What the search read out of the query text as filters, and the text \
		that was left. Omitted when nothing was read. The explanation tree \
		reflects the search with the filters in it.""")
	SearchResponse.Interpreted interpreted
) {
	/**
	 * The example response, as the JSON the engine answers with. It explains
	 * the text clause of the request under {@link SearchRequest#EXAMPLE}.
	 */
	public static final String EXAMPLE = """
		{
		  "matched": true,
		  "score": 8.42,
		  "detail": {
		    "matched": true,
		    "score": 8.42,
		    "description": "sum of:",
		    "children": [
		      {
		        "matched": true,
		        "score": 8.42,
		        "description": "weight(name:spring) [BM25], result of:",
		        "clause": "query[0]",
		        "clauseType": "text",
		        "field": "name",
		        "usage": "matching"
		      }
		    ]
		  }
		}""";

	/**
	 * One score step in the explanation tree.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(name = "ExplainDetail", description = "One score step in the explanation tree.")
	public record Detail(
		/**
		 * Whether this step was satisfied.
		 */
		@Schema(description = """
			Whether this step was satisfied. A non-matching step contributes \
			nothing to the parent score, and its `children` describe the \
			reason.""")
		boolean matched,

		/**
		 * Score contributed by this step to its parent step.
		 */
		@Schema(description = """
			Score contributed by this step to its parent step. Returns 0 if \
			the step did not match.""")
		float score,

		/**
		 * Human-readable explanation of the step.
		 */
		@Schema(
			description = "Human-readable explanation of the step.",
			examples = "weight(title:bok) [BM25], result of:"
		)
		String description,

		/**
		 * Path to the clause in the request body that produced this step, or
		 * null when the step is not an individual clause.
		 */
		@Schema(
			description = """
				Path to the clause in the request body that produced this \
				step. Omitted when the step is not an individual clause.""",
			examples = "query[0].clauses[2]"
		)
		String clause,

		/**
		 * Clause type matching request syntax, or null when {@code clause} is
		 * omitted.
		 */
		@Schema(
			description = """
				Clause type matching request syntax in `type`. Omitted when \
				`clause` is omitted.""",
			examples = "text"
		)
		String clauseType,

		/**
		 * Index definition field name evaluated by the step, or null when the
		 * step reads no fields or multiple fields.
		 */
		@Schema(
			description = """
				Index definition field name evaluated by the step. Omitted \
				when the step reads no fields or multiple fields.""",
			examples = "title"
		)
		String field,

		/**
		 * Field usage mode evaluated by the step, or null when {@code field} is
		 * omitted.
		 */
		@Schema(
			description = """
				Field usage mode evaluated by the step (such as `matching` or \
				`filter`). Omitted when `field` is omitted.""",
			examples = "matching"
		)
		String usage,

		/**
		 * BCP 47 tag of the field variant evaluated by this step. Omitted for
		 * fields that store a single variant across all languages.
		 */
		@Schema(
			description = """
				BCP 47 tag of the variant this step reads. Omitted for a field \
				that holds one variant for every language.""",
			examples = "sv"
		)
		String locale,

		/**
		 * Child score steps that compose this step. Empty for leaf steps.
		 */
		@Schema(description = "Child score steps that compose this step. Empty for leaf steps.")
		List<Detail> children
	) {
	}
}
