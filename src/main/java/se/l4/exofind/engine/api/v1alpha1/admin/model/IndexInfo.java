package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * An index together with the definition and status of one of its generations.
 *
 * <p>A definition belongs to a generation rather than directly to the index.
 * This resource describes the generation specified in the request, or the live
 * generation if omitted.
 *
 * @param name
 *   name of the index
 * @param generation
 *   generation described in this response
 * @param live
 *   whether this generation is the live generation
 * @param version
 *   version of the definition, returned in the {@code ETag} header. Pass in
 *   {@code If-Match} on updates to prevent overwriting concurrent changes
 * @param definition
 *   active definition for this generation
 * @param status
 *   observed state of this generation on the answering node
 * @param generations
 *   all generations of the index, ordered by name
 */
@Schema(
	description = """
		An index together with the definition and status of one of its \
		generations: the generation specified in the request, or the live \
		generation if omitted. See [Index \
		resource](https://exofind.dev/reference/admin-api/#index-resource).""",
	examples = IndexInfo.EXAMPLE
)
public record IndexInfo(
	@Schema(description = "The name of the index.", examples = "products")
	String name,

	@Schema(
		description = """
			The generation described in the response. When the request \
			specifies only the index name, this is the live generation.""",
		examples = "2"
	)
	String generation,

	@Schema(description = "A boolean indicating whether this generation is the live generation.")
	boolean live,

	@Schema(
		description = """
			An identifier for the definition, also returned in the `ETag` \
			header. Pass this value in the `If-Match` header on `PUT` requests \
			to prevent overwriting concurrent updates.""",
		examples = "9f2c1a0b3d4e5f60"
	)
	String version,

	@Schema(description = """
		The active index definition. Presets are stored expanded; the response \
		returns the expanded chain rather than the preset name.""")
	IndexDefinition definition,

	@Schema(description = """
		The observed state reported by the answering node. The API does not \
		accept this object as input.""")
	IndexStatus status,

	@Schema(description = """
		A list of all generations for the index, ordered by name.""")
	List<GenerationSummary> generations
) {
	/**
	 * The example response, as the JSON the engine answers with. The OpenAPI
	 * schema of this record shows this text.
	 */
	public static final String EXAMPLE = """
		{
		  "name": "products",
		  "generation": "2",
		  "live": true,
		  "version": "9f2c1a0b3d4e5f60",
		  "definition": {
		    "fields": {
		      "id": { "type": "string", "primaryKey": true, "required": true },
		      "name": { "type": "string", "matching": {}, "sort": {} }
		    }
		  },
		  "status": {
		    "state": "USABLE",
		    "readOnly": false,
		    "indexer": { "node": "node-a-7f21", "address": "http://node-a:8080" },
		    "luceneCompatibility": "CURRENT"
		  },
		  "generations": [
		    { "name": "1", "live": false, "createdAt": "2026-08-16T11:02:07Z" },
		    { "name": "2", "live": true, "createdAt": "2026-08-28T10:15:30Z" }
		  ]
		}""";
}
