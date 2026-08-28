package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What came of changing some of the fields of documents in an index.
 *
 * @param updated
 *   how many documents were changed
 * @param missing
 *   the keys nothing was indexed under, in the order they were given. Only ever
 *   filled for a request that asked for them to be skipped - one that did not
 *   fails on the first of them instead
 */
@Schema(description = "The count of updated documents, and any keys that were skipped.")
public record UpdateResponse(
	@Schema(description = "How many documents were changed.", examples = "1998")
	int updated,

	@Schema(description = """
		The keys nothing was indexed under, in the order they were given. Only \
		ever filled for a request sent with `?missing=skip`; one sent without \
		it fails on the first such key instead.""")
	List<Object> missing
) {
}
