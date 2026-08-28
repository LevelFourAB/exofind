package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(description = """
	The keys of the deployment, and how the answering node is configured to use \
	them. The keys are shared by every node, while the other two fields are \
	this node's own configuration - so two nodes can answer the same listing \
	differently.""")
public record KeyListResponse(
	@Schema(description = "The deployment keys, ordered by ID.")
	List<KeyInfo> keys,

	@Schema(description = """
		Whether this node accepts a root key besides the ones listed. The root \
		key is configured per node with `EXOFIND_AUTH_ROOT_KEY`, is not kept \
		in key storage, and is never answered with.""")
	boolean rootKeyConfigured,

	@Schema(
		description = """
			ID of the key this node answers credential-less requests as, set \
			with `EXOFIND_AUTH_ANONYMOUS_KEY`. Omitted when the node refuses \
			such requests. Such a key may hold no permission other than \
			`search`.""",
		examples = "fe3747c2761ef89d"
	)
	String anonymousKey
) {
}
