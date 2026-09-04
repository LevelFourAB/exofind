package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The values of one facet field that start with a prefix, with how many
 * documents of the search hold each.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = """
		The values of one facet field that start with the prefix, each with \
		how many matching documents hold it.""",
	examples = FacetValuesResponse.EXAMPLE
)
public record FacetValuesResponse(
	/**
	 * The values with their counts, in the requested order and at most as
	 * many as the limit.
	 */
	@Schema(description = """
		The values with their counts, in the requested order and limited to \
		the configured maximum.""")
	List<SearchResponse.FacetValue> values,

	/**
	 * Total count of distinct values that start with the prefix. Exceeds the
	 * number of returned values when the limit is reached.
	 */
	@Schema(description = """
		Total count of distinct values that start with the prefix. Exceeds \
		the number of entries under `values` when the limit is reached.""")
	int totalValues,

	/**
	 * Execution time in milliseconds, including fractions of one.
	 */
	@Schema(
		description = "Execution time for the request in milliseconds, including fractions of one.",
		examples = "1.208"
	)
	double tookMs
) {
	/**
	 * The example response, answering {@link FacetValuesRequest#EXAMPLE}.
	 */
	public static final String EXAMPLE = """
		{
		  "values": [
		    { "value": "adidas", "count": 87 },
		    { "value": "Adidas Originals", "count": 12 }
		  ],
		  "totalValues": 2,
		  "tookMs": 1.208
		}""";
}
