package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request for the values of one facet field that start with a typed prefix,
 * counted under a search.
 *
 * <p>The {@code query} and {@code filters} are the ones of the search whose
 * counts the values belong with, in the same shape as a
 * {@link SearchRequest}. The counts leave the filter entries on the facet's
 * own field out, the way a facet of a search does.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = """
		Asks for the values of one facet field that start with a prefix, \
		counted under the query and filters of a search. All properties are \
		optional; an empty request answers the most common values under \
		everything the index holds.""",
	examples = FacetValuesRequest.EXAMPLE
)
public record FacetValuesRequest(
	/**
	 * Clauses that a counted document must satisfy, in the same shape as
	 * {@link SearchRequest#query()}.
	 */
	@Schema(description = """
		Clauses that a counted document must satisfy, in the same shape as \
		the `query` of a search. If omitted, every document is counted.""")
	List<Clause> query,

	/**
	 * Refinement clauses, in the same shape as {@link SearchRequest#filters()}.
	 * Filter entries on the facet's own field are left out of the counts.
	 */
	@Schema(description = """
		Refinement clauses, in the same shape as the `filters` of a search. \
		Filter entries on the facet's own field are left out of the counts, \
		so a value already ticked keeps the other values countable (see \
		[Facets](https://exofind.dev/reference/search-api/#facets)).""")
	List<Clause> filters,

	/**
	 * What the answered values start with. Omitted or blank answers every
	 * value.
	 */
	@Schema(
		description = """
			What the answered values start with. Compared with the values \
			folded in case and Unicode form, so `rö` finds `Röd`, and with \
			the labels the search settings declare for them in the locale of \
			the request. A number, boolean or timestamp field compares the \
			prefix with the value as a search response shows it, ignoring \
			case. If omitted or blank, every value is answered.""",
		examples = "adi"
	)
	String prefix,

	/**
	 * BCP-47 locale tag used to read locale-specific fields, as for a search.
	 */
	@Schema(
		description = """
			BCP-47 locale tag used to read locale-specific fields, as for a \
			search. If omitted, uses each field's default locale.""",
		examples = "sv"
	)
	String locale,

	/**
	 * Maximum number of values to return (1 to 1000). Defaults to 10.
	 */
	@Schema(
		description = "Maximum number of values to return.",
		defaultValue = "10",
		minimum = "1",
		maximum = "1000"
	)
	Integer limit,

	/**
	 * Sort order of the values. Defaults to {@code count}.
	 */
	@Schema(
		description = """
			Sort order of the values: `"count"` (descending by count), \
			`"value"` (ascending by value), or `"declared"` (the order the \
			search settings declare for the field's values, followed by \
			every other value by count).""",
		defaultValue = "count"
	)
	SearchRequest.Facet.Order order
) {
	/**
	 * The example request, answered by {@link FacetValuesResponse#EXAMPLE}.
	 */
	public static final String EXAMPLE = """
		{
		  "query": [
		    { "type": "text", "text": "running shoes" }
		  ],
		  "filters": [
		    { "field": "brand", "match": { "type": "in", "values": ["Nike"] } }
		  ],
		  "prefix": "adi",
		  "limit": 5
		}""";
}
