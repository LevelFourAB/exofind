package se.l4.exofind.engine.auth;

/**
 * What a node checks before it answers a request.
 */
public enum AuthMode {
	/**
	 * Nothing. Every request is answered as a caller allowed everything, which
	 * is what dev mode runs as and what a deployment has to ask for by name.
	 */
	NONE,

	/**
	 * A credential, checked against the keys of the deployment and the root key
	 * of this node.
	 */
	KEYS
}
