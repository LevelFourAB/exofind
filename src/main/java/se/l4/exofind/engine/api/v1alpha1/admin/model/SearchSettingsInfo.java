package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Search settings of an index as they are stored, together with what the node
 * answering makes of them.
 *
 * @param ranking
 *   the ranking searches run with instead of the definition's, or {@code null}
 *   when the settings carry none
 * @param synonyms
 *   synonym sets applied to the text of a search, or {@code null} when the
 *   settings carry none
 * @param version
 *   the version the settings are stored at, also returned as the {@code ETag}
 *   of the response and accepted back as {@code If-Match}
 * @param unsupportedFeatures
 *   names of what the settings need that the answering node does not have.
 *   Only present when the node has set the settings aside and searches with
 *   the definition alone
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	The search settings of an index as they are stored, together with what the \
	answering node makes of them. See [Search \
	settings](https://exofind.dev/reference/admin-api/#search-settings).""")
public record SearchSettingsInfo(
	@Schema(description = """
		The ranking searches run with instead of the definition's. Omitted \
		when the settings carry none.""")
	IndexDefinition.Ranking ranking,

	@Schema(description = """
		Synonym sets applied to the text of a search, by name. Omitted when \
		the settings carry none.""")
	Map<String, SearchSettingsDefinition.QuerySynonyms> synonyms,

	@Schema(
		description = """
			The version the settings are stored at, also returned in the \
			`ETag` header. Send it back in `If-Match` on a `PUT`; a mismatch \
			returns `412`.""",
		examples = "9f2c1a0b3d4e5f60"
	)
	String version,

	@Schema(description = """
		What the settings need that the answering node does not have. Present \
		only when the node has set the settings aside and searches with the \
		definition alone; upgrading the node puts them in force.""")
	List<String> unsupportedFeatures
) {
}
