package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import se.l4.exofind.engine.api.v1alpha1.search.model.Clause;

/**
 * Specifies documents to remove from an index by primary keys, by search query,
 * or every document at once.
 *
 * <p>The request body must name exactly one of `keys`, `query`, and `all`:
 *
 * <pre>{@value #BY_KEYS}
 *
 * {@value #BY_QUERY}
 *
 * {@value #ALL}</pre>
 *
 * @param keys
 *   the primary keys of the documents to remove, formatted according to the key
 *   field type. Deleting an unindexed key produces a success response
 * @param query
 *   query clauses matching documents to delete, using search query clause
 *   syntax. At least one clause is required
 * @param all
 *   {@code true} to remove every document and empty the index, or {@code null}
 *   or {@code false} to name the documents by {@code keys} or {@code query}
 * @param locale
 *   the BCP 47 locale tag used to match locale-specific fields, or omitted to
 *   use each field's default locale. Valid only when specifying {@code query}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = """
		Which documents to remove. The request must name exactly one of `keys`, \
		`query`, and `all` (`request:delete:target_required`, \
		`request:delete:target_conflicting`).""",
	examples = { DeleteRequest.BY_KEYS, DeleteRequest.BY_QUERY, DeleteRequest.ALL }
)
public record DeleteRequest(
	@Schema(description = """
		List of primary keys to delete, formatted according to the key field \
		type. All keys are validated before any documents are removed; if any \
		key is invalid, no documents are removed. An empty array deletes \
		nothing, and requesting the deletion of an unindexed key produces a \
		success response.""")
	List<Object> keys,

	@Schema(description = """
		Query clauses matching documents to delete, using search query clause \
		syntax. Removes matching committed searchable documents and any \
		uncommitted documents indexed since the last commit. The array requires \
		at least one clause (`request:delete:query_empty`). To empty the index, \
		send `all` instead.""")
	List<Clause> query,

	@Schema(description = """
		Set to `true` to remove every document and empty the index. Cannot be \
		combined with `keys` or `query`.""")
	Boolean all,

	@Schema(
		description = """
			BCP 47 locale tag used to match locale-specific fields, defaulting \
			to each field's default locale. Valid only when specifying `query` \
			(`request:delete:locale_without_query`).""",
		examples = "sv"
	)
	String locale
) {
	/**
	 * The example naming the documents by key. The class Javadoc and the
	 * OpenAPI schema of this record both show this text.
	 */
	public static final String BY_KEYS = """
		{ "keys": ["1", "2"] }""";

	/**
	 * The example naming the documents by query. The class Javadoc and the
	 * OpenAPI schema of this record both show this text.
	 */
	public static final String BY_QUERY = """
		{ "query": [ { "field": "category", "match": { "value": "sylt" } } ] }""";

	/**
	 * The example emptying the index. The class Javadoc and the OpenAPI schema
	 * of this record both show this text.
	 */
	public static final String ALL = """
		{ "all": true }""";

	/**
	 * Get if this request asks for every document to be removed.
	 */
	@JsonIgnore
	@Schema(hidden = true)
	public boolean removesEverything() {
		return all != null && all;
	}
}
