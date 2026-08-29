package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(description = """
	What a key should be allowed to do. See \
	[Permissions](https://exofind.dev/reference/auth/#permissions).""")
public record KeyDefinition(
	@Schema(
		description = """
			What the key is for, so whoever lists the keys later can tell them \
			apart. Read by nobody but a human.""",
		examples = "the search backend"
	)
	String description,

	@Schema(
		description = """
			What the key may do, evaluated as a union - a request is allowed \
			if any grant permits it, and there are no deny rules. At least one \
			grant is required, as a key with none could do nothing.""",
		required = true
	)
	List<GrantDefinition> grants,

	@Schema(
		description = """
			When the key stops working, as an ISO 8601 timestamp. Omit for a \
			key that does not expire.""",
		examples = "2027-01-01T00:00:00Z"
	)
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
	@Schema(description = """
		A set of permissions over a set of indexes. The two are crossed: every \
		permission applies to every index matched. A grant must carry `role`, \
		`permissions`, or both.""")
	public record GrantDefinition(
		@Schema(
			description = """
				Shorthand for a set of permissions. `reader` is `search` and \
				`indexes.read`; `writer` adds `documents.read`, \
				`documents.write`, `documents.delete` and `indexes.commit`, \
				but not `indexes.write`; `admin` is every permission, key \
				management included. Roles are expanded when the key is \
				created and are not stored, so a key never changes meaning \
				when a later version widens a role.""",
			enumeration = {"reader", "writer", "admin"},
			examples = "reader"
		)
		String role,

		@Schema(description = """
			Permissions by name, added to whatever `role` stands for. An \
			unknown name returns `auth:key:unknown_permission`.""")
		List<String> permissions,

		@Schema(description = """
			Which indexes the permissions apply to, each an exact index name \
			or a prefix followed by `*`; a single `*` matches all indexes. \
			Required for index-scoped permissions and ignored by \
			deployment-scoped ones. Generations are named `index@generation`, \
			so `products` matches the index but no generation of it, \
			`products@*` every generation but not the index, and `products*` \
			both.""")
		List<String> indexes
	) {
	}
}
