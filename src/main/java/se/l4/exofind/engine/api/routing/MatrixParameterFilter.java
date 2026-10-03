package se.l4.exofind.engine.api.routing;

import se.l4.exofind.engine.errors.EngineException;
import se.l4.exofind.engine.errors.ErrorType;
import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

/**
 * Refuses a request whose path holds a semicolon that is not percent-encoded.
 *
 * <p>The framework reads a semicolon in a path segment as the start of matrix
 * parameters, and removes them from the path before it selects an endpoint. A
 * request to {@code /documents/x;y} then reaches the document {@code x}: a
 * write replaces it, a delete removes it, and a read returns it. An index name
 * is cut the same way, and a key that starts with a semicolon reaches the
 * endpoints of the whole collection. No endpoint reads matrix parameters, so
 * a semicolon in the path is refused before an endpoint is selected. A
 * semicolon that is part of a key or a name is sent as {@code %3B}, which the
 * framework keeps in the segment.
 *
 * <p>Runs before every filter that reads the selected endpoint, so a refused
 * request is not authenticated, and it is not forwarded to the indexer with
 * the cut path.
 */
@Provider
@PreMatching
public class MatrixParameterFilter implements ContainerRequestFilter {
	static final ErrorType PATH_INVALID = ErrorType.withCode("request:path_invalid")
		.withStatus(400)
		.withMessage("The path holds a `;` that is not percent-encoded. Send it as `%3B`");

	/**
	 * The request as it arrived. The path the framework gives filters has
	 * the matrix parameters removed already.
	 */
	@Context
	HttpServerRequest http;

	@Override
	public void filter(ContainerRequestContext request) {
		check(http.path());
	}

	/**
	 * Check the path of a request as the client sent it, before any part of
	 * it is decoded.
	 *
	 * @throws EngineException
	 *   with {@code request:path_invalid} if the path holds a {@code ;}
	 */
	static void check(String rawPath) {
		if(rawPath != null && rawPath.indexOf(';') >= 0) {
			throw new EngineException(PATH_INVALID);
		}
	}
}
