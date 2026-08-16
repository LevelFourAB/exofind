package se.l4.exofind.engine.api.v1alpha1.admin.model;

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
public record GenerationSummary(
	String name,
	boolean live,
	String createdAt
) {
}
