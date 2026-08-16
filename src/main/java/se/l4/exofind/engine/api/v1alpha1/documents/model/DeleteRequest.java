package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;

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
public record DeleteRequest(
	List<Object> keys,
	List<Clause> query,
	String locale
) {
}
