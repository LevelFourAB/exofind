package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What to search for, from the text typed so far.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = """
		The suggestions for the typed text, the most common first, with \
		every suggestion the text starts before the ones found a mistake \
		away.""",
	examples = SuggestResponse.EXAMPLE
)
public record SuggestResponse(
	/**
	 * The suggestions, at most as many as the limit.
	 */
	@Schema(description = """
		The suggestions, the most common first and limited to the requested \
		maximum.""")
	List<Suggestion> suggestions,

	/**
	 * Execution time in milliseconds, including fractions of one.
	 */
	@Schema(
		description = "Execution time for the request in milliseconds, including fractions of one.",
		examples = "0.412"
	)
	double tookMs
) {
	/**
	 * One thing to search for.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		One thing to search for: a value of a suggested field, shown by its \
		label where the search settings declare one, with how much of it \
		was typed and how many documents hold it.""")
	public record Suggestion(
		/**
		 * What to show and to search for.
		 */
		@Schema(
			description = """
				What to show and to search for: the label of the value in the \
				locale of the request where the search settings declare one, \
				or the value itself. Where the typed text starts the value \
				but not its label, the value is shown, so that `typed` says \
				something true. Sending it as the `text` of a search finds \
				the value, and a `corrected` suggestion finds it exactly.""",
			examples = "adidas"
		)
		String text,

		/**
		 * How many characters at the start of {@code text} the typed text
		 * covers.
		 */
		@Schema(
			description = """
				How many characters at the start of `text` the typed text \
				covers, so the part typed can be marked apart from the part \
				that completes it. Counted on `text` as answered, not on \
				what was typed, so `RÖ` typed against `Röd` covers 2. `0` \
				when the suggestion was found a mistake away, and for a \
				blank text.""",
			examples = "3"
		)
		int typed,

		/**
		 * Present and {@code true} when the suggestion was found one mistake
		 * away from the text. Omitted when the text starts it.
		 */
		@Schema(description = """
			`true` when the suggestion was found one mistake away from the \
			text instead of starting with it. Omitted otherwise.""")
		Boolean corrected,

		/**
		 * The field the value is held by.
		 */
		@Schema(
			description = """
				The field the value is held by, as declared in the index \
				definition, which a `field` clause filtering on the value \
				names.""",
			examples = "brand"
		)
		String field,

		/**
		 * The value as the field stores it.
		 */
		@Schema(
			description = """
				The value as the field stores it, which a filter on the field \
				matches.""",
			examples = "adidas"
		)
		Object value,

		/**
		 * The label the search settings declare for the value in the locale
		 * of the request. Omitted when the settings declare none.
		 */
		@Schema(
			description = """
				The label the search settings of the index declare for the \
				value, in the locale of the request, falling back to the \
				field's default locale. Omitted when the settings declare no \
				label for the value.""",
			examples = "Adidas"
		)
		String label,

		/**
		 * Number of documents holding the value under the filters.
		 */
		@Schema(
			description = "Number of documents holding the value under the filters of the request.",
			examples = "87"
		)
		long count
	) {
	}

	/**
	 * The example response, answering {@link SuggestRequest#EXAMPLE}.
	 */
	public static final String EXAMPLE = """
		{
		  "suggestions": [
		    { "text": "adidas", "typed": 3, "field": "brand", "value": "adidas", "count": 87 },
		    { "text": "Adidas Originals", "typed": 3, "field": "brand", "value": "Adidas Originals", "count": 12 }
		  ],
		  "tookMs": 0.412
		}""";
}
