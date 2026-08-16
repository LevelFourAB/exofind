package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

/**
 * What a key should be allowed to do.
 *
 * @param description
 *   what the key is for, kept so that whoever lists the keys later can tell
 *   them apart. Read by nobody but a human
 * @param grants
 *   what the key may do, evaluated as a union. At least one is required - a key
 *   with none could do nothing
 * @param expiresAt
 *   when the key stops working, as an ISO-8601 timestamp. Omit for a key that
 *   does not expire
 */
public record KeyDefinition(
	String description,
	List<GrantDefinition> grants,
	String expiresAt
) {
	/**
	 * A set of permissions over a set of indexes. The two are crossed: every
	 * permission applies to every index matched.
	 *
	 * @param role
	 *   shorthand for a set of permissions - {@code reader}, {@code writer} or
	 *   {@code admin}. Expanded when the key is created and not stored, so a
	 *   key never changes meaning when a later version widens a role
	 * @param permissions
	 *   permissions by name, added to whatever the role stands for. One of the
	 *   two is required
	 * @param indexes
	 *   which indexes the permissions apply to, each the name of an index or a
	 *   prefix followed by {@code *}. Required for permissions that name an
	 *   index, ignored by the ones that do not
	 */
	public record GrantDefinition(
		String role,
		List<String> permissions,
		List<String> indexes
	) {
	}
}
