package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The indexes the deployment holds that the caller may see.
 *
 * Definitions and status are not listed. They belong to a generation and
 * require it to be opened, which for a node with many indexes would mean
 * opening all of them to answer a listing, so they are fetched per index
 * instead. Which generations exist comes from the registry and costs nothing,
 * so it is listed here.
 *
 * @param indexes
 *   the indexes, ordered by name
 */
@Schema(description = """
	The indexes the deployment holds. Definitions and status are not listed - \
	they belong to a generation and require it to be opened - so read one \
	index to get them.""")
public record IndexListResponse(
	@Schema(description = "The indexes the key may see, ordered by name.")
	List<IndexSummary> indexes
) {
	/**
	 * @param name
	 *   the name of the index
	 * @param generation
	 *   the generation the index answers from, absent for one that answers from
	 *   none
	 * @param generations
	 *   every generation of the index, ordered by name
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "One index and the generations it holds.")
	public record IndexSummary(
		@Schema(description = "Name of the index.", examples = "products")
		String name,

		@Schema(
			description = """
				The generation the index answers from. Omitted for an index \
				that answers from none.""",
			examples = "2"
		)
		String generation,

		@Schema(description = "Every generation of the index, ordered by name.")
		List<GenerationSummary> generations
	) {
	}
}
