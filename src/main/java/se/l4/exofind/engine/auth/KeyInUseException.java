package se.l4.exofind.engine.auth;

import se.l4.exofind.engine.errors.ErrorType;

/**
 * Thrown when a key is one the node it is revoked through depends on.
 *
 * <p>Two keys are load bearing rather than ordinary. The last key granted
 * {@code keys.write} is the only credential that could create another one on a
 * node with no root key, and the key named by
 * {@code EXOFIND_AUTH_ANONYMOUS_KEY} is the one such a node answers requests
 * carrying no credential as. Revoking either leaves a node that refuses to
 * start, which is found out at the next restart rather than at the request that
 * caused it - so the request is refused instead, and the stored keys are left
 * exactly as they were.
 *
 * <p>Both are read from the configuration of the node answering the request,
 * the same values its startup checks read. A deployment whose nodes are
 * configured differently is therefore refused by the node that depends on the
 * key and allowed by one that does not.
 */
public class KeyInUseException extends AuthException {
	private static final long serialVersionUID = 1L;

	private static final ErrorType LAST_ADMINISTRATOR =
		ErrorType.withCode("auth:key:last_administrator")
			.withArguments("key")
			.withMessage(
				"`{{key}}` is the last key granted `keys.write` and this node has no root"
					+ " key, so revoking it would leave nobody able to create another."
					+ " Create a replacement key first, or set EXOFIND_AUTH_ROOT_KEY"
			);

	private static final ErrorType ANONYMOUS =
		ErrorType.withCode("auth:key:in_use_as_anonymous")
			.withArguments("key")
			.withMessage(
				"`{{key}}` is what EXOFIND_AUTH_ANONYMOUS_KEY names on this node, so"
					+ " revoking it would stop the node from starting. Point"
					+ " EXOFIND_AUTH_ANONYMOUS_KEY somewhere else first, or unset it"
			);

	private KeyInUseException(ErrorType type, String id) {
		super(type, "key", id);
	}

	/**
	 * The only key left that could create another one.
	 */
	public static KeyInUseException lastAdministrator(String id) {
		return new KeyInUseException(LAST_ADMINISTRATOR, id);
	}

	/**
	 * The key this node answers requests carrying no credential as.
	 */
	public static KeyInUseException anonymous(String id) {
		return new KeyInUseException(ANONYMOUS, id);
	}
}
