package se.l4.exofind.engine.api.v1alpha1.documents.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What came of putting documents into an index.
 *
 * @param indexed
 *   how many documents the index took, which for a request that succeeded is
 *   every document it carried
 */
@Schema(description = "The count of indexed documents.")
public record DocumentsResponse(
	@Schema(
		description = """
			How many documents were indexed, which for a request that \
			succeeded is every document it carried.""",
		examples = "2"
	)
	int indexed
) {
}
