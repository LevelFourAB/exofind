package se.l4.exofind.engine.api.v1alpha1.documents.model;

import java.util.List;

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
public record UpdateResponse(
	int updated,
	List<Object> missing
) {
}
