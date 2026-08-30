package se.l4.exofind.engine.api.v1alpha1.admin.model;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Definition of permissions and index patterns for an API key.
 *
 * @param description
 *   what the key is for, to distinguish keys in listings
 * @param grants
 *   permissions granted to the key, evaluated as a union; at least one grant is
 *   required
 * @param expiresAt
 *   when the key expires, as an ISO-8601 timestamp; omitted if the key does not
 *   expire
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
			What the key may do, evaluated as a union: a request is allowed if \
			any grant permits it, and there are no deny rules. At least one \
			grant is required, as a key with none could do nothing.""",
		required = true
	)
	List<GrantDefinition> grants,

	@Schema(
		description = """
			An ISO 8601 timestamp string defining when the key expires. If \
			omitted, the key does not expire.""",
		examples = "2027-01-01T00:00:00Z"
	)
	String expiresAt
) {
	/**
	 * A set of permissions over a set of index patterns. Every permission in
	 * the grant applies to every matching index.
	 *
	 * @param role
	 *   shorthand role name: {@code reader}, {@code writer}, or {@code admin}.
	 *   Expanded into constituent permissions when the key is created
	 * @param permissions
	 *   permission names to grant, added to any permissions from the role
	 * @param indexes
	 *   index names or prefix patterns ending in {@code *}. Required for
	 *   index-scoped permissions and ignored for deployment-scoped permissions
	 */
	@Schema(description = """
		A set of permissions over a set of index patterns. Every permission in \
		the grant applies to every matching index. A grant specifies `role`, \
		`permissions`, or both.""")
	public record GrantDefinition(
		@Schema(
			description = """
				Shorthand for a set of permissions. `reader` grants `search` \
				and `indexes.read`. `writer` adds `documents.read`, \
				`documents.write`, `documents.delete`, and `indexes.commit`, \
				but not `indexes.write`. `admin` grants all permissions, \
				including key management. When a key is created, roles are \
				expanded into their constituent permissions. Only the \
				resulting permissions are stored in the key. Existing keys do \
				not change permissions if role definitions change in later \
				software versions.""",
			enumeration = {"reader", "writer", "admin"},
			examples = "reader"
		)
		String role,

		@Schema(description = """
			Permissions by name, added to whatever `role` specifies. An \
			unknown permission name returns `auth:key:unknown_permission`.""")
		List<String> permissions,

		@Schema(description = """
			Index names or prefix patterns the permissions apply to. An index \
			pattern is either an exact index name or a prefix followed by `*`; \
			a single `*` matches all indexes. Required for index-scoped \
			permissions and ignored for deployment-scoped permissions. \
			Generations are named `index@generation`: `products` matches the \
			index but no generation of it, `products@*` matches every \
			generation but not the index itself, and `products*` matches both.""")
		List<String> indexes
	) {
	}
}
