package se.l4.exofind.engine.api.v1alpha1.admin.model;

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
public record VectorFieldDefinition(
	Boolean primaryKey,
	Boolean required,
	Boolean multiple,
	Boolean stored,
	FieldDefinition.Locales locales,
	FieldDefinition.Filter filter,
	FieldDefinition.Sort sort,
	FieldDefinition.Facet facet,

	/**
	 * How many components every vector of the field has. Required, and fixed
	 * once documents have been indexed.
	 */
	Integer dimensions,

	/**
	 * How near two vectors are judged to be. Defaults to {@code cosine}.
	 */
	Similarity similarity,

	/**
	 * Parameters of the HNSW graph the vectors are searched through.
	 */
	Hnsw hnsw,

	/**
	 * How much precision stored vectors give up for space. Defaults to
	 * {@code none}.
	 */
	Quantization quantization
) implements FieldDefinition {
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
	public record Hnsw(
		/**
		 * How many neighbours each node of the graph keeps.
		 */
		Integer m,

		/**
		 * How many candidates are considered while the graph is built.
		 */
		Integer efConstruction
	) {
	}
}
