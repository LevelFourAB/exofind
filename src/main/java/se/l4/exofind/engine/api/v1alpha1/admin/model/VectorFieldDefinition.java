package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Definition of a field holding a vector of floating-point numbers searched by
 * similarity using the {@code knn} search clause.
 *
 * <p>Vectors must be supplied in document payloads, and every vector must
 * contain exactly {@code dimensions} components:
 *
 * <pre>
 * {
 *   "type": "vector",
 *   "dimensions": 1536,
 *   "similarity": "cosine"
 * }
 * </pre>
 *
 * <p>Vector fields do not support {@code filter}, sorting, or faceting.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	Represents an array of floating-point numbers searched by similarity using \
	the `knn` search clause. Vector fields do not support `filter`, `sort`, \
	`facet`, or `locales`. Vectors must be supplied in document payloads. See \
	[Search by vector](https://exofind.dev/how-to/search-by-vector/).""")
public record VectorFieldDefinition(
	@Schema(description = FieldDefinition.PRIMARY_KEY_DESCRIPTION, defaultValue = "false")
	Boolean primaryKey,

	@Schema(description = FieldDefinition.REQUIRED_DESCRIPTION, defaultValue = "false")
	Boolean required,

	@Schema(description = FieldDefinition.MULTIPLE_DESCRIPTION, defaultValue = "false")
	Boolean multiple,

	@Schema(description = FieldDefinition.STORED_DESCRIPTION, defaultValue = "false")
	Boolean stored,

	@Schema(description = "Not supported on a vector field; setting it is rejected.")
	FieldDefinition.Locales locales,

	@Schema(description = """
		Not supported on a vector field. Vector fields are searched by \
		similarity using the `knn` search clause; setting it is rejected.""")
	FieldDefinition.Filter filter,

	@Schema(description = "Not supported on a vector field; setting it is rejected.")
	FieldDefinition.Sort sort,

	@Schema(description = "Not supported on a vector field; setting it is rejected.")
	FieldDefinition.Facet facet,

	/**
	 * Number of vector dimensions. Required. Cannot be modified after indexing
	 * documents.
	 */
	@Schema(
		description = """
			Number of vector dimensions. Required. Cannot be modified after \
			indexing documents.""",
		required = true,
		examples = "1536"
	)
	Integer dimensions,

	/**
	 * Vector distance metric. Defaults to {@code cosine}.
	 */
	@Schema(
		description = """
			Vector distance metric. `dot_product` requires unit-length \
			normalized vectors.""",
		defaultValue = "cosine"
	)
	Similarity similarity,

	/**
	 * Hierarchical Navigable Small World index configuration.
	 */
	@Schema(description = """
		Hierarchical Navigable Small World index configuration.""")
	Hnsw hnsw,

	/**
	 * Vector compression method. Defaults to {@code none}.
	 */
	@Schema(
		description = "Vector compression method.",
		defaultValue = "none"
	)
	Quantization quantization
) implements FieldDefinition {
	@Schema(description = """
		Vector distance metric: `cosine`, `dot_product`, or `euclidean`. \
		`dot_product` requires unit-length normalized vectors.""")
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
		Vector compression method: `none`, `int8`, or `int4`.""")
	public enum Quantization {
		@JsonProperty("none")
		NONE,

		@JsonProperty("int8")
		INT8,

		@JsonProperty("int4")
		INT4
	}

	/**
	 * Hierarchical Navigable Small World index configuration. Parameters trade
	 * indexing time and space for recall; omitting them uses engine defaults.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(description = """
		Hierarchical Navigable Small World index configuration. Parameters \
		trade indexing time and space for recall; omitting them uses engine \
		defaults.""")
	public record Hnsw(
		/**
		 * Number of bi-directional links per node.
		 */
		@Schema(description = "Number of bi-directional links per node.")
		Integer m,

		/**
		 * Size of dynamic candidate list evaluated during index construction.
		 */
		@Schema(description = """
			Size of dynamic candidate list evaluated during index \
			construction.""")
		Integer efConstruction
	) {
	}
}
