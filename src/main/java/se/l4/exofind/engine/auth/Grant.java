package se.l4.exofind.engine.auth;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.api.set.SetIterable;

/**
 * A set of permissions over a set of indexes, held by a {@link Key}.
 *
 * <p>The two sets are crossed: every permission applies to every index the
 * patterns match. A key that needs different permissions on different indexes
 * holds a grant for each.
 *
 * <p>An index pattern is the name of an index, or a prefix followed by
 * {@code *}; {@code *} on its own matches every index. Nothing richer is
 * accepted, so what a key reaches can be read off the pattern without working
 * out what a wildcard in the middle would do. A pattern with a {@code *}
 * anywhere but at the end matches no index at all.
 *
 * <p>A generation is named {@code index@generation}, so it is matched by the
 * same patterns rather than by anything of its own: {@code books} reaches the
 * index and no generation by name, while {@code books@*} reaches every
 * generation of it and not the index. That is what lets the key an application
 * holds follow an index across rebuilds while never being able to address a
 * generation, and the key that rolls one out reach the generations of that
 * index alone - the {@code @} appears in no name, so the prefix cannot run past
 * the index it names.
 *
 * <p>Permissions of {@link Permission.Scope#DEPLOYMENT} are granted by this
 * grant whatever its patterns say, as there is no index for a pattern to be
 * about.
 */
public record Grant(SetIterable<Permission> permissions, ListIterable<String> indexes) {
	public Grant {
		permissions = permissions == null
			? Sets.immutable.empty()
			: Sets.immutable.ofAll(permissions);
		indexes = indexes == null
			? Lists.immutable.empty()
			: Lists.immutable.ofAll(indexes);
	}

	/**
	 * Whether this grant allows a permission that names no index.
	 */
	public boolean allows(Permission permission) {
		return permission.scope() == Permission.Scope.DEPLOYMENT
			&& permissions.contains(permission);
	}

	/**
	 * Whether this grant allows a permission on one index.
	 *
	 * @param permission
	 * @param index
	 *   name of the index, ignored for a permission that names none
	 * @return
	 */
	public boolean allows(Permission permission, String index) {
		if(!permissions.contains(permission)) {
			return false;
		}

		return permission.scope() == Permission.Scope.DEPLOYMENT || covers(index);
	}

	/**
	 * Whether an index is among the ones this grant is about, whatever the
	 * grant allows to be done with it.
	 *
	 * <p>What decides whether an index is visible to a key at all: an index no
	 * grant covers is answered as if it did not exist, so a key can not use the
	 * difference between being refused and being told nothing is there to find
	 * out what a deployment holds.
	 *
	 * @param index
	 * @return
	 */
	public boolean covers(String index) {
		for(var pattern : indexes) {
			if(matches(pattern, index)) {
				return true;
			}
		}

		return false;
	}

	private static boolean matches(String pattern, String index) {
		if(!pattern.endsWith("*")) {
			return !pattern.contains("*") && pattern.equals(index);
		}

		var prefix = pattern.substring(0, pattern.length() - 1);
		return !prefix.contains("*") && index.startsWith(prefix);
	}
}
