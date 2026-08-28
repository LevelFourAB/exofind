package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Definition of a field holding a vector of floats, searched by similarity
 * rather than by value.
 *
 * The vectors arrive with the documents - the engine never produces them - and
 * every value has to have exactly {@code dimensions} components:
 *
 * <pre>
 * {
 *   "type": "vector",
 *   "dimensions": 1536,
 *   "similarity": "cosine"
 * }
 * </pre>
 *
 * A vector is searched with the {@code knn} clause, so it has no
 * {@code filter} - filtering, sorting and faceting mean nothing for it and are
 * refused.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	An array of floating-point numbers searched by similarity with the `knn` \
	search clause. Vectors are supplied in document payloads - the engine never \
	produces them - and vector fields support no `filter`, `sort`, `facet` or \
	`locales`. See [Search by \
	vector](https://levelfourab.github.io/exofind/how-to/search-by-vector/).""")
public record VectorFieldDefinition(
	@Schema(description = FieldDefinition.PRIMARY_KEY_DESCRIPTION, defaultValue = "false")
	Boolean primaryKey,

	@Schema(description = FieldDefinition.REQUIRED_DESCRIPTION, defaultValue = "false")
	Boolean required,

	@Schema(description = FieldDefinition.MULTIPLE_DESCRIPTION, defaultValue = "false")
	Boolean multiple,

	@Schema(description = FieldDefinition.STORED_DESCRIPTION, defaultValue = "false")
	Boolean stored,

	@Schema(description = "Not supported on a vector field; setting it is refused.")
	FieldDefinition.Locales locales,

	@Schema(description = """
		Not supported on a vector field - a vector is searched with the `knn` \
		clause - so setting it is refused.""")
	FieldDefinition.Filter filter,

	@Schema(description = "Not supported on a vector field; setting it is refused.")
	FieldDefinition.Sort sort,

	@Schema(description = "Not supported on a vector field; setting it is refused.")
	FieldDefinition.Facet facet,

	/**
	 * How many components every vector of the field has. Required, and fixed
	 * once documents have been indexed.
	 */
	@Schema(
		description = """
			Number of vector dimensions. Every value must have exactly this \
			many components. Required, and fixed once documents have been \
			indexed.""",
		required = true,
		examples = "1536"
	)
	Integer dimensions,

	/**
	 * How near two vectors are judged to be. Defaults to {@code cosine}.
	 */
	@Schema(
		description = """
			Vector distance metric. `dot_product` requires unit-length \
			normalized vectors.""",
		defaultValue = "cosine"
	)
	Similarity similarity,

	/**
	 * Parameters of the HNSW graph the vectors are searched through.
	 */
	@Schema(description = """
		Parameters of the Hierarchical Navigable Small World graph the vectors \
		are searched through.""")
	Hnsw hnsw,

	/**
	 * How much precision stored vectors give up for space. Defaults to
	 * {@code none}.
	 */
	@Schema(
		description = "Vector compression method.",
		defaultValue = "none"
	)
	Quantization quantization
) implements FieldDefinition {
	@Schema(description = """
		Vector distance metric: `cosine`, `dot_product` - which requires \
		unit-length vectors - or `euclidean`.""")
	public enum Similarity {
		@JsonProperty("cosine")
		COSINE,

		/**
		 * Ranks by the dot product of the vectors, which only orders sensibly
		 * when every vector is unit length - a promise the caller makes about
		 * the model the vectors come from.
		 */
		@JsonProperty("dot_product")
		DOT_PRODUCT,

		@JsonProperty("euclidean")
		EUCLIDEAN
	}

	@Schema(description = """
		Vector compression method: `none`, `int8` or `int4`.""")
	public enum Quantization {
		@JsonProperty("none")
		NONE,

		@JsonProperty("int8")
		INT8,

		@JsonProperty("int4")
		INT4
	}

	/**
	 * Parameters of the HNSW graph. Both trade indexing time and space for
	 * recall; leaving them out takes the engine defaults.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Parameters of the HNSW graph. Both trade indexing time and space for \
		recall; leaving them out takes the engine defaults.""")
	public record Hnsw(
		/**
		 * How many neighbours each node of the graph keeps.
		 */
		@Schema(description = "Number of bi-directional links each node of the graph keeps.")
		Integer m,

		/**
		 * How many candidates are considered while the graph is built.
		 */
		@Schema(description = """
			Size of the dynamic candidate list evaluated while the graph is \
			built.""")
		Integer efConstruction
	) {
	}
}
