package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Search settings of an index as they are stored, together with what the node
 * answering makes of them.
 *
 * @param ranking
 *   the ranking searches run with instead of the definition's, or {@code null}
 *   when the settings carry none
 * @param version
 *   the version the settings are stored at, also returned as the {@code ETag}
 *   of the response and accepted back as {@code If-Match}
 * @param unsupportedFeatures
 *   names of what the settings need that the answering node does not have.
 *   Only present when the node has set the settings aside and searches with
 *   the definition alone
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchSettingsInfo(
	IndexDefinition.Ranking ranking,
	String version,
	List<String> unsupportedFeatures
) {
}
