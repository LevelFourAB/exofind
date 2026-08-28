package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

import se.l4.exofind.engine.api.v1alpha1.search.model.Clause;

/**
 * Which documents to remove from an index, as it is received over the API.
 *
 * Documents are named either by their primary keys or by a query they match,
 * and a request carries exactly one of the two:
 *
 * <pre>
 * { "keys": ["1", "2"] }
 *
 * { "query": [ { "field": "category", "match": { "value": "sylt" } } ] }
 * </pre>
 *
 * @param keys
 *   the primary keys of the documents to remove, each as the type of the key
 *   field holds it. A key nothing was indexed under is not an error
 * @param query
 *   the clauses a document has to satisfy to be removed, all of them - the
 *   same clauses a search is written with. An empty list matches every
 *   document and empties the index
 * @param locale
 *   the locale locale specific fields are matched in (BCP 47), left out to
 *   leave every field to its own default locale. Only meaningful with
 *   {@code query}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	Which documents to remove. The request must carry either `keys` or \
	`query`, but not both (`request:delete:target_required`, \
	`request:delete:target_conflicting`).""")
public record DeleteRequest(
	@Schema(description = """
		Primary keys of the documents to remove, each written as the key \
		field's type holds it. All keys are validated before any document is \
		removed, so an invalid key removes nothing. An empty array deletes \
		nothing, and a key nothing was indexed under is not an error.""")
	List<Object> keys,

	@Schema(description = """
		Clauses a document must satisfy to be removed, all of them - the same \
		clauses a search is written with. Removes matching committed \
		searchable documents along with any uncommitted ones indexed since the \
		last commit. An empty array matches every document and empties the \
		index.""")
	List<Clause> query,

	@Schema(
		description = """
			BCP-47 locale tag used to match locale-specific fields, defaulting \
			to each field's own locale. Valid only together with `query` \
			(`request:delete:locale_without_query`).""",
		examples = "sv"
	)
	String locale
) {
}
