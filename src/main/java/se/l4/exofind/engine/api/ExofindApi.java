package se.l4.exofind.engine.api;

import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

import jakarta.ws.rs.core.Application;

/**
 * The parts of the OpenAPI document that belong to the API as a whole rather
 * than to any one endpoint.
 *
 * Quarkus discovers resources without an {@link Application} class, so this one
 * declares nothing about routing - it exists because the document's shared
 * pieces have to hang off a type the OpenAPI scanner reads, and the scanner
 * reads them from the {@link Application}. Everything else about the document
 * is either configuration, in {@code application.properties}, or declared on the
 * endpoint it describes.
 *
 * The scheme declared here is the only way a request carries a credential:
 * every endpoint takes an API key as a bearer token, and nothing is read from a
 * cookie or a query parameter. What each endpoint then requires of that key is
 * said by its {@code @RequiresPermission}, and repeated in the endpoint's
 * description - a scheme says how to authenticate, not what it lets you do.
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
