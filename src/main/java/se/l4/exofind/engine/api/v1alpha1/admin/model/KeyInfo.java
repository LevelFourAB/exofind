package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A key as the deployment holds it.
 *
 * <p>The credential itself is not here and cannot be recovered - only a hash of
 * it is stored. A key whose credential was lost is replaced.
 *
 * @param id
 *   what the credential names, and what logs record in place of it
 * @param description
 * @param grants
 *   what the key may do, with any role it was created from already expanded
 * @param createdAt
 *   ISO-8601 timestamp
 * @param expiresAt
 *   ISO-8601 timestamp, absent for a key that does not expire
 */
@Schema(description = """
	A key as the deployment holds it. The credential itself is not here and \
	cannot be recovered - only a hash of it is stored - so a key whose \
	credential was lost is replaced rather than read back.""")
public record KeyInfo(
	@Schema(
		description = """
			What the credential names, and what server logs record in place of \
			it.""",
		examples = "4ff6b760264c1918"
	)
	String id,

	@Schema(description = "What the key is for.", examples = "the search backend")
	String description,

	@Schema(description = """
		What the key may do, with any role it was created from already \
		expanded into its permissions.""")
	List<Grant> grants,

	@Schema(
		description = "When the key was created, as an ISO 8601 timestamp.",
		examples = "2026-08-16T12:09:33.198275Z"
	)
	String createdAt,

	@Schema(
		description = """
			When the key stops working, as an ISO 8601 timestamp. Omitted for \
			a key that does not expire.""",
		examples = "2027-01-01T00:00:00Z"
	)
	String expiresAt
) {
	/**
	 * @param permissions
	 *   permission names, ordered
	 * @param indexes
	 *   index names and prefix patterns, in the order they were given
	 */
	@Schema(description = "A set of permissions over a set of indexes, as stored.")
	public record Grant(
		@Schema(description = "Permission names, ordered.")
		List<String> permissions,

		@Schema(description = """
			Index names and prefix patterns, in the order they were given.""")
		List<String> indexes
	) {
	}
}
