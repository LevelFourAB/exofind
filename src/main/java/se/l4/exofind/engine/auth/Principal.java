package se.l4.exofind.engine.auth;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ListIterable;

/**
 * Who a request is being answered as, and what that lets it reach.
 *
 * <p>Every request has one, including a request that presented no credential
 * on a node that answers those. What differs is what it is allowed to do, so
 * the rest of the engine asks the same questions whatever a deployment is
 * configured to accept.
 *
 * @param id
 *   what a log records the caller as - the id of a key, or the name of one of
 *   the principals a deployment is configured into rather than granted
 * @param grants
 *   what the caller may do, ignored when {@code unrestricted}
 * @param unrestricted
 *   whether every permission is allowed on every index, which is what the root
 *   key and a node with authentication turned off answer as
 */
public record Principal(String id, ListIterable<Grant> grants, boolean unrestricted) {
	/**
	 * Id of the principal a node with authentication turned off answers as.
	 */
	public static final String UNCHECKED = "unchecked";

	/**
	 * Id of the principal the configured root key answers as.
	 */
	public static final String ROOT = "root";

	public Principal {
		grants = grants == null ? Lists.immutable.empty() : Lists.immutable.ofAll(grants);
	}

	/**
	 * Id of the principal that stands for nobody.
	 */
	public static final String NONE = "none";

	/**
	 * The principal allowed nothing at all, which is what a caller is until a
	 * request has been through {@code AuthFilter}.
	 */
	public static Principal none() {
		return new Principal(NONE, Lists.immutable.empty(), false);
	}

	/**
	 * The principal for a node that checks nothing, allowed everything because
	 * there is nothing to tell one caller from another.
	 */
	public static Principal unchecked() {
		return new Principal(UNCHECKED, Lists.immutable.empty(), true);
	}

	/**
	 * The principal for the configured root key, allowed everything so that a
	 * deployment always has a way back in.
	 */
	public static Principal root() {
		return new Principal(ROOT, Lists.immutable.empty(), true);
	}

	/**
	 * The principal for a presented key.
	 */
	public static Principal of(Key key) {
		return new Principal(key.id(), key.grants(), false);
	}

	/**
	 * The principal for a request that presented no credential, as the key a
	 * node is configured to answer those with.
	 *
	 * <p>Permissions the key holds that {@link Permission#isAnonymousAllowed()}
	 * refuses are left out rather than honoured, so a key that gains one after
	 * the node started widens nothing.
	 *
	 * @param key
	 *   key named by {@code exofind.auth.anonymous-key}
	 * @return
	 */
	public static Principal anonymous(Key key) {
		var grants = key.grants()
			.collect(
				grant -> new Grant(
					grant.permissions().select(Permission::isAnonymousAllowed),
					grant.indexes()
				)
			)
			.reject(grant -> grant.permissions().isEmpty());

		return new Principal(key.id(), grants, false);
	}

	/**
	 * Whether a permission that names no index is allowed.
	 */
	public boolean allows(Permission permission) {
		return unrestricted || grants.anySatisfy(grant -> grant.allows(permission));
	}

	/**
	 * Whether a permission is allowed on one index.
	 */
	public boolean allows(Permission permission, String index) {
		return unrestricted || grants.anySatisfy(grant -> grant.allows(permission, index));
	}

	/**
	 * Whether a permission is allowed on any index at all.
	 *
	 * <p>What a request that is about the indexes without naming one is checked
	 * against, such as listing them. The answer says only that there is
	 * something to see; which indexes those are is decided per index by
	 * {@link #covers(String)}.
	 */
	public boolean allowsAny(Permission permission) {
		return unrestricted
			|| grants.anySatisfy(
				grant -> grant.permissions().contains(permission) && grant.indexes().notEmpty()
			);
	}

	/**
	 * Whether an index is one this caller is allowed to know exists.
	 *
	 * <p>An index no grant covers is answered as if it were not there, so the
	 * names a deployment holds can not be found by comparing a refusal against
	 * a miss.
	 */
	public boolean covers(String index) {
		return unrestricted || grants.anySatisfy(grant -> grant.covers(index));
	}
}
