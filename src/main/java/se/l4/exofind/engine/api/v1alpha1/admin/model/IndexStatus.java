package se.l4.exofind.engine.api.v1alpha1.admin.model;

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
 * @param luceneCompatibility
 *   how much longer the index can be read. An index reported as {@code ENDING}
 *   is readable now but is dropped by the next Lucene major, so it has to be
 *   reindexed before the nodes are upgraded across one
 * @param luceneCreatedMajor
 *   major Lucene version the index was created with, or {@code null} when
 *   nothing recorded one and the index has no commit to read it from
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndexStatus(
	IndexState state,
	boolean readOnly,
	LuceneCompatibility luceneCompatibility,
	Integer luceneCreatedMajor
) {
}
