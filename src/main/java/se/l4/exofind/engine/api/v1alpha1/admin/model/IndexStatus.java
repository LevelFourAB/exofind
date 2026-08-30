package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

import se.l4.exofind.engine.index.IndexState;
import se.l4.exofind.engine.index.LuceneCompatibility;

/**
 * State of an index as observed by the answering node. Reported by the engine
 * and never accepted as input.
 *
 * @param state
 *   remote synchronization state as observed by the answering node
 * @param readOnly
 *   whether the answering node can modify the index; only the node holding the
 *   index can modify it, while other nodes serve searches from local copies
 * @param indexer
 *   the node currently writing the index, or {@code null} when no node holds
 *   it, when the holder could not be read, or on nodes using local storage
 *   where {@code readOnly} already answers
 * @param luceneCompatibility
 *   Lucene version compatibility; an index reported as {@code ENDING} is
 *   readable now but unsupported by the next Lucene major version, requiring
 *   reindexing before upgrading across major versions
 * @param luceneCreatedMajor
 *   major Lucene version the index was created with, or {@code null} when no
 *   version was recorded and no commit exists to determine it
 * @param settingsUnsupportedFeatures
 *   capabilities the index's search settings require that the answering node
 *   does not support; present only when the node sets the settings aside and
 *   searches with the definition alone
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = """
		The observed state reported by the answering node. The API does not accept \
		this object as input. See [Index \
		states](https://exofind.dev/reference/admin-api/#index-states).""",
	examples = IndexStatus.EXAMPLE
)
public record IndexStatus(
	@Schema(description = """
		The remote synchronization state as observed by the answering node: \
		`NEEDS_PULL` (a newer remote state exists and has not been pulled \
		yet), `PULLING` (the node is fetching remote state), `USABLE` (the \
		index is serving searches), `MODIFIED` (the index has local changes \
		that are not yet pushed; only writer nodes reach this state), \
		`PUSHING` (the node is pushing local changes), `UNSUPPORTED` (the \
		definition requires engine features not present on this node version), \
		`INCOMPATIBLE` (the Lucene files are too old for this build to open), \
		or `CLOSED` (the index is closed on this node; a new request opens a \
		fresh instance).""")
	IndexState state,

	@Schema(description = """
		Indicates whether the answering node can modify the index. Only the \
		node holding the index can modify it; other nodes serve searches from \
		their local copy.""")
	boolean readOnly,

	@Schema(description = """
		Identifies the holder node and the address where writes are forwarded. \
		Omitted if no node holds the index, if the holder could not be read, \
		if the holder provided no address, or on nodes using local storage \
		where `readOnly` already answers. Data in this field can lag behind a \
		node handover by a few seconds.""")
	IndexerInfo indexer,

	@Schema(description = """
		Indicates Lucene version compatibility. `CURRENT`: created by the \
		current major version, and compatible with the current and next Lucene \
		major versions. `ENDING`: readable by the current version, but \
		unsupported by the next Lucene major version; reindex before upgrading \
		across major versions. `UNREADABLE`: too old to open; the index \
		reports the `INCOMPATIBLE` state and requires reindexing. `UNKNOWN`: \
		no version was recorded and no commit exists to determine the version, \
		such as on an empty index.""")
	LuceneCompatibility luceneCompatibility,

	@Schema(
		description = """
			The recorded Lucene major version the index was created with. \
			Omitted when compatibility is `UNKNOWN`.""",
		examples = "10"
	)
	Integer luceneCreatedMajor,

	@Schema(description = """
		Lists the capabilities the index's search settings use that the \
		answering node does not have. Present only when the node has set the \
		settings aside and searches with the definition alone; upgrading the \
		node puts them back in force.""")
	List<String> settingsUnsupportedFeatures
) {
	/**
	 * The example status, as the JSON the engine answers with. The OpenAPI
	 * schema of this record shows this text.
	 */
	public static final String EXAMPLE = """
		{
		  "state": "USABLE",
		  "readOnly": false,
		  "indexer": { "node": "node-a-7f21", "address": "http://node-a:8080" },
		  "luceneCompatibility": "CURRENT",
		  "luceneCreatedMajor": 10
		}""";
}
