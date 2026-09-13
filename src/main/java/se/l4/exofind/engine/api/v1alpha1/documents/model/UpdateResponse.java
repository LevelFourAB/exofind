package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Response returned after updating fields of documents in an index.
 *
 * @param updated
 *   number of documents updated
 * @param missing
 *   primary keys that were not found, in the order provided. Each key is text,
 *   whatever type the key field holds. Holds keys only when the request
 *   specifies skipping missing keys; otherwise, the request fails on the first
 *   missing key and the list is empty
 * @param failed
 *   the changes the index refused, in the order sent. Holds entries only when
 *   the request specifies skipping refused changes; otherwise, the request fails
 *   on the first refused change and the list is empty
 */
@Schema(
	description = """
		The count of updated documents, and any keys or changes that were \
		skipped.""",
	examples = UpdateResponse.EXAMPLE
)
public record UpdateResponse(
	@Schema(description = "The number of documents updated.", examples = "1998")
	int updated,

	@Schema(description = """
		List of primary keys that were not found, in the order provided. Each \
		key is text, whatever type the key field declares: a whole-number key \
		`42` is returned as `"42"`. Send a key back in the `{key}` path \
		parameter or the `after` parameter as it came. For more information, \
		see [Primary keys on the \
		wire](https://exofind.dev/reference/api-conventions/#primary-keys-on-the-wire). \
		Holds keys only when the request is sent with `?missing=skip`; a \
		request sent without it fails on the first missing key. Always \
		present, and empty when nothing was skipped.""")
	List<String> missing,

	@Schema(description = """
		The changes the index refused, in the order sent. Holds entries only \
		when the request is sent with `?onError=skip`; a request sent without it \
		fails on the first refused change. A key nothing is indexed under is \
		reported under `missing` instead when the request is sent with \
		`?missing=skip`. Always present, and empty when nothing was skipped.""")
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
	 * {@code ?missing=skip} answers with an empty {@code missing}, and one sent
	 * without {@code ?onError=skip} with an empty {@code failed}.
	 */
	public static final String EXAMPLE = """
		{ "updated": 1998, "missing": [], "failed": [], "freshness": "AQoIcHJvZHVjdHMSATIYBw" }""";
}
