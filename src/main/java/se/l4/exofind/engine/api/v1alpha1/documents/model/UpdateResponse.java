package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Response returned after updating fields of documents in an index.
 *
 * @param updated
 *   number of documents updated
 * @param missing
 *   primary keys that were not found, in the order provided. Populated only
 *   when the request specifies skipping missing keys; otherwise, the request
 *   fails on the first missing key
 */
@Schema(description = "The count of updated documents, and any keys that were skipped.")
public record UpdateResponse(
	@Schema(description = "The number of documents updated.", examples = "1998")
	int updated,

	@Schema(description = """
		List of primary keys that were not found, in the order provided. \
		Populated only when the request is sent with `?missing=skip`; a \
		request sent without it fails on the first missing key.""")
	List<Object> missing
) {
}
