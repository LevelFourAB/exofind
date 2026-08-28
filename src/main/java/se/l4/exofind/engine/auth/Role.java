package se.l4.exofind.engine.auth;

import java.util.Optional;

import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.set.ImmutableSet;
import org.eclipse.collections.api.set.SetIterable;

/**
 * Shorthand for a set of permissions, for creating the keys a deployment
 * actually hands out.
 *
 * <p>A role is expanded when a key is created and only the permissions it
 * expanded to are stored. Nothing reads a role afterwards, which is what keeps
 * a key granted today from quietly gaining a permission when a later version
 * widens what a role covers.
 *
 * <p>The three roles are the three things that hold keys: what serves searches,
 * what loads documents, and what defines the indexes.
 */
public enum Role {
	/**
	 * Searching, and reading how an index is defined. What an application's own
	 * backend holds.
	 */
	READER(Permission.SEARCH, Permission.INDEXES_READ),

	/**
	 * Everything a reader may do, plus putting documents in, reading them back
	 * out, taking them out and committing. What a pipeline that loads data
	 * holds - it deliberately cannot change a definition, so a runaway loader
	 * cannot reshape a schema.
	 */
	WRITER(
		Permission.SEARCH,
		Permission.INDEXES_READ,
		Permission.DOCUMENTS_READ,
		Permission.DOCUMENTS_WRITE,
		Permission.DOCUMENTS_DELETE,
		Permission.INDEXES_COMMIT
	),

	/**
	 * Every permission there is, including managing keys. What whatever applies
	 * definitions holds.
	 */
	ADMIN(Permission.values());

	private static final MapIterable<String, Role> BY_ID = byId();

	private final ImmutableSet<Permission> permissions;

	Role(Permission... permissions) {
		this.permissions = Sets.immutable.of(permissions);
	}

	/**
	 * The name this role is written as.
	 */
	public String id() {
		return name().toLowerCase();
	}

	/**
	 * What this role stands for, which is what gets stored.
	 */
	public SetIterable<Permission> permissions() {
		return permissions;
	}

	/**
	 * Look a role up by the name it is written as.
	 *
	 * @param id
	 * @return
	 *   empty when there is no such role
	 */
	public static Optional<Role> byId(String id) {
		return id == null
			? Optional.empty()
			: Optional.ofNullable(BY_ID.get(id.toLowerCase()));
	}

	private static MapIterable<String, Role> byId() {
		var map = Maps.mutable.<String, Role>empty();
		for(var role : values()) {
			map.put(role.id(), role);
		}

		return map.toImmutable();
	}
}
