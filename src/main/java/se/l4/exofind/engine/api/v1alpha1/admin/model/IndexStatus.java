package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

import se.l4.exofind.engine.index.IndexState;
import se.l4.exofind.engine.index.LuceneCompatibility;

/**
 * State of an index as observed by the node answering the request. Reported by
 * the engine and never accepted as input.
 *
 * @param state
 *   where the index is in its synchronization with the remote
 * @param readOnly
 *   if this node can modify the index. Only the node running as the indexer
 *   can, other nodes serve searches from their local copy
 * @param indexer
 *   the node currently writing the index, whichever node answers. {@code null}
 *   when no node holds it, when who does could not be read, or when the node
 *   stores locally - where {@code readOnly} already answers
 * @param luceneCompatibility
 *   how much longer the index can be read. An index reported as {@code ENDING}
 *   is readable now but is dropped by the next Lucene major, so it has to be
 *   reindexed before the nodes are upgraded across one
 * @param luceneCreatedMajor
 *   major Lucene version the index was created with, or {@code null} when
 *   nothing recorded one and the index has no commit to read it from
 * @param settingsUnsupportedFeatures
 *   names of what the index's search settings need that the answering node
 *   does not have. Only present when the node has set the settings aside and
 *   searches with the definition alone
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = """
	The state of an index as the answering node observes it. Reported by the \
	engine and never accepted as input. See [Index \
	states](https://exofind.dev/reference/admin-api/#index-states).""")
public record IndexStatus(
	@Schema(description = """
		Where the index is in its synchronization with remote storage. \
		`NEEDS_PULL`: a newer remote state has not been pulled yet. \
		`PULLING`: the node is fetching remote state. `USABLE`: the index is \
		serving searches. `MODIFIED`: there are local changes not yet pushed, \
		which only a writer node reaches. `PUSHING`: local changes are being \
		pushed. `UNSUPPORTED`: the definition needs engine features this node \
		version lacks. `INCOMPATIBLE`: the Lucene files are too old for this \
		build to open. `CLOSED`: the index is closed on this node, and a new \
		request opens a fresh instance.""")
	IndexState state,

	@Schema(description = """
		Whether the answering node can modify the index. Only the node holding \
		the index can; the others serve searches from their local copy.""")
	boolean readOnly,

	@Schema(description = """
		The node currently writing the index. Omitted when no node holds it, \
		when the holder could not be read, when the holder provided no \
		address, or on a node using local storage - where `readOnly` already \
		answers. Can lag a handover by a few seconds.""")
	IndexerInfo indexer,

	@Schema(description = """
		How much longer the Lucene files can be read. `CURRENT`: created by \
		the current major version, and compatible with the next. `ENDING`: \
		readable now but dropped by the next Lucene major, so it has to be \
		reindexed before nodes are upgraded across one. `UNREADABLE`: too old \
		to open, which is what puts the index in the `INCOMPATIBLE` state. \
		`UNKNOWN`: no version was recorded and there is no commit to read one \
		from, such as on an empty index.""")
	LuceneCompatibility luceneCompatibility,

	@Schema(
		description = """
			The recorded Lucene major version the index was created with. \
			Omitted when compatibility is `UNKNOWN`.""",
		examples = "10"
	)
	Integer luceneCreatedMajor,

	@Schema(description = """
		What the index's search settings need that the answering node does not \
		have. Present only when the node has set the settings aside and \
		searches with the definition alone; upgrading the node puts them back \
		in force.""")
	List<String> settingsUnsupportedFeatures
) {
}
