package se.l4.exofind.engine.api.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import se.l4.exofind.engine.auth.ForbiddenException;
import se.l4.exofind.engine.auth.Grant;
import se.l4.exofind.engine.auth.Keys;
import se.l4.exofind.engine.auth.Permission;
import se.l4.exofind.engine.auth.Principal;
import se.l4.exofind.engine.auth.UnauthenticatedException;
import se.l4.exofind.engine.index.IndexNotFoundException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;

public class AuthFilterTest {
	/**
	 * Stands in for the resource methods, so the filter is tested against the
	 * annotation rather than against any one endpoint.
	 */
	static class Endpoints {
		@RequiresPermission(Permission.SEARCH)
		public void onOneIndex() {
		}

		@RequiresPermission(value = Permission.INDEXES_READ, anyIndex = true)
		public void onAnyIndex() {
		}

		@RequiresPermission(Permission.KEYS_WRITE)
		public void onTheDeployment() {
		}

		public void saysNothing() {
		}
	}

	Keys keys;
	AuthContext context;
	AuthFilter filter;

	@BeforeEach
	void setup() {
		keys = mock(Keys.class);
		context = new AuthContext();
		filter = new AuthFilter(keys, context);
	}

	private static Method endpoint(String name) {
		try {
			return Endpoints.class.getMethod(name);
		} catch(NoSuchMethodException e) {
			throw new IllegalStateException(e);
		}
	}

	private void run(String method, Principal principal, String index) {
		when(keys.resolve(any())).thenReturn(principal);

		var resourceInfo = mock(ResourceInfo.class);
		when(resourceInfo.getResourceMethod()).thenReturn(endpoint(method));
		filter.resourceInfo = resourceInfo;

		var parameters = new MultivaluedHashMap<String, String>();
		if(index != null) {
			parameters.putSingle("name", index);
		}

		var uriInfo = mock(UriInfo.class);
		when(uriInfo.getPathParameters()).thenReturn(parameters);

		var request = mock(ContainerRequestContext.class);
		when(request.getUriInfo()).thenReturn(uriInfo);

		filter.filter(request);
	}

	private static Principal principal(String index, Permission... permissions) {
		return new Principal(
			"0123456789abcdef",
			Lists.immutable.of(
				new Grant(Sets.immutable.of(permissions), Lists.immutable.of(index))
			),
			false
		);
	}

	@Test
	void aGrantedRequestIsLetThrough() {
		run("onOneIndex", principal("books", Permission.SEARCH), "books");

		assertThat(context.principal().id(), is("0123456789abcdef"));
	}

	@Test
	void anIndexTheCallerWasGrantedNothingOnIsAnsweredAsMissing() {
		/*
		 * Not a refusal: the two answers have to look the same, or the names a
		 * deployment holds can be found by asking about them one at a time.
		 */
		assertThrows(
			IndexNotFoundException.class,
			() -> run("onOneIndex", principal("books", Permission.SEARCH), "movies")
		);
	}

	@Test
	void anIndexTheCallerCanSeeButNotUseThisWayIsRefused() {
		assertThrows(
			ForbiddenException.class,
			() -> run("onOneIndex", principal("books", Permission.INDEXES_READ), "books")
		);
	}

	@Test
	void anEndpointAboutTheIndexesPassesOnHoldingThePermissionAnywhere() {
		run("onAnyIndex", principal("books", Permission.INDEXES_READ), null);

		assertThat(context.principal().allowsAny(Permission.INDEXES_READ), is(true));
	}

	@Test
	void anEndpointAboutTheIndexesIsRefusedWithoutThePermissionAnywhere() {
		assertThrows(
			ForbiddenException.class,
			() -> run("onAnyIndex", principal("books", Permission.SEARCH), null)
		);
	}

	@Test
	void anEndpointAboutTheDeploymentIgnoresTheIndexPatterns() {
		run(
			"onTheDeployment",
			new Principal(
				"0123456789abcdef",
				Lists.immutable.of(
					new Grant(
						Sets.immutable.of(Permission.KEYS_WRITE),
						Lists.immutable.empty()
					)
				),
				false
			),
			null
		);
	}

	@Test
	void anEndpointThatSaysNothingAboutWhatItNeedsIsNotServed() {
		assertThrows(
			IllegalStateException.class,
			() -> run("saysNothing", Principal.root(), "books")
		);
	}

	@Test
	void aRefusedCredentialNeverReachesAnEndpoint() {
		when(keys.resolve(any())).thenThrow(new UnauthenticatedException());

		var resourceInfo = mock(ResourceInfo.class);
		when(resourceInfo.getResourceMethod()).thenReturn(endpoint("onOneIndex"));
		filter.resourceInfo = resourceInfo;

		assertThrows(
			UnauthenticatedException.class,
			() -> filter.filter(mock(ContainerRequestContext.class))
		);

		assertThat(context.principal().id(), is(Principal.NONE));
	}
}
