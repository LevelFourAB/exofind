package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Every reindex job across the deployment, finished ones included.
 *
 * @param reindexes
 *   the jobs, ordered by index name
 */
@Schema(
	description = "Every reindex job across the deployment, finished ones included.",
	examples = ReindexListResponse.EXAMPLE
)
public record ReindexListResponse(
	@Schema(description = """
		The jobs the key can view, ordered by index name. A job on an index on \
		which the key lacks permissions is omitted.""")
	List<ReindexInfo> reindexes
) {
	/**
	 * The example response, as the JSON the engine answers with. The OpenAPI
	 * schema of this record shows this text.
	 */
	public static final String EXAMPLE = """
		{
		  "reindexes": [
		    {
		      "index": "products",
		      "target": "products@2",
		      "source": "products@1",
		      "phase": "copying",
		      "promote": "auto",
		      "documentsCopied": 125000,
		      "sourceDocuments": 2400000,
		      "startedAt": "2026-08-28T10:15:30Z",
		      "updatedAt": "2026-08-28T10:16:02Z"
		    }
		  ]
		}""";
}
