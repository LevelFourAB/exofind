package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The deployment API keys and the local key configuration of the answering
 * node.
 *
 * <p>Deployment keys are shared across all nodes. The remaining fields reflect
 * the local configuration of the node answering the request.
 *
 * @param keys
 *   deployment keys, ordered by ID
 * @param rootKeyConfigured
 *   whether this node has a root key configured
 * @param anonymousKey
 *   ID of the key used for unauthenticated requests, or absent if
 *   unauthenticated requests are rejected
 */
@Schema(description = """
	Deployment API keys and node key configuration. The keys are shared across \
	all nodes. The remaining fields reflect the local configuration of the \
	node answering the request.""")
public record KeyListResponse(
	@Schema(description = "Deployment keys shared across all nodes, ordered by ID.")
	List<KeyInfo> keys,

	@Schema(description = """
		Whether this node has a root key configured with \
		`EXOFIND_AUTH_ROOT_KEY`. The root key is not stored in key storage and \
		cannot be listed or revoked through the API.""")
	boolean rootKeyConfigured,

	@Schema(
		description = """
			ID of the key used for unauthenticated requests, configured with \
			`EXOFIND_AUTH_ANONYMOUS_KEY`. Omitted when the node rejects \
			unauthenticated requests. An anonymous key cannot contain any \
			permission other than `search`.""",
		examples = "fe3747c2761ef89d"
	)
	String anonymousKey
) {
}
