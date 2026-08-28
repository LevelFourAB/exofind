package se.l4.exofind.engine.auth;

import java.util.Optional;

import org.eclipse.collections.api.factory.Maps;
import org.eclipse.collections.api.map.MapIterable;

/**
 * What a key is allowed to do, as one name per thing that can be done.
 *
 * The names are stored in keys and written by whoever creates one, so they are
 * never renamed and never reused for something else. A name this build has no
 * constant for is ignored where it is read, which grants nothing - a grant only
 * ever adds, so dropping part of one can only allow less.
 *
 * Coarse roles such as reader and writer are not values here. A role is
 * expanded into permissions when a key is created and only the permissions are
 * stored, so adding a permission to the engine never widens a key that already
 * exists.
 */
public enum Permission {
	SEARCH("search", Scope.INDEX, Anonymous.ALLOWED),

	DOCUMENTS_READ("documents.read", Scope.INDEX, Anonymous.REFUSED),
	DOCUMENTS_WRITE("documents.write", Scope.INDEX, Anonymous.REFUSED),
	DOCUMENTS_DELETE("documents.delete", Scope.INDEX, Anonymous.REFUSED),

	INDEXES_READ("indexes.read", Scope.INDEX, Anonymous.REFUSED),
	INDEXES_WRITE("indexes.write", Scope.INDEX, Anonymous.REFUSED),
	INDEXES_DELETE("indexes.delete", Scope.INDEX, Anonymous.REFUSED),
	INDEXES_PROMOTE("indexes.promote", Scope.INDEX, Anonymous.REFUSED),
	INDEXES_COMMIT("indexes.commit", Scope.INDEX, Anonymous.REFUSED),
	INDEXES_PULL("indexes.pull", Scope.INDEX, Anonymous.REFUSED),
	INDEXES_REINDEX("indexes.reindex", Scope.INDEX, Anonymous.REFUSED),

	KEYS_READ("keys.read", Scope.DEPLOYMENT, Anonymous.REFUSED),
	KEYS_WRITE("keys.write", Scope.DEPLOYMENT, Anonymous.REFUSED);

	/**
	 * Whether a permission is about one index or about the deployment.
	 */
	public enum Scope {
		/**
		 * The permission applies to the indexes a grant names, so a request
		 * carrying it is checked against the index it is for.
		 */
		INDEX,

		/**
		 * The permission is not about any one index, so the index patterns of
		 * the grant that carries it say nothing about it.
		 */
		DEPLOYMENT
	}

	/**
	 * Whether a permission may be reached without presenting a credential.
	 */
	private enum Anonymous {
		ALLOWED,
		REFUSED
	}

	private static final MapIterable<String, Permission> BY_ID = byId();

	private final String id;
	private final Scope scope;
	private final Anonymous anonymous;

	Permission(String id, Scope scope, Anonymous anonymous) {
		this.id = id;
		this.scope = scope;
		this.anonymous = anonymous;
	}

	/**
	 * The name this permission is stored and written as.
	 */
	public String id() {
		return id;
	}

	public Scope scope() {
		return scope;
	}

	/**
	 * Whether this permission may be held by the key a node answers
	 * unauthenticated requests as.
	 *
	 * <p>Anything that changes an index or reveals how the deployment is
	 * administered answers {@code false}, so a node configured to serve
	 * anonymous callers can only be made to serve searches. Widening this is a
	 * change to the engine rather than to a deployment's configuration.
	 */
	public boolean isAnonymousAllowed() {
		return anonymous == Anonymous.ALLOWED;
	}

	/**
	 * Look a permission up by the name it is stored under.
	 *
	 * @param id
	 * @return
	 *   empty when this build has no permission by that name, which is how a
	 *   key written by a newer version is read
	 */
	public static Optional<Permission> byId(String id) {
		return Optional.ofNullable(BY_ID.get(id));
	}

	private static MapIterable<String, Permission> byId() {
		var map = Maps.mutable.<String, Permission>empty();
		for(var permission : values()) {
			map.put(permission.id, permission);
		}

		return map.toImmutable();
	}
}
