package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A key as stored by the deployment.
 *
 * <p>Key secrets are stored only as hashes. A lost credential cannot be
 * recovered and must be replaced.
 *
 * @param id
 *   key identifier recorded in server logs in place of the credential
 * @param description
 * @param grants
 *   grants for the key, with roles expanded into their constituent permissions
 * @param createdAt
 *   creation timestamp as an ISO-8601 string
 * @param expiresAt
 *   expiration timestamp as an ISO-8601 string, or absent if the key does not
 *   expire
 */
@Schema(description = """
	A key as stored by the deployment. Key secrets are stored only as hashes. \
	A lost credential cannot be recovered and must be replaced.""")
public record KeyInfo(
	@Schema(
		description = """
			Key identifier. Server logs record the key ID, never the \
			credential value.""",
		examples = "4ff6b760264c1918"
	)
	String id,

	@Schema(description = "A string describing the key.", examples = "the search backend")
	String description,

	@Schema(description = """
		Grants assigned to the key, with roles expanded into their constituent \
		permissions.""")
	List<Grant> grants,

	@Schema(
		description = "An ISO 8601 timestamp string defining when the key was created.",
		examples = "2026-08-16T12:09:33.198275Z"
	)
	String createdAt,

	@Schema(
		description = """
			An ISO 8601 timestamp string defining when the key expires. \
			Omitted for a key that does not expire.""",
		examples = "2027-01-01T00:00:00Z"
	)
	String expiresAt
) {
	/**
	 * @param permissions
	 *   ordered list of permission names
	 * @param indexes
	 *   index names and prefix patterns in the order specified
	 */
	@Schema(description = "A stored grant combining permissions and index patterns.")
	public record Grant(
		@Schema(description = "Permission names, in sorted order.")
		List<String> permissions,

		@Schema(description = """
			Index names and prefix patterns in the order specified.""")
		List<String> indexes
	) {
	}
}
