package se.l4.exofind.engine.api.v1alpha1.search.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request for what to search for, from the text typed into a search box so
 * far.
 *
 * <p>The suggestions are picked from the values of the fields the search
 * settings of the index opt in with {@code suggest}, counted under the
 * {@code filters}, in the same shape as the filters of a
 * {@link SearchRequest}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = """
		Asks what to search for, from the text typed so far. The suggestions \
		are the values of the fields the search settings of the index opt in \
		with `suggest`, that start with the text, the most common first. All \
		properties are optional; an empty request answers the most common \
		values.""",
	examples = SuggestRequest.EXAMPLE
)
public record SuggestRequest(
	/**
	 * What has been typed so far. Omitted or blank answers the most common
	 * values.
	 */
	@Schema(
		description = """
			What has been typed so far. Compared with the start of each \
			whole value, folded in case and Unicode form, so `rö` finds \
			`Röd` and `air` does not find `Nike Air Max`, and with the \
			labels the search settings declare for the values in the locale \
			of the request. If omitted or blank, the most common values are \
			answered with `typed` at `0`.""",
		examples = "adi"
	)
	String text,

	/**
	 * BCP-47 locale tag used to read locale-specific fields and pick labels,
	 * as for a search.
	 */
	@Schema(
		description = """
			BCP-47 locale tag used to read locale-specific fields and to pick \
			the labels of declared values, as for a search. If omitted, uses \
			each field's default locale.""",
		examples = "sv"
	)
	String locale,

	/**
	 * Refinement clauses, in the same shape as {@link SearchRequest#filters()}.
	 * A filter on a suggested field is left out of that field's own counts.
	 */
	@Schema(description = """
		Refinement clauses, in the same shape as the `filters` of a search, \
		such as the category a search box is scoped to. The counts are the \
		ones a facet of a search under the same filters answers. A filter \
		on a suggested field is left out of that field's own counts, so a \
		brand already ticked keeps the other brands suggestable (see \
		[Facets](https://exofind.dev/reference/search-api/#facets)). The \
		clauses count against `EXOFIND_SEARCH_MAX_CLAUSES` and \
		`EXOFIND_SEARCH_MAX_CLAUSE_DEPTH`, as for a search.""")
	List<Clause> filters,

	/**
	 * Maximum number of suggestions to return (1 to 100). Defaults to 5.
	 */
	@Schema(
		description = "Maximum number of suggestions to return.",
		defaultValue = "5",
		minimum = "1",
		maximum = "100"
	)
	Integer limit,

	/**
	 * Whether a value one mistake away from the text may be suggested.
	 * Defaults to {@code auto}.
	 */
	@Schema(
		description = """
			Whether a value one mistake away from the text may be suggested \
			when fewer values than `limit` start with it. `auto` suggests \
			them after the values the text starts, once the text is at least \
			five characters long; a mistake is a character inserted, \
			dropped, replaced, or two adjacent ones swapped, never in the \
			first character. Such a suggestion carries `corrected: true` \
			and `typed: 0`. `off` never suggests them.""",
		defaultValue = "auto"
	)
	Typos typos
) {
	/**
	 * Whether a value near the text may be suggested.
	 */
	@Schema(description = """
		Typo tolerance: `auto` suggests values one mistake away from a text \
		of at least five characters when fewer values than the limit start \
		with it, `off` never does.""")
	public enum Typos {
		@JsonProperty("auto")
		AUTO,

		@JsonProperty("off")
		OFF
	}

	/**
	 * The example request, answered by {@link SuggestResponse#EXAMPLE}.
	 */
	public static final String EXAMPLE = """
		{
		  "text": "adi",
		  "filters": [
		    { "field": "category", "match": { "value": "Shoes" } }
		  ],
		  "limit": 5
		}""";
}
