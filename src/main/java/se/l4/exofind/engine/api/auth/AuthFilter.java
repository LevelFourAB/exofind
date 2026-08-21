package se.l4.exofind.engine.api.auth;

import se.l4.exofind.engine.api.routing.ServedBy;
import se.l4.exofind.engine.auth.ForbiddenException;
import se.l4.exofind.engine.auth.Keys;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.auth.Principal;
import se.l4.exofind.engine.index.IndexNotFoundException;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;

/**
 * Works out who a request is from and whether they may make it.
 *
 * <p>Every endpoint is checked in one place rather than each checking itself,
 * because an endpoint that forgets is indistinguishable from one that is meant
 * to be open. What each needs is declared with {@link RequiresPermission}, and
 * a resource method that declares nothing is refused.
 *
 * <p>Being refused comes in two shapes. An index the caller was granted nothing
 * on is answered as though it did not exist, so the names a deployment holds
 * cannot be found by comparing a refusal against a miss; an index they can
 * reach but not this way is refused outright.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuthFilter implements ContainerRequestFilter {
	@Context
	ResourceInfo resourceInfo;

	private final Keys keys;
	private final AuthContext context;

	public AuthFilter(Keys keys, AuthContext context) {
		this.keys = keys;
		this.context = context;
	}

	@Override
	public void filter(ContainerRequestContext request) {
		var method = resourceInfo.getResourceMethod();
		if(method == null) {
			// Nothing was matched, so there is nothing to be allowed to reach
			return;
		}

		var principal = keys.resolve(request.getHeaderString(HttpHeaders.AUTHORIZATION));
		context.set(principal);

		var required = method.getAnnotation(RequiresPermission.class);
		if(required == null) {
			throw new IllegalStateException(
				"Endpoint " + method.getDeclaringClass().getName() + "#" + method.getName()
					+ " does not say what it requires, so it cannot be served. Annotate it"
					+ " with @RequiresPermission"
			);
		}

		check(
			principal,
			required,
			request.getUriInfo().getPathParameters().getFirst(ServedBy.INDEX_PARAMETER)
		);
	}

	private static void check(
		Principal principal,
		RequiresPermission required,
		String index
	) {
		var permission = required.value();

		if(permission.scope() == Permission.Scope.DEPLOYMENT) {
			if(!principal.allows(permission)) {
				throw new ForbiddenException(permission);
			}

			return;
		}

		if(required.anyIndex()) {
			if(!principal.allowsAny(permission)) {
				throw new ForbiddenException(permission);
			}

			return;
		}

		if(index == null) {
			throw new IllegalStateException(
				"An endpoint requiring " + permission.id() + " has no `"
					+ ServedBy.INDEX_PARAMETER
					+ "` path parameter to check it against, and did not declare anyIndex"
			);
		}

		if(!principal.covers(index)) {
			throw new IndexNotFoundException(index);
		}

		if(!principal.allows(permission, index)) {
			throw new ForbiddenException(permission);
		}
	}
}
