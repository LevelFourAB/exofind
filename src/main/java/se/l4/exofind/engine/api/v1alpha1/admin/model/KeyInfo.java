package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

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
public record KeyInfo(
	String id,
	String description,
	List<Grant> grants,
	String createdAt,
	String expiresAt
) {
	/**
	 * @param permissions
	 *   permission names, ordered
	 * @param indexes
	 *   index names and prefix patterns, in the order they were given
	 */
	public record Grant(
		List<String> permissions,
		List<String> indexes
	) {
	}
}
