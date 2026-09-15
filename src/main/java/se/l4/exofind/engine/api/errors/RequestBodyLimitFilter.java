package se.l4.exofind.engine.api.errors;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;

/**
 * Bounds the size of every request body the API reads.
 *
 * <p>{@link RefusedRequestRoute} refuses a body that states a
 * {@code Content-Length} past the limit before any of it arrives. A body sent
 * with chunked transfer encoding states no length, and a stated length is only
 * what the caller said it would send, so the bytes are counted as well: this
 * puts a {@link LimitedInputStream} in front of the body, and every endpoint
 * reads through it.
 *
 * <p>Wrapping rather than reading, because the endpoint decides when the body
 * is read. An endpoint that acts on a body as it arrives is refused in the
 * middle of its work, which is the point - the alternative is to hold a body of
 * any size to measure it.
 *
 * <p>Runs before every other filter, so the count is in place before anything
 * can read the body. {@code IndexerForwardFilter} reads it to pass a request
 * along, so a forwarded body is counted by the node that takes it in as well as
 * by the node that writes it.
 *
 * <p>Bounds the endpoints of the API alone. The management endpoints Quarkus
 * serves under {@code /q/} read no body.
 */
@Provider
@Priority(RequestBodyLimitFilter.PRIORITY)
public class RequestBodyLimitFilter implements ContainerRequestFilter {
	/**
	 * Where this runs among the filters. Ahead of
	 * {@code jakarta.ws.rs.Priorities.AUTHENTICATION}, which is the first of
	 * the ones this project declares.
	 */
	static final int PRIORITY = 500;

	private final RequestBodyLimits limits;

	public RequestBodyLimitFilter(RequestBodyLimits limits) {
		this.limits = limits;
	}

	@Override
	public void filter(ContainerRequestContext request) {
		var limit = limits.forContentType(request.getHeaderString(HttpHeaders.CONTENT_TYPE));
		var body = request.getEntityStream();

		if(limit == RequestBodyLimits.NONE || body == null) {
			return;
		}

		request.setEntityStream(new LimitedInputStream(body, limit));
	}
}
