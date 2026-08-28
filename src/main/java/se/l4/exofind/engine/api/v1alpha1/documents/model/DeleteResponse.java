package se.l4.exofind.engine.api.v1alpha1.documents.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(description = "The count of deleted documents.")
public record DeleteResponse(
	@Schema(
		description = """
			How many documents were removed. A request naming `keys` counts \
			the keys it carried, since a key nothing was indexed under is not \
			an error; a request naming a `query` counts the committed \
			searchable documents it matched.""",
		examples = "3"
	)
	int deleted
) {
}
