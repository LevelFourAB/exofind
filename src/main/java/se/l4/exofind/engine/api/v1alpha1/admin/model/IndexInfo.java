package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

/**
 * An index together with the definition and status of one generation of it.
 *
 * <p>A definition belongs to a generation rather than to the index, so what is
 * described here is whichever generation the request named - the live one when
 * it named the index alone. The generations that exist are listed beside it, so
 * that reading an index says what could be promoted as well as what is
 * answering now.
 *
 * @param name
 *   the name of the index
 * @param generation
 *   the generation this describes
 * @param live
 *   whether the index currently answers from this generation
 * @param version
 *   version of the definition, also sent as an {@code ETag} header. Pass it
 *   back in {@code If-Match} when updating to fail instead of overwriting an
 *   update made in the meantime
 * @param definition
 *   the definition currently in effect for this generation
 * @param status
 *   observed state of this generation on this node
 * @param generations
 *   every generation of the index, ordered by name
 */
public record IndexInfo(
	String name,
	String generation,
	boolean live,
	String version,
	IndexDefinition definition,
	IndexStatus status,
	List<GenerationSummary> generations
) {
}
