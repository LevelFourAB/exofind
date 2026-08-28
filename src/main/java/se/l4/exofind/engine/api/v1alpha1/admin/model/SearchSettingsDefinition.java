package se.l4.exofind.engine.api.v1alpha1.admin.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Search settings of an index, describing how its searches behave apart from
 * how its documents were indexed.
 *
 * <p>Settings are the state a caller wants - they are sent in full and replace
 * what was stored, so the same request can be repeated without changing the
 * outcome. Unlike a definition they belong to the index name rather than to a
 * generation: promoting a generation keeps them, and changing them reaches
 * every node without going through the index's writer.
 *
 * @param ranking
 *   the ranking searches run with instead of the definition's. An empty object
 *   turns the definition's ranking off; left out - together with everything
 *   else - the settings say nothing and the definition stands
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	Per-index settings that affect how searches are answered, sent in full and \
	replacing what was stored. They belong to the index name rather than to a \
	generation, so promoting a generation keeps them.""")
public record SearchSettingsDefinition(
	@Schema(description = """
		The ranking searches run with instead of the definition's, in the same \
		shape as the definition's `ranking`. While present it replaces the \
		definition's ranking completely; an empty object turns ranking off. \
		Supplying `signals` in a search request still replaces both. Validated \
		against the generation the index name answers from, using the same \
		`index:ranking:*` codes that validate a definition's ranking.""")
	IndexDefinition.Ranking ranking
) {
}
