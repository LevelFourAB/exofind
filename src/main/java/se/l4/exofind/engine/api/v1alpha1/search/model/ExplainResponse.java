package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How one hit scores under a search, as it is answered over the API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	How one hit scores under a search. See \
	[Explaining a result](https://levelfourab.github.io/exofind/reference/search-api/#explaining-a-result).""")
public record ExplainResponse(
	/**
	 * Whether the hit satisfies the search.
	 */
	@Schema(description = """
		Whether the hit satisfies the search. A hit that does not appears in no \
		result page, whatever the steps below scored.""")
	boolean matched,

	/**
	 * What the hit scored, zero when it matched nothing.
	 */
	@Schema(
		description = """
			What the hit scored, `0` when it matched nothing. The same number a \
			search reports for the hit.""",
		examples = "7.42"
	)
	float score,

	/**
	 * How the score was arrived at.
	 */
	@Schema(description = "How the score was arrived at.")
	Detail detail,

	/**
	 * What the search let go of to match anything, left out when it let go of
	 * nothing.
	 */
	@Schema(description = """
		What the search let go of to match anything. Omitted when the query was \
		not relaxed. A search that matches nothing drops words and runs again, \
		and what is explained is the search that ran.""")
	SearchResponse.Relaxed relaxed
) {
	/**
	 * One step of a score.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(name = "ExplainDetail", description = "One step of a score.")
	public record Detail(
		/**
		 * Whether this step was satisfied.
		 */
		@Schema(description = """
			Whether this step was satisfied. A step that was not contributes \
			nothing, and its `children` say why.""")
		boolean matched,

		/**
		 * What this step contributed to the step above it.
		 */
		@Schema(description = "What this step contributed to the step above it.")
		float score,

		/**
		 * What the step is, in words.
		 */
		@Schema(
			description = "What the step is, in words.",
			examples = "weight(title:bok) [BM25], result of:"
		)
		String description,

		/**
		 * Path of the clause this step was compiled from, left out for a step
		 * that is not a clause of its own.
		 */
		@Schema(
			description = """
				Path of the clause this step was compiled from, into the request \
				body. Omitted for a step that is not a clause of its own.""",
			examples = "query[0].clauses[2]"
		)
		String clause,

		/**
		 * The kind of clause, left out wherever {@code clause} is.
		 */
		@Schema(
			description = """
				The kind of clause, as `type` names it in a request. Omitted \
				wherever `clause` is.""",
			examples = "text"
		)
		String clauseType,

		/**
		 * The field of the definition the step reads, left out when the step
		 * reads none or reads several.
		 */
		@Schema(
			description = """
				Field of the index definition this step reads. Omitted when the \
				step reads no field, or reads several.""",
			examples = "title"
		)
		String field,

		/**
		 * Which way of using the field the step reads it as, left out wherever
		 * {@code field} is.
		 */
		@Schema(
			description = """
				Which way of using the field the step reads it as, such as \
				`matching` or `filter`. Omitted wherever `field` is.""",
			examples = "matching"
		)
		String usage,

		/**
		 * Which variant of a locale specific field the step reads, left out for
		 * a field holding one variant for every language.
		 */
		@Schema(
			description = """
				BCP 47 tag of the variant this step reads. Omitted for a field \
				that holds one variant for every language.""",
			examples = "sv"
		)
		String locale,

		/**
		 * The steps this one is made of, empty at a leaf.
		 */
		@Schema(description = "The steps this one is made of. Empty at a leaf.")
		List<Detail> children
	) {
	}
}
