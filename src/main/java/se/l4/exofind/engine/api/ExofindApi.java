package se.l4.exofind.engine.api;

import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

import jakarta.ws.rs.core.Application;

/**
 * Declares shared OpenAPI components for the API.
 *
 * <p>Resource classes declare routing independently of {@link Application}.
 * Shared OpenAPI components are discovered from {@link Application}, deployment
 * options are configured in {@code application.properties}, and endpoint
 * metadata is declared on each resource.
 *
 * <p>API requests authenticate using bearer tokens. Credentials are read only
 * from the Authorization header, never from cookies or query parameters. The
 * permission required by an endpoint is specified by its
 * {@code @RequiresPermission} annotation and documented in the endpoint
 * description.
 */
@SecurityScheme(
	securitySchemeName = ExofindApi.API_KEY,
	type = SecuritySchemeType.HTTP,
	scheme = "bearer",
	description = """
		An API key sent as a bearer token, such as \
		`Authorization: Bearer exok_4ff6b760264c1918_ePQcdT1O9HSATZoXfDbT8hhHGsP9VpZH`. \
		A key carries grants that pair permissions with index patterns; the \
		permission each endpoint needs is named in its description. Nodes \
		running with `EXOFIND_AUTH_MODE=none` accept requests without a \
		credential, and a node with `EXOFIND_AUTH_ANONYMOUS_KEY` set serves \
		requests that carry none with the permissions of that key."""
)
public class ExofindApi extends Application {
	/**
	 * Name the bearer scheme is referenced by, from the
	 * {@code @SecurityRequirement} of each resource. Part of the published
	 * document rather than of the code, so a generated client names its
	 * credential from it.
	 */
	public static final String API_KEY = "apiKey";
}
