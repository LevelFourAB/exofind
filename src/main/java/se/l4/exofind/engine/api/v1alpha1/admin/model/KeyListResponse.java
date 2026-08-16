package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

/**
 * The keys of the deployment, and how the node answering is configured to use
 * them.
 *
 * <p>The keys are shared by every node; the two fields below it are this node's
 * own configuration, so two nodes can answer the same list differently.
 *
 * @param keys
 *   the keys, ordered by id
 * @param rootKeyConfigured
 *   whether this node accepts a root key besides the ones listed. The key
 *   itself is never answered with
 * @param anonymousKey
 *   id of the key this node answers requests carrying no credential as, absent
 *   when it refuses them
 */
public record KeyListResponse(
	List<KeyInfo> keys,
	boolean rootKeyConfigured,
	String anonymousKey
) {
}
