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
	int deleted
) {
	/**
	 * The example response, as the JSON the engine answers with. The OpenAPI
	 * schema of this record shows this text.
	 */
	public static final String EXAMPLE = """
		{ "deleted": 3 }""";
}
