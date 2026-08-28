package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(description = """
	An index together with the definition and status of one of its \
	generations - the one the request named, or the live one when it named the \
	index alone. See [Index \
	resource](https://levelfourab.github.io/exofind/reference/admin-api/#index-resource).""")
public record IndexInfo(
	@Schema(description = "Name of the index.", examples = "products")
	String name,

	@Schema(
		description = """
			The generation this response describes. When the request named \
			only the index, this is the live generation.""",
		examples = "2"
	)
	String generation,

	@Schema(description = "Whether the index currently answers from this generation.")
	boolean live,

	@Schema(
		description = """
			Version of the definition, also returned in the `ETag` header. \
			Send it back in `If-Match` on a `PUT` to be told that someone else \
			changed the index rather than overwriting their change.""",
		examples = "9f2c1a0b3d4e5f60"
	)
	String version,

	@Schema(description = """
		The definition in effect for this generation. Presets are stored \
		expanded, so this is the expanded chain rather than the preset \
		name.""")
	IndexDefinition definition,

	@Schema(description = """
		The state of this generation as the answering node observes it. Never \
		accepted as input.""")
	IndexStatus status,

	@Schema(description = """
		Every generation of the index, ordered by name - so reading an index \
		says what could be promoted as well as what is answering now.""")
	List<GenerationSummary> generations
) {
}
