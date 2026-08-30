package se.l4.exofind.engine.api.v1alpha1.documents.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Response returned after indexing documents into an index.
 *
 * @param indexed
 *   the number of documents indexed, matching every document in the request on
 *   success
 */
@Schema(
	description = "The count of indexed documents.",
	examples = DocumentsResponse.EXAMPLE
)
public record DocumentsResponse(
	@Schema(
		description = """
			The number of documents indexed. For a successful request, this \
			includes every document in the request.""",
		examples = "2"
	)
	int indexed
) {
	/**
	 * The example response, as the JSON the engine answers with. The OpenAPI
	 * schema of this record shows this text.
	 */
	public static final String EXAMPLE = """
		{ "indexed": 2 }""";
}
