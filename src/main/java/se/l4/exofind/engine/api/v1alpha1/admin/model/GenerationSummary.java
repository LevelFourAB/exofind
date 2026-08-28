package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One generation of an index, as listed alongside the others.
 *
 * @param name
 *   name of the generation within its index, which is what follows the
 *   {@code @} when addressing it
 * @param live
 *   whether the index answers from this generation. Exactly one generation of
 *   an index does, unless it has just been created
 * @param createdAt
 *   ISO-8601 timestamp, absent for a generation registered before this was
 *   recorded
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One generation of an index.")
public record GenerationSummary(
	@Schema(
		description = """
			Name of the generation within its index, which is what follows the \
			`@` when addressing it as `products@2`.""",
		examples = "2"
	)
	String name,

	@Schema(description = """
		Whether the index answers from this generation. Exactly one generation \
		of an index does, unless the index has just been created.""")
	boolean live,

	@Schema(
		description = """
			When the generation was created, as an ISO 8601 timestamp. \
			Omitted for a generation registered before this was recorded.""",
		examples = "2026-08-16T11:02:07Z"
	)
	String createdAt
) {
}
