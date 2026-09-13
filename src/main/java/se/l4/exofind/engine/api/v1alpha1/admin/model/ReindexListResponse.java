package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Every reindex job across the deployment, finished ones included.
 *
 * @param reindexes
 *   the jobs, ordered by index name
 * @param next
 *   the index name to pass as {@code after} to list the jobs after these,
 *   or {@code null} when the response holds the rest
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = "Every reindex job across the deployment, finished ones included.",
	examples = ReindexListResponse.EXAMPLE
)
public record ReindexListResponse(
	@Schema(description = """
		The jobs the key can view, ordered by index name. A job on an index on \
		which the key lacks permissions is omitted.""")
	List<ReindexInfo> reindexes,

	@Schema(
		description = """
			The index name to pass as the `after` parameter to list the jobs \
			after these. Present only when a `limit` cut the listing short.""",
		examples = "products"
	)
	String next
) {
	/**
	 * The example response, as the JSON the engine answers with. The OpenAPI
	 * schema of this record shows this text.
	 */
	public static final String EXAMPLE = """
		{
		  "reindexes": [
		    {
		      "id": "6f1c2a9d8b3e4c05",
		      "index": "products",
		      "target": "products@2",
		      "source": "products@1",
		      "phase": "copying",
		      "promote": "auto",
		      "documentsCopied": 125000,
		      "sourceDocuments": 2400000,
		      "backlog": 4100,
		      "error": null,
		      "startedBy": "3f9a1c7e2b8d4650",
		      "node": "node-a-7f21",
		      "startedAt": "2026-08-28T10:15:30Z",
		      "updatedAt": "2026-08-28T10:16:02Z",
		      "finishedAt": null
		    }
		  ]
		}""";
}
