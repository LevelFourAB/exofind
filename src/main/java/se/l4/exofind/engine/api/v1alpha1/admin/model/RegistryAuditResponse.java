package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

import se.l4.exofind.engine.index.registry.RegistryAuditReport;

/**
 * The registry compared with what remote storage holds. Reported by the engine
 * and never accepted as input.
 *
 * @param registry
 *   the state of the registry object
 * @param indexes
 *   every index named by the registry or found in storage, ordered by name
 * @param unusable
 *   storage prefixes whose names no index or generation may carry, as
 *   {@code index} or {@code index/generation}; a repair never registers these
 */
@Schema(
	description = """
		The registry compared with what remote storage holds. Reported by the \
		engine and never accepted as input. See \
		[Audit](https://exofind.dev/reference/admin-api/#audit).""",
	examples = RegistryAuditResponse.EXAMPLE
)
public record RegistryAuditResponse(
	@Schema(description = """
		The state of the registry object: `PRESENT`, `ABSENT` (no registry \
		object), or `CORRUPT` (contents cannot be parsed).""")
	RegistryAuditReport.Registry registry,

	@Schema(description = """
		Every index named by the registry or found in storage, ordered by \
		name.""")
	List<AuditedIndex> indexes,

	@Schema(description = """
		Storage prefixes whose names no index or generation may carry (as \
		`index` or `index/generation`). A repair never registers these \
		prefixes.""")
	List<String> unusable
) {
	/**
	 * The example response, as the JSON the engine answers with. The OpenAPI
	 * schema of this record shows this text.
	 */
	public static final String EXAMPLE = """
		{
		  "registry": "PRESENT",
		  "indexes": [
		    {
		      "name": "products",
		      "registered": true,
		      "live": "2",
		      "generations": [
		        { "name": "1", "registered": true, "stored": "SYNCED" },
		        { "name": "2", "registered": true, "stored": "SYNCED" }
		      ]
		    }
		  ],
		  "unusable": []
		}""";

	/**
	 * @param name
	 *   the name of the index
	 * @param registered
	 *   whether the registry has an entry for the index
	 * @param live
	 *   the generation the index answers for, omitted when unregistered or when
	 *   no generation is live
	 * @param proposedLive
	 *   the generation that a repair with promoteNewest would make live,
	 *   omitted when none would be promoted
	 * @param generations
	 *   a list of generations found for the index, ordered by name
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = "One index as the registry and storage each describe it.")
	public record AuditedIndex(
		@Schema(description = "Name of the index.", examples = "products")
		String name,

		@Schema(description = """
			A boolean indicating whether the registry has an entry for the \
			index.""")
		boolean registered,

		@Schema(
			description = """
				The generation the index answers for. Omitted when \
				unregistered or when no generation is live.""",
			examples = "2"
		)
		String live,

		@Schema(
			description = """
				The generation that a repair with `promoteNewest` would make \
				live. Omitted when none would be promoted.""",
			examples = "1"
		)
		String proposedLive,

		@Schema(description = "A list of generations found for the index, ordered by name.")
		List<AuditedGeneration> generations
	) {
	}

	/**
	 * @param name
	 *   the name of the generation
	 * @param registered
	 *   whether the registry names the generation
	 * @param stored
	 *   what storage holds under it
	 */
	@Schema(description = "One generation as the registry and storage each describe it.")
	public record AuditedGeneration(
		@Schema(description = "The name of the generation.", examples = "1")
		String name,

		@Schema(description = "A boolean indicating whether the registry names the generation.")
		boolean registered,

		@Schema(description = """
			What storage holds under it. `SYNCED`: storage holds a manifest; \
			nodes can pull and serve this generation. `INCOMPLETE`: storage \
			holds a prefix without a manifest (such as an unfinished push or \
			leftovers from a deleted generation). `MISSING`: the generation is \
			registered, but nothing exists in storage.""")
		RegistryAuditReport.Stored stored
	) {
	}
}
