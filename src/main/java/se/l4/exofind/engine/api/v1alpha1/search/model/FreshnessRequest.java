package se.l4.exofind.engine.api.v1alpha1.search.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What a read demands of the state it is answered from.
 *
 * @param atLeast
 *   a freshness token an earlier response returned. The node answers only
 *   once it holds the state the token describes, or fails once it has waited as
 *   long as it may
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	name = "Freshness",
	description = """
		What the request demands of the state it is answered from. See \
		[Freshness](https://exofind.dev/reference/search-api/#freshness)."""
)
public record FreshnessRequest(
	@Schema(
		description = """
			A freshness token that an earlier response returned in its \
			`freshness` property or `X-Exofind-Freshness` header. The node \
			answers only once it holds the state the token describes: the \
			generation, the commit and the search settings. A node that is \
			behind commits, pulls or reads the settings first, and answers \
			`search:freshness:unavailable` with a `Retry-After` header when \
			it has waited `EXOFIND_SEARCH_FRESHNESS_WAIT` without reaching \
			the state. The token is opaque; pass it back unchanged.""",
		examples = "AQoIcHJvZHVjdHMSATIYBw"
	)
	String atLeast
) {
}
