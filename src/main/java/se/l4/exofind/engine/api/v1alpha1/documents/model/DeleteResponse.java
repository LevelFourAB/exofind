package se.l4.exofind.engine.api.v1alpha1.documents.model;

/**
 * What came of removing documents from an index.
 *
 * @param deleted
 *   how many documents the request removed. A request naming keys counts the
 *   keys it carried, as a key nothing was indexed under is not an error; a
 *   request naming a query counts the documents it matched among the
 *   searchable ones, which leaves out any it removed that had not been
 *   committed yet
 */
public record DeleteResponse(
	int deleted
) {
}
