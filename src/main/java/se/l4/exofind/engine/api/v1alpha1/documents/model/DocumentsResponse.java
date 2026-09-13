package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Response returned after indexing documents into an index.
 *
 * @param indexed
 *   the number of documents indexed, matching every document in the request on
 *   success
 * @param failed
 *   the documents the index refused, in the order sent. Holds entries only when
 *   the request specifies skipping refused documents; otherwise, the request
 *   fails on the first refused document and the list is empty
 */
@Schema(
	description = "The count of indexed documents, and any that were skipped.",
	examples = DocumentsResponse.EXAMPLE
)
public record DocumentsResponse(
	@Schema(
		description = """
			The number of documents indexed. For a successful request, this \
			includes every document in the request, except any reported under \
			`failed`.""",
		examples = "2"
	)
	int indexed,

	@Schema(description = """
		The documents the index refused, in the order sent. Holds entries only \
		when the request is sent with `?onError=skip`; a request sent without it \
		fails on the first refused document. Always present, and empty when \
		nothing was skipped.""")
	List<DocumentFailure> failed,

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
	 * schema of this record shows this text. A request sent without
	 * {@code ?onError=skip} answers with an empty {@code failed}.
	 */
	public static final String EXAMPLE = """
		{ "indexed": 2, "failed": [], "freshness": "AQoIcHJvZHVjdHMSATIYBw" }""";
}
