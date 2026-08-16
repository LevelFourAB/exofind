package se.l4.exofind.engine.api.auth;

import se.l4.exofind.engine.auth.Principal;
import jakarta.enterprise.context.RequestScoped;

/**
 * Who the request being handled is being answered as.
 *
 * <p>Set by {@link AuthFilter} before a resource method runs, for the endpoints
 * that narrow what they answer with rather than only being allowed or refused.
 */
@RequestScoped
public class AuthContext {
	/**
	 * Nobody until the filter says otherwise, so a response narrowed by the
	 * caller narrows to nothing rather than to everything if it is ever read
	 * without the filter having run.
	 */
	private Principal principal = Principal.none();

	/**
	 * The caller. A request that reached a resource method has been through the
	 * filter, so this is the principal the filter allowed.
	 */
	public Principal principal() {
		return principal;
	}

	public void set(Principal principal) {
		this.principal = principal;
	}
}
