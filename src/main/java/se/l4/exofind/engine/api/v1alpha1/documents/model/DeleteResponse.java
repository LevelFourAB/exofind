package se.l4.exofind.engine.api.v1alpha1.documents.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Response returned after deleting documents from an index.
 *
 * @param deleted
 *   the number of removed documents. For requests using keys, this counts the
 *   keys provided in the request, since requesting the deletion of an unindexed
 *   key produces a success response; for requests using a query, this counts
 *   the matching committed searchable documents
 */
@Schema(
	description = "The count of deleted documents.",
	examples = DeleteResponse.EXAMPLE
)
public record DeleteResponse(
	@Schema(
		description = """
			How many documents were removed. For requests using `keys`, this \
			is the number of keys provided in the request, since requesting \
			the deletion of an unindexed key produces a success response. For \
			requests using `query`, this is the number of matching committed \
			searchable documents.""",
		examples = "3"
	)
	int deleted,

	/**
	 * The state the change lands in, as a freshness token.
	 */
	@Schema(
		description = """
			A freshness token for the state the change lands in. Pass it \
			as `freshness.atLeast` on a search, or in the \
			`X-Exofind-Freshness` header of a read, and that request is \
			answered only once the node holds the change. Opaque; pass it \
			back unchanged. See \
			[Freshness](https://exofind.dev/reference/search-api/#freshness).""",
		examples = "AQoIcHJvZHVjdHMSATIYBw"
	)
	String freshness
) {
	/**
	 * The example response, as the JSON the engine answers with. The OpenAPI
	 * schema of this record shows this text.
	 */
	public static final String EXAMPLE = """
		{ "deleted": 3, "freshness": "AQoIcHJvZHVjdHMSATIYBw" }""";
}
