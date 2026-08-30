package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

import se.l4.exofind.engine.api.v1alpha1.search.model.Clause;

/**
 * Specifies documents to remove from an index by primary keys or by search
 * query.
 *
 * <p>The request body must include either `keys` or `query`, but not both:
 *
 * <pre>{@value #BY_KEYS}
 *
 * {@value #BY_QUERY}</pre>
 *
 * @param keys
 *   the primary keys of the documents to remove, formatted according to the key
 *   field type. Deleting an unindexed key produces a success response
 * @param query
 *   query clauses matching documents to delete, using search query clause
 *   syntax. An empty list matches and deletes all documents
 * @param locale
 *   the BCP 47 locale tag used to match locale-specific fields, or omitted to
 *   use each field's default locale. Valid only when specifying {@code query}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = """
		Which documents to remove. The request must carry either `keys` or \
		`query`, but not both (`request:delete:target_required`, \
		`request:delete:target_conflicting`).""",
	examples = { DeleteRequest.BY_KEYS, DeleteRequest.BY_QUERY }
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
		uncommitted documents indexed since the last commit. An empty array \
		matches and deletes all documents.""")
	List<Clause> query,

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
}
