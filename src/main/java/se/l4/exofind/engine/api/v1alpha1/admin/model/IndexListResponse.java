package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The indexes held across the deployment that the caller has permission to
 * view.
 *
 * <p>Definitions and status are omitted and must be fetched per index.
 *
 * @param indexes
 *   indexes visible to the caller, ordered by name
 */
@Schema(
	description = """
		The indexes held across the deployment. Definitions and status are \
		omitted; request a specific index to retrieve them.""",
	examples = IndexListResponse.EXAMPLE
)
public record IndexListResponse(
	@Schema(description = "The indexes visible to the key, ordered by name.")
	List<IndexSummary> indexes
) {
	/**
	 * The example response, as the JSON the engine answers with. The OpenAPI
	 * schema of this record shows this text.
	 */
	public static final String EXAMPLE = """
		{
		  "indexes": [
		    {
		      "name": "products",
		      "generation": "2",
		      "generations": [
		        { "name": "1", "live": false, "createdAt": "2026-08-16T11:02:07Z" },
		        { "name": "2", "live": true, "createdAt": "2026-08-28T10:15:30Z" }
		      ]
		    }
		  ]
		}""";

	/**
	 * Summary of an index and its generations.
	 *
	 * @param name
	 *   name of the index
	 * @param generation
	 *   live generation of the index, or omitted if none is live
	 * @param generations
	 *   all generations of the index, ordered by name
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "An index and the generations it holds.")
	public record IndexSummary(
		@Schema(description = "The name of the index.", examples = "products")
		String name,

		@Schema(
			description = """
				The generation the index answers from. Omitted when no \
				generation is live.""",
			examples = "2"
		)
		String generation,

		@Schema(description = "Every generation of the index, ordered by name.")
		List<GenerationSummary> generations
	) {
	}
}
