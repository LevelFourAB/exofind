package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 *   ID of the key used for unauthenticated requests, or {@code null} if
 *   unauthenticated requests are rejected
 * @param next
 *   the ID to pass as {@code after} to list the keys after these, or
 *   {@code null} when the response holds the rest
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
	description = """
		Deployment API keys and node key configuration. The keys are shared across \
		all nodes. The remaining fields reflect the local configuration of the \
		node answering the request.""",
	examples = KeyListResponse.EXAMPLE
)
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
			`EXOFIND_AUTH_ANONYMOUS_KEY`. `null` when the node rejects \
			unauthenticated requests. An anonymous key cannot contain any \
			permission other than `search`.""",
		examples = "fe3747c2761ef89d"
	)
	@JsonInclude(JsonInclude.Include.ALWAYS)
	String anonymousKey,

	@Schema(
		description = """
			The ID to pass as the `after` parameter to list the keys after \
			these. Present only when a `limit` cut the listing short.""",
		examples = "4ff6b760264c1918"
	)
	String next
) {
	/**
	 * The example response, as the JSON the engine answers with. The OpenAPI
	 * schema of this record shows this text. The permissions of a grant come
	 * back sorted by name, and the example shows that order.
	 */
	public static final String EXAMPLE = """
		{
		  "keys": [
		    {
		      "id": "4ff6b760264c1918",
		      "description": "the search backend",
		      "grants": [ { "permissions": ["indexes.read", "search"], "indexes": ["products"] } ],
		      "createdAt": "2026-08-16T12:09:33.198275Z",
		      "expiresAt": null
		    }
		  ],
		  "rootKeyConfigured": true,
		  "anonymousKey": null
		}""";
}
