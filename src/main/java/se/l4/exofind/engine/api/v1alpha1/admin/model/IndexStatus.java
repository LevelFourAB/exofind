package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

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
public record IndexStatus(
	IndexState state,
	boolean readOnly,
	IndexerInfo indexer,
	LuceneCompatibility luceneCompatibility,
	Integer luceneCreatedMajor,
	List<String> settingsUnsupportedFeatures
) {
}
