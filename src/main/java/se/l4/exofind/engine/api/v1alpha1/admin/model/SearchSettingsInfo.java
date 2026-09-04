package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Search settings of an index as stored, together with observed feature support
 * from the answering node.
 *
 * @param ranking
 *   ranking configuration applied instead of the definition ranking, or
 *   {@code null} when not configured
 * @param synonyms
 *   query-time synonym sets applied to search text, or {@code null} when not
 *   configured
 * @param typoExclusions
 *   words matched as spelled, or {@code null} when not configured
 * @param fields
 *   how searches read single fields, keyed by field name, or {@code null}
 *   when not configured
 * @param version
 *   version identifier of the stored settings, also returned in the
 *   {@code ETag} header and accepted in {@code If-Match}
 * @param unsupportedFeatures
 *   capabilities the settings require that the answering node does not support;
 *   present only when the node sets the settings aside and searches with the
 *   definition alone
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = """
		The search settings of an index as stored, together with the observed \
		status reported by the answering node. See [Search \
		settings](https://exofind.dev/reference/admin-api/#search-settings).""",
	examples = SearchSettingsInfo.EXAMPLE
)
public record SearchSettingsInfo(
	@Schema(description = """
		The ranking searches run with instead of the definition's ranking. \
		Omitted when the settings configure no ranking.""")
	IndexDefinition.Ranking ranking,

	@Schema(description = """
		Synonym sets applied to the text of a search, keyed by set name. \
		Omitted when the settings configure no synonyms.""")
	Map<String, SearchSettingsDefinition.QuerySynonyms> synonyms,

	@Schema(description = """
		Words matched as they are spelled, keyed by list name. Omitted when \
		the settings configure no typo exclusions.""")
	Map<String, SearchSettingsDefinition.TypoExclusions> typoExclusions,

	@Schema(description = """
		Settings that apply to one field, keyed by field name. Omitted when \
		the settings configure no field.""")
	Map<String, SearchSettingsDefinition.FieldSettings> fields,

	@Schema(
		description = """
			An identifier for the stored settings version, also returned in \
			the `ETag` header. Pass this value in the `If-Match` header on \
			`PUT` requests to prevent overwriting concurrent updates; a \
			mismatch returns `412`.""",
		examples = "9f2c1a0b3d4e5f60"
	)
	String version,

	@Schema(description = """
		Present only when the answering node sets the settings aside because \
		they use capabilities its version does not have. The node searches \
		with the definition alone. Upgrade the node to put the settings in \
		force.""")
	List<String> unsupportedFeatures
) {
	/**
	 * The example response, as the JSON the engine answers with. The OpenAPI
	 * schema of this record shows this text.
	 */
	public static final String EXAMPLE = """
		{
		  "ranking": {
		    "signals": [
		      { "field": "purchases", "saturation": { "pivot": 50 }, "weight": 0.5 }
		    ],
		    "tieBreakers": [
		      { "field": "sales", "direction": "descending" }
		    ]
		  },
		  "version": "9f2c1a0b3d4e5f60"
		}""";
}
