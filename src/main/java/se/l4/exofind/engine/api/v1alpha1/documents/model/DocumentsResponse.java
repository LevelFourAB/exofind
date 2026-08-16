package se.l4.exofind.engine.api.v1alpha1.documents.model;

/**
 * What came of putting documents into an index.
 *
 * @param indexed
 *   how many documents the index took, which for a request that succeeded is
 *   every document it carried
 */
public record DocumentsResponse(
	int indexed
) {
}
